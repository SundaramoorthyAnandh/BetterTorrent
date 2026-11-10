package com.bettertorrent.app.ui.library

import android.app.Application
import android.os.StatFs
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bettertorrent.app.BetterTorrentApp
import com.bettertorrent.app.engine.FileTorrentLink
import com.bettertorrent.app.engine.TorrentEngine
import com.bettertorrent.app.util.Exporter
import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.FileKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class LibEntry(
    val file: File,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val childCount: Int,
    val kind: FileKind?,
    /** Download progress when the file (or something in the folder) is still downloading. */
    val downloading: Float?,
    val complete: Boolean,
) {
    val name: String get() = file.name
    val isPlayable: Boolean get() = !isDir && FileKinds.isPlayable(file.name)
}

data class StorageInfo(val appBytes: Long = 0, val totalBytes: Long = 0, val freeBytes: Long = 0)

enum class SortMode(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date added") }

data class LibraryUi(
    val roots: List<File> = emptyList(),
    val root: File? = null,
    val dir: File? = null,
    val entries: List<LibEntry> = emptyList(),
    val storage: StorageInfo = StorageInfo(),
    val sort: SortMode = SortMode.DATE,
    val loaded: Boolean = false,
) {
    val atRoot: Boolean get() = root == null || dir == null || dir == root
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val engine: TorrentEngine = (app as BetterTorrentApp).engine
    private val _ui = MutableStateFlow(LibraryUi())
    val ui = _ui.asStateFlow()
    private val _exporting = MutableStateFlow<Map<String, Float>>(emptyMap())
    val exporting = _exporting.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private val refreshLock = Mutex()

    fun rootLabel(root: File): String = when (TorrentEngine.canonical(root)) {
        TorrentEngine.canonical(engine.downloadDir()) -> "App storage"
        else -> root.name
    }

    fun refresh() {
        viewModelScope.launch {
            refreshLock.withLock {
                val next = withContext(Dispatchers.IO) { load(_ui.value) }
                _ui.value = next
            }
        }
    }

    private fun load(cur: LibraryUi): LibraryUi {
        val appRoot = engine.downloadDir().apply { mkdirs() }
        val roots = engine.libraryRoots().filter { it.exists() || it == appRoot }
        val root = cur.root?.takeIf { r -> roots.any { it == r } } ?: roots.first()
        val dir = cur.dir?.takeIf { it.isDirectory && isInside(it, root) } ?: root
        val links = engine.fileLinks()

        val entries = dir.listFiles().orEmpty()
            .filterNot { it.name.startsWith(".") }
            .map { f -> entryFor(f, links) }
        val sorted = sort(entries, cur.sort)

        val stat = runCatching { StatFs(root.path) }.getOrNull()
        val appBytes = roots.sumOf { diskUsage(it) }
        return cur.copy(
            roots = roots,
            root = root,
            dir = dir,
            entries = sorted,
            storage = StorageInfo(appBytes, stat?.totalBytes ?: 0, stat?.availableBytes ?: 0),
            loaded = true,
        )
    }

    private fun entryFor(f: File, links: Map<String, FileTorrentLink>): LibEntry {
        return if (f.isDirectory) {
            val prefix = TorrentEngine.canonical(f) + File.separator
            val inside = links.filterKeys { it.startsWith(prefix) }.values.filter { it.size > 0 }
            val wantedIncomplete = inside.any { !it.complete && it.downloaded > 0 }
            val progress = if (wantedIncomplete) {
                inside.sumOf { it.downloaded }.toFloat() / inside.sumOf { it.size }.coerceAtLeast(1)
            } else {
                null
            }
            LibEntry(
                file = f,
                isDir = true,
                size = folderSize(f),
                modified = f.lastModified(),
                childCount = f.listFiles()?.size ?: 0,
                kind = null,
                downloading = progress,
                complete = progress == null,
            )
        } else {
            val link = links[TorrentEngine.canonical(f)]
            val incomplete = link != null && !link.complete
            LibEntry(
                file = f,
                isDir = false,
                size = link?.size ?: f.length(),
                modified = f.lastModified(),
                childCount = 0,
                kind = FileKinds.kindOf(f.name),
                downloading = if (incomplete) link!!.progress else null,
                complete = !incomplete,
            )
        }
    }

    private fun sort(list: List<LibEntry>, mode: SortMode): List<LibEntry> {
        val cmp = when (mode) {
            SortMode.NAME -> compareBy<LibEntry> { it.name.lowercase() }
            SortMode.SIZE -> compareByDescending { it.size }
            SortMode.DATE -> compareByDescending { it.modified }
        }
        return list.sortedWith(compareByDescending<LibEntry> { it.isDir }.then(cmp))
    }

    fun setSort(mode: SortMode) {
        _ui.update { it.copy(sort = mode, entries = sort(it.entries, mode)) }
    }

    fun open(dir: File) {
        _ui.update { it.copy(dir = dir, entries = emptyList()) }
        refresh()
    }

    fun openPath(dir: File) = open(dir)

    /** Returns false when already at the root. */
    fun up(): Boolean {
        val cur = _ui.value
        if (cur.atRoot) return false
        open(cur.dir!!.parentFile ?: cur.root!!)
        return true
    }

    fun selectRoot(root: File) {
        _ui.update { it.copy(root = root, dir = root, entries = emptyList()) }
        refresh()
    }

    fun delete(entry: LibEntry) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.deletePath(entry.file) }
            _messages.tryEmit("Deleted “${entry.name}”")
            refresh()
        }
    }

    fun exportToDevice(entry: LibEntry) {
        val key = entry.file.absolutePath
        if (_exporting.value.containsKey(key)) return
        _exporting.update { it + (key to 0f) }
        viewModelScope.launch {
            try {
                val count = Exporter.exportToDownloads(getApplication(), entry.file) { p ->
                    _exporting.update { m -> if (m.containsKey(key)) m + (key to p) else m }
                }
                _messages.tryEmit("Saved ${if (count == 1) "“${entry.name}”" else "$count files"} to Download/BetterTorrent")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _messages.tryEmit("Couldn't save to Downloads: ${e.message ?: "storage error"}. Check free space and try again.")
            } finally {
                _exporting.update { it - key }
            }
        }
    }

    private fun isInside(f: File, root: File): Boolean {
        val p = TorrentEngine.canonical(f)
        val r = TorrentEngine.canonical(root)
        return p == r || p.startsWith(r + File.separator)
    }

    private fun folderSize(f: File): Long =
        if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Bytes actually allocated on disk (torrent files are sparse while downloading). */
    private fun diskUsage(f: File): Long =
        if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { file ->
            runCatching { Os.stat(file.path).st_blocks * 512 }.getOrElse { file.length() }
        }
}
