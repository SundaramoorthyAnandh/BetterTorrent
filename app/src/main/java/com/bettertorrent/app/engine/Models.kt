package com.bettertorrent.app.engine

import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.FileKinds

sealed interface AddSource {
    data class Magnet(val uri: String) : AddSource
    class TorrentFile(val bytes: ByteArray, val label: String?) : AddSource
}

class AddException(message: String) : Exception(message)

data class TorrentFileInfo(
    val index: Int,
    /** Path inside the torrent, e.g. "Movie/Movie.mkv". */
    val path: String,
    val name: String,
    val size: Long,
) {
    val kind: FileKind get() = FileKinds.kindOf(name)
    val isPlayable: Boolean get() = FileKinds.isPlayable(name)
    val folder: String get() = path.substringBeforeLast('/', "")
}

class TorrentPreview(
    val infoHash: String,
    val name: String,
    val totalSize: Long,
    val files: List<TorrentFileInfo>,
    val torrentBytes: ByteArray,
    val alreadyAdded: Boolean,
)

enum class TorrentState(val label: String) {
    FETCHING_METADATA("Fetching info"),
    CHECKING("Checking"),
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    SEEDING("Seeding"),
    COMPLETED("Completed"),
    PAUSED("Paused"),
    ERROR("Error");

    /** True while the torrent uses the network and should keep the background service alive. */
    val isActive: Boolean
        get() = this == DOWNLOADING || this == SEEDING || this == QUEUED || this == CHECKING || this == FETCHING_METADATA
}

data class TorrentItem(
    val hash: String,
    val name: String,
    val state: TorrentState,
    val progress: Float,
    val totalWanted: Long,
    val wantedDone: Long,
    val totalSize: Long,
    val downRate: Long,
    val upRate: Long,
    val peers: Int,
    val seeds: Int,
    val etaSeconds: Long,
    val ratio: Float,
    val savePath: String,
    val sequential: Boolean,
    val addedAt: Long,
    val error: String?,
    val fileCount: Int,
    /** Largest video (or audio) file in the torrent, -1 when there is none. */
    val bestMediaIndex: Int,
)

data class TorrentFileState(
    val info: TorrentFileInfo,
    val downloaded: Long,
    val wanted: Boolean,
    val absolutePath: String,
) {
    val progress: Float get() = if (info.size > 0) (downloaded.toFloat() / info.size).coerceIn(0f, 1f) else 1f
    val complete: Boolean get() = downloaded >= info.size
}

/** Download state of a file on disk that belongs to a torrent in the session. */
data class FileTorrentLink(val hash: String, val index: Int, val size: Long, val downloaded: Long) {
    val complete: Boolean get() = downloaded >= size
    val progress: Float get() = if (size > 0) (downloaded.toFloat() / size).coerceIn(0f, 1f) else 1f
}

data class EngineStats(
    val downRate: Long = 0,
    val upRate: Long = 0,
    val waitingForWifi: Boolean = false,
    val running: Boolean = false,
)

sealed interface EngineEvent {
    data class Completed(val hash: String, val name: String) : EngineEvent
    data class Error(val message: String) : EngineEvent
}

data class PlayRequest(
    val url: String,
    val title: String,
    val mime: String,
    val hash: String?,
    val fileIndex: Int,
)
