package com.bettertorrent.app.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.bettertorrent.app.data.AppSettings
import com.bettertorrent.app.data.DownloadLocation
import com.bettertorrent.app.data.SettingsRepository
import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.FileKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * On-device BitTorrent engine (libtorrent via libtorrent4j).
 *
 * Works like a personal Seedr: torrents download into app storage ("your cloud"),
 * every file can be streamed while it downloads, and finished files can be
 * exported to the phone's Downloads folder.
 */
class TorrentEngine(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
) : StreamSource {

    private val session = SessionManager(false)
    private val store = TorrentStore(context.filesDir)
    private val metas = ConcurrentHashMap<String, TorrentMeta>()
    private val infos = ConcurrentHashMap<String, TorrentInfo>()
    private val fileLists = ConcurrentHashMap<String, List<TorrentFileInfo>>()
    private val bestMedia = ConcurrentHashMap<String, Int>()
    private val handles = ConcurrentHashMap<String, TorrentHandle>()
    private val pendingResumeSaves = AtomicInteger(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val startMutex = Mutex()
    private val jobs = mutableListOf<Job>()

    @Volatile
    var isStarted = false
        private set

    @Volatile
    private var settings = AppSettings()

    private enum class NetType { WIFI, MOBILE, OTHER, NONE }

    @Volatile
    private var netType = NetType.NONE

    @Volatile
    private var networkBlock = NetworkBlock.NONE

    private val _torrents = MutableStateFlow<List<TorrentItem>>(emptyList())
    val torrents: StateFlow<List<TorrentItem>> = _torrents.asStateFlow()

    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()

    val streamServer = StreamServer(this) { Log.d(TAG, it) }

    // ---------------------------------------------------------------- lifecycle

    suspend fun ensureStarted() {
        if (isStarted) return
        startMutex.withLock {
            if (isStarted) return
            withContext(Dispatchers.IO) {
                settings = settingsRepo.settings.first()
                session.addListener(alertListener)
                // POSIX file I/O instead of mmap: safer on Android's FUSE-backed shared storage.
                session.start(SessionParams(buildSettingsPack(settings)).apply { setPosixDiskIO() })
                restoreTorrents()
                streamServer.startServer()
            }
            isStarted = true
            registerNetworkWatcher()
            jobs += scope.launch { pollLoop() }
            jobs += scope.launch { resumeDataLoop() }
            jobs += scope.launch { settingsRepo.settings.collect { applySettings(it) } }
        }
    }

    /** Saves state and stops the session (notification "Exit"). */
    suspend fun shutdown() {
        startMutex.withLock {
            if (!isStarted) return
            jobs.forEach { it.cancel() }
            jobs.clear()
            unregisterNetworkWatcher()
            withContext(Dispatchers.IO) {
                saveAllResumeDataBlocking()
                runCatching { streamServer.stop() }
                session.removeListener(alertListener)
                runCatching { session.stop() }
            }
            handles.clear()
            metas.clear()
            infos.clear()
            fileLists.clear()
            bestMedia.clear()
            _torrents.value = emptyList()
            _stats.value = EngineStats()
            isStarted = false
        }
    }

    private fun buildSettingsPack(s: AppSettings): SettingsPack {
        val sp = SettingsPack()
            .downloadRateLimit(s.downloadLimitKb * 1024)
            .uploadRateLimit(s.uploadLimitKb * 1024)
            .activeDownloads(s.maxActiveDownloads)
            .activeSeeds(if (s.seedAfterComplete) 5 else 1)
            .activeLimit(max(10, s.maxActiveDownloads + 5))
            .connectionsLimit(200)
        // Make "max active downloads" mean exactly that.
        sp.setBoolean(settings_pack.bool_types.dont_count_slow_torrents.swigValue(), false)
        // Find peers faster.
        sp.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        sp.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        return sp
    }

    private fun applySettings(s: AppSettings) {
        val old = settings
        settings = s
        if (!isStarted) return
        session.applySettings(buildSettingsPack(s))
        updateNetworkPause()
        if (old.seedAfterComplete && !s.seedAfterComplete) {
            for (hash in metas.keys) {
                val h = handleFor(hash) ?: continue
                if (h.status().isFinished) pauseHandle(h)
            }
        }
    }

    // ---------------------------------------------------------------- network (Wi-Fi / mobile data switches)

    private val connectivity by lazy { context.getSystemService(ConnectivityManager::class.java) }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            netType = classify(caps)
            updateNetworkPause()
        }

        override fun onLost(network: Network) {
            // No default network: nothing can transfer anyway, so don't flag a settings block.
            netType = NetType.NONE
            updateNetworkPause()
        }
    }

    private fun classify(caps: NetworkCapabilities?): NetType = when {
        caps == null -> NetType.NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetType.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetType.MOBILE
        else -> NetType.OTHER
    }

    private fun registerNetworkWatcher() {
        runCatching {
            netType = classify(connectivity.getNetworkCapabilities(connectivity.activeNetwork))
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
        updateNetworkPause()
    }

    private fun unregisterNetworkWatcher() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
    }

    @Synchronized
    private fun updateNetworkPause() {
        if (!isStarted) return
        val block = when (netType) {
            NetType.WIFI -> if (settings.downloadOnWifi) NetworkBlock.NONE else NetworkBlock.WIFI_OFF
            NetType.MOBILE -> if (settings.downloadOnMobile) NetworkBlock.NONE else NetworkBlock.MOBILE_OFF
            NetType.OTHER, NetType.NONE -> NetworkBlock.NONE
        }
        networkBlock = block
        val shouldPause = block != NetworkBlock.NONE
        if (shouldPause && !session.isPaused) session.pause()
        if (!shouldPause && session.isPaused) session.resume()
    }

    // ---------------------------------------------------------------- alerts

    private val alertListener = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.ADD_TORRENT.swig(),
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.SAVE_RESUME_DATA_FAILED.swig(),
            AlertType.TORRENT_FINISHED.swig(),
            AlertType.TORRENT_ERROR.swig(),
        )

        override fun alert(alert: Alert<*>) {
            try {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> onTorrentAdded(alert as AddTorrentAlert)
                    AlertType.SAVE_RESUME_DATA -> onResumeData(alert as SaveResumeDataAlert)
                    AlertType.SAVE_RESUME_DATA_FAILED -> pendingResumeSaves.decrementAndGet()
                    AlertType.TORRENT_FINISHED -> onTorrentFinished((alert as TorrentAlert<*>).handle())
                    AlertType.TORRENT_ERROR -> {
                        val a = alert as TorrentErrorAlert
                        _events.tryEmit(EngineEvent.Error(a.error().message ?: "Torrent error"))
                    }
                    else -> Unit
                }
            } catch (t: Throwable) {
                Log.w(TAG, "alert handling failed", t)
            }
        }
    }

    private fun onTorrentAdded(a: AddTorrentAlert) {
        if (a.error().isError) {
            _events.tryEmit(EngineEvent.Error("Couldn't add torrent: ${a.error().message}"))
            return
        }
        val h = a.handle()
        val hash = h.infoHash().toHex()
        if (!metas.containsKey(hash)) return // metadata fetch helper torrent
        handles[hash] = h
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    private fun onResumeData(a: SaveResumeDataAlert) {
        pendingResumeSaves.decrementAndGet()
        val hash = a.handle().infoHash().toHex()
        if (!metas.containsKey(hash)) return
        store.saveResume(hash, AddTorrentParams.writeResumeDataBuf(a.params()))
    }

    private fun onTorrentFinished(h: TorrentHandle) {
        val hash = h.infoHash().toHex()
        val meta = metas[hash] ?: return
        h.saveResumeData()
        if (!settings.seedAfterComplete) pauseHandle(h)
        if (!meta.completedNotified) {
            updateMeta(hash) { it.copy(completedNotified = true) }
            _events.tryEmit(EngineEvent.Completed(hash, meta.name))
        }
    }

    // ---------------------------------------------------------------- polling

    private suspend fun pollLoop() {
        while (scope.isActive) {
            try {
                refresh()
            } catch (t: Throwable) {
                Log.w(TAG, "refresh failed", t)
            }
            delay(1000)
        }
    }

    private suspend fun resumeDataLoop() {
        while (scope.isActive) {
            delay(60_000)
            for (hash in metas.keys) {
                val h = handleFor(hash) ?: continue
                if (h.needSaveResumeData()) {
                    pendingResumeSaves.incrementAndGet()
                    h.saveResumeData()
                }
            }
        }
    }

    private fun refresh() {
        val items = metas.values.mapNotNull { meta ->
            val h = handleFor(meta.hash) ?: return@mapNotNull null
            runCatching { toItem(meta, h) }.getOrNull()
        }.sortedByDescending { it.addedAt }
        _torrents.value = items
        _stats.value = EngineStats(
            downRate = items.sumOf { it.downRate },
            upRate = items.sumOf { it.upRate },
            networkBlock = networkBlock,
            running = true,
        )
    }

    private fun toItem(meta: TorrentMeta, h: TorrentHandle): TorrentItem {
        val st = h.status()
        val flags = st.flags()
        val paused = flags.has(TorrentFlags.PAUSED)
        val autoManaged = flags.has(TorrentFlags.AUTO_MANAGED)
        val err = st.errorCode()
        val finished = st.isFinished
        val lt = st.state()
        val state = when {
            err.isError -> TorrentState.ERROR
            lt == TorrentStatus.State.CHECKING_FILES || lt == TorrentStatus.State.CHECKING_RESUME_DATA -> TorrentState.CHECKING
            finished && (paused || !settings.seedAfterComplete) -> TorrentState.COMPLETED
            paused && autoManaged -> TorrentState.QUEUED
            paused -> TorrentState.PAUSED
            lt == TorrentStatus.State.DOWNLOADING_METADATA -> TorrentState.FETCHING_METADATA
            finished -> TorrentState.SEEDING
            else -> TorrentState.DOWNLOADING
        }
        val wanted = st.totalWanted()
        val done = st.totalWantedDone()
        val down = if (paused) 0L else st.downloadPayloadRate().toLong()
        val up = if (paused) 0L else st.uploadPayloadRate().toLong()
        val eta = if (down > 0) (wanted - done) / down else -1L
        val downloadedEver = max(st.allTimeDownload(), done)
        return TorrentItem(
            hash = meta.hash,
            name = meta.name.ifBlank { st.name() },
            state = state,
            progress = if (wanted > 0) (done.toFloat() / wanted).coerceIn(0f, 1f) else 1f,
            totalWanted = wanted,
            wantedDone = done,
            totalSize = infos[meta.hash]?.totalSize() ?: wanted,
            downRate = down,
            upRate = up,
            peers = st.numPeers(),
            seeds = st.numSeeds(),
            etaSeconds = eta,
            ratio = if (downloadedEver > 0) st.allTimeUpload().toFloat() / downloadedEver else 0f,
            savePath = meta.savePath,
            sequential = flags.has(TorrentFlags.SEQUENTIAL_DOWNLOAD),
            addedAt = meta.addedAt,
            error = if (err.isError) err.message else null,
            fileCount = fileLists[meta.hash]?.size ?: 0,
            bestMediaIndex = bestMedia[meta.hash] ?: -1,
        )
    }

    // ---------------------------------------------------------------- restore / add

    private fun restoreTorrents() {
        for (meta in store.loadAll()) {
            val torrentFile = store.torrentFile(meta.hash)
            if (!torrentFile.exists()) {
                store.delete(meta.hash)
                continue
            }
            try {
                val ti = TorrentInfo(torrentFile)
                cacheInfo(meta.hash, ti)
                metas[meta.hash] = meta
                val resume = store.resumeFile(meta.hash).takeIf { it.exists() }
                val priorities = if (resume == null) parsePriorities(meta.priorities, ti.numFiles()) else null
                var flags = torrent_flags_t()
                if (meta.sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                File(meta.savePath).mkdirs()
                session.download(ti, File(meta.savePath), resume, priorities, null, flags)
            } catch (t: Throwable) {
                Log.w(TAG, "restore failed for ${meta.hash}", t)
            }
        }
    }

    suspend fun preview(source: AddSource): TorrentPreview {
        ensureStarted()
        val bytes: ByteArray = when (source) {
            is AddSource.Magnet -> {
                val params = try {
                    AddTorrentParams.parseMagnetUri(source.uri)
                } catch (e: Throwable) {
                    throw AddException("That doesn't look like a valid magnet link. Copy the full link and try again.")
                }
                val hash = params.infoHashes.best.toHex()
                val stored = store.torrentFile(hash)
                if (metas.containsKey(hash) && stored.exists()) {
                    withContext(Dispatchers.IO) { stored.readBytes() }
                } else {
                    val temp = File(context.cacheDir, "metadata").apply { mkdirs() }
                    runInterruptible(Dispatchers.IO) {
                        session.fetchMagnet(source.uri, METADATA_TIMEOUT_S, temp)
                    } ?: throw AddException(
                        "No peers sent the torrent info within $METADATA_TIMEOUT_S seconds. " +
                            "Check your connection, or try a link with more seeders.",
                    )
                }
            }
            is AddSource.TorrentFile -> source.bytes
        }
        val ti = try {
            withContext(Dispatchers.IO) { TorrentInfo(bytes) }
        } catch (e: Throwable) {
            throw AddException("This file isn't a valid .torrent. Pick another file or paste a magnet link.")
        }
        val hash = ti.infoHash().toHex()
        return TorrentPreview(
            infoHash = hash,
            name = ti.name(),
            totalSize = ti.totalSize(),
            files = fileListOf(ti),
            torrentBytes = bytes,
            alreadyAdded = metas.containsKey(hash),
        )
    }

    /** Adds a torrent with the selected files. Returns its info-hash. */
    suspend fun addTorrent(preview: TorrentPreview, selected: Set<Int>, sequential: Boolean): String =
        withContext(Dispatchers.IO) {
            ensureStarted()
            val hash = preview.infoHash
            if (metas.containsKey(hash)) {
                handleFor(hash)?.let { h ->
                    for (i in selected) if (h.filePriority(i) == Priority.IGNORE) h.filePriority(i, Priority.DEFAULT)
                    if (sequential) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                    persistPriorities(hash, h)
                    if (h.status().flags().has(TorrentFlags.PAUSED)) resumeHandle(h)
                }
                return@withContext hash
            }

            val ti = TorrentInfo(preview.torrentBytes)
            val saveDir = currentDownloadDir().apply { mkdirs() }
            val priorities = Array(ti.numFiles()) { if (it in selected) Priority.DEFAULT else Priority.IGNORE }
            store.saveTorrentBytes(hash, preview.torrentBytes)
            val meta = TorrentMeta(
                hash = hash,
                name = ti.name(),
                savePath = saveDir.absolutePath,
                addedAt = System.currentTimeMillis(),
                sequential = sequential,
                completedNotified = false,
                priorities = priorities.joinToString(",") { it.swig().toString() },
            )
            store.save(meta)
            cacheInfo(hash, ti)
            metas[hash] = meta

            // The metadata-fetch helper torrent is removed asynchronously; wait for it to leave.
            val sha = Sha1Hash.parseHex(hash)
            var waited = 0
            while (session.find(sha)?.isValid == true && waited < 5000) {
                Thread.sleep(100)
                waited += 100
            }

            var flags = torrent_flags_t()
            if (sequential) flags = flags.or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            session.download(ti, saveDir, null, priorities, null, flags)
            hash
        }

    /** Waits until the session has a handle for [hash]. */
    suspend fun awaitHandle(hash: String, timeoutMs: Long = 8000): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (withContext(Dispatchers.IO) { handleFor(hash) } != null) return true
            delay(100)
            waited += 100
        }
        return false
    }

    // ---------------------------------------------------------------- controls

    fun pause(hash: String) {
        handleFor(hash)?.let { pauseHandle(it); it.saveResumeData() }
        refreshSoon()
    }

    fun resume(hash: String) {
        handleFor(hash)?.let { resumeHandle(it); it.saveResumeData() }
        refreshSoon()
    }

    fun pauseAll() {
        metas.keys.forEach { hash -> handleFor(hash)?.let { pauseHandle(it) } }
        refreshSoon()
    }

    fun resumeAll() {
        metas.keys.forEach { hash ->
            val h = handleFor(hash) ?: return@forEach
            val st = h.status()
            if (st.flags().has(TorrentFlags.PAUSED) && !(st.isFinished && !settings.seedAfterComplete)) resumeHandle(h)
        }
        refreshSoon()
    }

    private fun pauseHandle(h: TorrentHandle) {
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
    }

    private fun resumeHandle(h: TorrentHandle) {
        h.setFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
    }

    fun setSequential(hash: String, enabled: Boolean) {
        val h = handleFor(hash) ?: return
        if (enabled) h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD) else h.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        updateMeta(hash) { it.copy(sequential = enabled) }
        refreshSoon()
    }

    fun setFileWanted(hash: String, index: Int, wanted: Boolean) {
        val h = handleFor(hash) ?: return
        h.filePriority(index, if (wanted) Priority.DEFAULT else Priority.IGNORE)
        persistPriorities(hash, h)
        if (wanted && h.status().flags().has(TorrentFlags.PAUSED)) resumeHandle(h)
        h.saveResumeData()
        refreshSoon()
    }

    fun setAllFilesWanted(hash: String, wanted: Boolean) {
        val h = handleFor(hash) ?: return
        val n = infos[hash]?.numFiles() ?: return
        h.prioritizeFiles(Array(n) { if (wanted) Priority.DEFAULT else Priority.IGNORE })
        persistPriorities(hash, h)
        if (wanted && h.status().flags().has(TorrentFlags.PAUSED)) resumeHandle(h)
        h.saveResumeData()
        refreshSoon()
    }

    fun magnetUri(hash: String): String? = handleFor(hash)?.makeMagnetUri()

    fun remove(hash: String, deleteFiles: Boolean) {
        val h = handleFor(hash)
        val meta = metas.remove(hash)
        val ti = infos.remove(hash)
        handles.remove(hash)
        fileLists.remove(hash)
        bestMedia.remove(hash)
        store.delete(hash)
        if (h != null) {
            if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES) else session.remove(h)
        } else if (deleteFiles && meta != null && ti != null) {
            File(meta.savePath, ti.files().name()).deleteRecursively()
        }
        refreshSoon()
    }

    private fun refreshSoon() {
        scope.launch { runCatching { refresh() } }
    }

    // ---------------------------------------------------------------- files & streaming

    fun files(hash: String): List<TorrentFileState> {
        val h = handleFor(hash) ?: return emptyList()
        val meta = metas[hash] ?: return emptyList()
        val ti = infos[hash] ?: return emptyList()
        val list = fileLists[hash] ?: return emptyList()
        val progress = h.fileProgress(TorrentHandle.PIECE_GRANULARITY)
        val priorities = h.filePriorities()
        val fs = ti.files()
        return list.map { f ->
            TorrentFileState(
                info = f,
                downloaded = progress.getOrElse(f.index) { 0L }.coerceAtMost(f.size),
                wanted = priorities.getOrNull(f.index) != Priority.IGNORE,
                absolutePath = fs.filePath(f.index, meta.savePath),
            )
        }
    }

    fun fileInfo(hash: String, index: Int): TorrentFileInfo? = fileLists[hash]?.firstOrNull { it.index == index }

    fun fileList(hash: String): List<TorrentFileInfo> = fileLists[hash].orEmpty()

    /** Maps absolute file paths on disk to the torrent file they belong to. */
    fun fileLinks(): Map<String, FileTorrentLink> {
        val out = HashMap<String, FileTorrentLink>()
        for ((hash, meta) in metas) {
            val h = handleFor(hash) ?: continue
            val ti = infos[hash] ?: continue
            val progress = runCatching { h.fileProgress(TorrentHandle.PIECE_GRANULARITY) }.getOrNull() ?: continue
            val fs = ti.files()
            for (f in fileLists[hash].orEmpty()) {
                val path = canonical(File(fs.filePath(f.index, meta.savePath)))
                out[path] = FileTorrentLink(hash, f.index, f.size, progress.getOrElse(f.index) { 0L })
            }
        }
        return out
    }

    /**
     * Prepares a torrent file for streaming (prioritises it, turns on sequential
     * download, fetches header and tail pieces first) and returns its local URL.
     */
    fun prepareStream(hash: String, index: Int): PlayRequest? {
        val h = handleFor(hash) ?: return null
        val ti = infos[hash] ?: return null
        val fs = ti.files()
        if (index !in 0 until fs.numFiles()) return null

        if (h.filePriority(index) == Priority.IGNORE) {
            h.filePriority(index, Priority.DEFAULT)
            persistPriorities(hash, h)
        }
        val size = fs.fileSize(index)
        val have = h.fileProgress(TorrentHandle.PIECE_GRANULARITY).getOrElse(index) { 0L }
        if (have < size) {
            h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            updateMeta(hash) { it.copy(sequential = true) }
            if (h.status().flags().has(TorrentFlags.PAUSED)) {
                // Streaming jumps the queue.
                h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                h.resume()
            }
            if (size > 0) {
                val first = fs.pieceIndexAtFile(index)
                val last = fs.lastPieceIndexAtFile(index)
                for (i in 0 until 4) if (first + i <= last) h.setPieceDeadline(first + i, 300 * (i + 1))
                h.setPieceDeadline(last, 1500)
                if (last - 1 > first) h.setPieceDeadline(last - 1, 2000)
            }
        }
        refreshSoon()
        val name = fs.fileName(index)
        return PlayRequest(
            url = streamServer.torrentUrl(hash, index, name),
            title = name,
            mime = FileKinds.mimeOf(name),
            hash = hash,
            fileIndex = index,
        )
    }

    /** URL for a file on disk; uses the torrent stream when the file is still downloading. */
    fun playRequestForFile(file: File): PlayRequest {
        val link = fileLinks()[canonical(file)]
        if (link != null && !link.complete) prepareStream(link.hash, link.index)?.let { return it }
        return PlayRequest(
            url = streamServer.fileUrl(file),
            title = file.name,
            mime = FileKinds.mimeOf(file.name),
            hash = link?.hash,
            fileIndex = link?.index ?: -1,
        )
    }

    override fun resolveStream(hash: String, index: Int): StreamTarget? {
        val h = handleFor(hash) ?: return null
        val meta = metas[hash] ?: return null
        val ti = infos[hash] ?: return null
        val fs = ti.files()
        if (index !in 0 until fs.numFiles()) return null
        val name = fs.fileName(index)
        return StreamTarget(
            pieces = HandlePieceSource(h),
            file = File(fs.filePath(index, meta.savePath)),
            fileOffset = fs.fileOffset(index),
            fileSize = fs.fileSize(index),
            pieceLength = ti.pieceLength(),
            lastPiece = fs.lastPieceIndexAtFile(index),
            name = name,
            mime = FileKinds.mimeOf(name),
        )
    }

    override fun isServableFile(file: File): Boolean {
        val path = canonical(file)
        return libraryRoots().any { path.startsWith(canonical(it) + File.separator) }
    }

    override fun mimeFor(name: String): String = FileKinds.mimeOf(name)

    // ---------------------------------------------------------------- library support

    fun downloadDir(location: DownloadLocation): File = when (location) {
        DownloadLocation.APP -> File(context.getExternalFilesDir(null) ?: context.filesDir, "Downloads")
        DownloadLocation.PUBLIC -> File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BetterTorrent",
        )
    }

    fun canUsePublicFolder(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun currentDownloadDir(): File =
        if (settings.location == DownloadLocation.PUBLIC && canUsePublicFolder()) {
            downloadDir(DownloadLocation.PUBLIC)
        } else {
            downloadDir(DownloadLocation.APP)
        }

    /** Folders the Library can browse: app storage, plus the public folder when usable. */
    fun libraryRoots(): List<File> {
        val roots = mutableListOf(downloadDir(DownloadLocation.APP))
        if (canUsePublicFolder()) roots += downloadDir(DownloadLocation.PUBLIC)
        metas.values.map { File(it.savePath) }.forEach { if (roots.none { r -> canonical(r) == canonical(it) }) roots += it }
        return roots
    }

    /** Deletes a file or folder from the Library, removing or trimming torrents that own it. */
    fun deletePath(target: File) {
        val tPath = canonical(target)
        for (meta in metas.values.toList()) {
            val ti = infos[meta.hash] ?: continue
            val root = canonical(File(meta.savePath, ti.files().name()))
            when {
                root == tPath || root.startsWith(tPath + File.separator) -> remove(meta.hash, deleteFiles = true)
                tPath.startsWith(root + File.separator) -> {
                    val h = handleFor(meta.hash) ?: continue
                    val fs = ti.files()
                    var changed = false
                    for (i in 0 until fs.numFiles()) {
                        val p = canonical(File(fs.filePath(i, meta.savePath)))
                        if (p == tPath || p.startsWith(tPath + File.separator)) {
                            h.filePriority(i, Priority.IGNORE)
                            changed = true
                        }
                    }
                    if (changed) {
                        persistPriorities(meta.hash, h)
                        h.saveResumeData()
                    }
                }
            }
        }
        target.deleteRecursively()
        refreshSoon()
    }

    // ---------------------------------------------------------------- helpers

    private fun handleFor(hash: String): TorrentHandle? {
        handles[hash]?.let { if (it.isValid) return it }
        if (!isStarted && !session.isRunning) return null
        val h = runCatching { session.find(Sha1Hash.parseHex(hash)) }.getOrNull() ?: return null
        if (!h.isValid) return null
        handles[hash] = h
        return h
    }

    private fun cacheInfo(hash: String, ti: TorrentInfo) {
        infos[hash] = ti
        val list = fileListOf(ti)
        fileLists[hash] = list
        val best = list.filter { it.kind == FileKind.VIDEO }.maxByOrNull { it.size }
            ?: list.filter { it.kind == FileKind.AUDIO }.maxByOrNull { it.size }
        bestMedia[hash] = best?.index ?: -1
    }

    private fun fileListOf(ti: TorrentInfo): List<TorrentFileInfo> {
        val fs = ti.files()
        return (0 until fs.numFiles())
            .filterNot { fs.padFileAt(it) }
            .map { i -> TorrentFileInfo(i, fs.filePath(i), fs.fileName(i), fs.fileSize(i)) }
            .sortedBy { it.path.lowercase() }
    }

    private fun persistPriorities(hash: String, h: TorrentHandle) {
        val p = h.filePriorities().joinToString(",") { it.swig().toString() }
        updateMeta(hash) { it.copy(priorities = p) }
    }

    private fun parsePriorities(raw: String, count: Int): Array<Priority>? {
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != count) return null
        return Array(count) { Priority.fromSwig(parts[it]) }
    }

    private fun updateMeta(hash: String, transform: (TorrentMeta) -> TorrentMeta) {
        val cur = metas[hash] ?: return
        val next = transform(cur)
        if (next == cur) return
        metas[hash] = next
        runCatching { store.save(next) }
    }

    private fun saveAllResumeDataBlocking() {
        val hs = metas.keys.mapNotNull { handleFor(it) }
        pendingResumeSaves.set(hs.size)
        hs.forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
        val deadline = System.currentTimeMillis() + 4000
        while (pendingResumeSaves.get() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun torrent_flags_t.has(flag: torrent_flags_t): Boolean = and_(flag).non_zero()

    companion object {
        private const val TAG = "TorrentEngine"
        const val METADATA_TIMEOUT_S = 90

        fun canonical(f: File): String = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
    }
}
