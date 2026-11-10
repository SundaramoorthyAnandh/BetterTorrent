package com.bettertorrent.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bettertorrent.app.BetterTorrentApp
import com.bettertorrent.app.data.AppSettings
import com.bettertorrent.app.engine.AddException
import com.bettertorrent.app.engine.AddSource
import com.bettertorrent.app.engine.EngineStats
import com.bettertorrent.app.engine.PlayRequest
import com.bettertorrent.app.engine.TorrentItem
import com.bettertorrent.app.engine.TorrentPreview
import com.bettertorrent.app.service.TorrentService
import com.bettertorrent.app.util.BatteryOptimization
import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.Magnet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AddState {
    data object Hidden : AddState
    data class Loading(val message: String) : AddState
    data class Failed(val message: String, val source: AddSource) : AddState
    data class Ready(val preview: TorrentPreview, val selected: Set<Int>, val busy: Boolean = false) : AddState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val bt = app as BetterTorrentApp
    val engine = bt.engine
    val torrents: StateFlow<List<TorrentItem>> = engine.torrents
    val stats: StateFlow<EngineStats> = engine.stats
    val settings: StateFlow<AppSettings> = bt.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _add = MutableStateFlow<AddState>(AddState.Hidden)
    val addState: StateFlow<AddState> = _add.asStateFlow()
    private var addJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _clipboardMagnet = MutableStateFlow<String?>(null)
    val clipboardMagnet: StateFlow<String?> = _clipboardMagnet.asStateFlow()
    private var lastClipboard: String? = null

    /** Torrent detail to open (e.g. from a notification tap). */
    val pendingDetail = MutableStateFlow<String?>(null)

    /** Show the battery-optimization dialog when the main screen next has focus. */
    val batteryPromptPending = MutableStateFlow(false)
    private var batteryChecked = false

    init {
        viewModelScope.launch { engine.ensureStarted() }
        // Ask once, the first time anything is downloading (covers torrents added before this version).
        viewModelScope.launch {
            torrents.first { list -> list.any { it.state.isActive } }
            maybeAskBatteryExemption()
        }
    }

    private fun maybeAskBatteryExemption() {
        if (batteryChecked) return
        batteryChecked = true
        viewModelScope.launch {
            val s = bt.settings.settings.first()
            if (!s.batteryPromptShown && !BatteryOptimization.isExempt(getApplication())) {
                batteryPromptPending.value = true
            }
        }
    }

    fun onBatteryPromptShown() {
        batteryPromptPending.value = false
        viewModelScope.launch { bt.settings.update { it.copy(batteryPromptShown = true) } }
    }

    private fun onTorrentStarted() {
        TorrentService.start(getApplication())
        maybeAskBatteryExemption()
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ------------------------------------------------------------ add flow

    /** Returns false when the text holds no magnet link. */
    fun addFromText(text: String): Boolean {
        val magnet = Magnet.extract(text) ?: return false
        startAdd(AddSource.Magnet(magnet))
        return true
    }

    fun addFromUri(uri: Uri) {
        _add.value = AddState.Loading("Reading torrent file…")
        addJob?.cancel()
        addJob = viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val (bytes, name) = withContext(Dispatchers.IO) {
                    val name = runCatching {
                        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull()
                    val data = cr.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(64 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_TORRENT_BYTES) throw AddException("This file is too large to be a .torrent.")
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } ?: throw AddException("Couldn't open that file. Pick it again from your file manager.")
                    data to name
                }
                runAdd(AddSource.TorrentFile(bytes, name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AddException) {
                _add.value = AddState.Failed(e.message ?: "Couldn't read the file.", AddSource.TorrentFile(ByteArray(0), null))
            } catch (e: Throwable) {
                _add.value = AddState.Failed("Couldn't read the file. Pick it again from your file manager.", AddSource.TorrentFile(ByteArray(0), null))
            }
        }
    }

    fun startAdd(source: AddSource) {
        addJob?.cancel()
        _add.value = AddState.Loading(
            if (source is AddSource.Magnet) "Fetching torrent info from peers…" else "Reading torrent…",
        )
        addJob = viewModelScope.launch { runAdd(source) }
    }

    private suspend fun runAdd(source: AddSource) {
        try {
            val preview = engine.preview(source)
            _add.value = AddState.Ready(preview, preview.files.map { it.index }.toSet())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AddException) {
            _add.value = AddState.Failed(e.message ?: "Couldn't load this torrent.", source)
        } catch (e: Throwable) {
            _add.value = AddState.Failed("Something went wrong loading this torrent. Try again.", source)
        }
    }

    fun retryAdd() {
        val failed = _add.value as? AddState.Failed ?: return
        val src = failed.source
        if (src is AddSource.TorrentFile && src.bytes.isEmpty()) {
            dismissAdd()
            return
        }
        startAdd(src)
    }

    fun toggleFile(index: Int) {
        val r = _add.value as? AddState.Ready ?: return
        _add.value = r.copy(selected = if (index in r.selected) r.selected - index else r.selected + index)
    }

    fun selectAllFiles(select: Boolean) {
        val r = _add.value as? AddState.Ready ?: return
        _add.value = r.copy(selected = if (select) r.preview.files.map { it.index }.toSet() else emptySet())
    }

    fun dismissAdd() {
        addJob?.cancel()
        _add.value = AddState.Hidden
    }

    fun confirmDownload() {
        val r = _add.value as? AddState.Ready ?: return
        if (r.selected.isEmpty()) return
        _add.value = r.copy(busy = true)
        viewModelScope.launch {
            try {
                engine.addTorrent(r.preview, r.selected, sequential = false)
                _add.value = AddState.Hidden
                onTorrentStarted()
                message(if (r.preview.alreadyAdded) "Updated “${r.preview.name}”" else "Downloading “${r.preview.name}”")
            } catch (e: Throwable) {
                _add.value = r.copy(busy = false)
                message("Couldn't add the torrent. Try again.")
            }
        }
    }

    /** Adds the torrent (if needed) and starts streaming [fileIndex] or the best media file. */
    fun confirmStream(fileIndex: Int?, onReady: (PlayRequest) -> Unit) {
        val r = _add.value as? AddState.Ready ?: return
        val index = fileIndex ?: bestStreamIndex(r.preview, r.selected) ?: run {
            message("No video or audio file in this torrent to stream.")
            return
        }
        _add.value = r.copy(busy = true)
        viewModelScope.launch {
            try {
                val hash = engine.addTorrent(r.preview, r.selected + index, sequential = true)
                if (!engine.awaitHandle(hash)) throw IllegalStateException("not added")
                val req = withContext(Dispatchers.IO) { engine.prepareStream(hash, index) }
                    ?: throw IllegalStateException("no stream")
                _add.value = AddState.Hidden
                onReady(req)
                onTorrentStarted()
            } catch (e: Throwable) {
                _add.value = r.copy(busy = false)
                message("Couldn't start the stream. Try again.")
            }
        }
    }

    fun bestStreamIndex(preview: TorrentPreview, selected: Set<Int>): Int? {
        val media = preview.files.filter { it.isPlayable }
        val pick = media.filter { it.index in selected && it.kind == FileKind.VIDEO }.maxByOrNull { it.size }
            ?: media.filter { it.kind == FileKind.VIDEO }.maxByOrNull { it.size }
            ?: media.filter { it.index in selected }.maxByOrNull { it.size }
            ?: media.maxByOrNull { it.size }
        return pick?.index
    }

    // ------------------------------------------------------------ clipboard

    fun onClipboardText(text: String?) {
        if (!settings.value.clipboardDetect) return
        val magnet = Magnet.extract(text) ?: return
        if (magnet == lastClipboard) return
        lastClipboard = magnet
        val hash = Magnet.infoHash(magnet)
        if (hash != null && torrents.value.any { it.hash == hash }) return
        _clipboardMagnet.value = magnet
    }

    fun dismissClipboard() {
        _clipboardMagnet.value = null
    }

    fun addClipboard() {
        val m = _clipboardMagnet.value ?: return
        _clipboardMagnet.value = null
        startAdd(AddSource.Magnet(m))
    }

    // ------------------------------------------------------------ torrent actions

    fun pause(hash: String) = engine.pause(hash)
    fun resume(hash: String) = engine.resume(hash)
    fun setSequential(hash: String, enabled: Boolean) = engine.setSequential(hash, enabled)
    fun setFileWanted(hash: String, index: Int, wanted: Boolean) = engine.setFileWanted(hash, index, wanted)
    fun setAllFilesWanted(hash: String, wanted: Boolean) = engine.setAllFilesWanted(hash, wanted)

    fun remove(hash: String, deleteFiles: Boolean) {
        val name = torrents.value.firstOrNull { it.hash == hash }?.name
        viewModelScope.launch(Dispatchers.IO) { engine.remove(hash, deleteFiles) }
        message(if (deleteFiles) "Removed “$name” and its files" else "Removed “$name”. Files kept in Library.")
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { bt.settings.update(transform) }
    }

    companion object {
        private const val MAX_TORRENT_BYTES = 20 * 1024 * 1024
    }
}
