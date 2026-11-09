package com.bettertorrent.app.engine

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.TorrentHandle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Minimal view of a torrent's pieces, so streaming can be tested without a live session. */
interface PieceSource {
    fun havePiece(piece: Int): Boolean
    fun setPieceDeadline(piece: Int, deadlineMs: Int)
    fun clearPieceDeadlines()
}

class HandlePieceSource(private val handle: TorrentHandle) : PieceSource {
    override fun havePiece(piece: Int): Boolean = handle.isValid && handle.havePiece(piece)
    override fun setPieceDeadline(piece: Int, deadlineMs: Int) {
        if (handle.isValid) handle.setPieceDeadline(piece, deadlineMs)
    }
    override fun clearPieceDeadlines() {
        if (handle.isValid) handle.clearPieceDeadlines()
    }
}

class StreamTarget(
    val pieces: PieceSource,
    val file: File,
    /** Offset of this file inside the torrent's byte stream. */
    val fileOffset: Long,
    val fileSize: Long,
    val pieceLength: Int,
    val lastPiece: Int,
    val name: String,
    val mime: String,
)

interface StreamSource {
    fun resolveStream(hash: String, index: Int): StreamTarget?
    fun isServableFile(file: File): Boolean
    fun mimeFor(name: String): String
}

/**
 * Serves torrent files over loopback HTTP with Range support while they download.
 * Reads block until the needed piece is verified, and set piece deadlines ahead of
 * the read position so libtorrent fetches what the player needs next.
 */
class StreamServer(
    private val source: StreamSource,
    private val log: (String) -> Unit = {},
) : NanoHTTPD("127.0.0.1", 0) {

    /** Random path segment so other apps on the device can't guess stream URLs. */
    private val token: String = UUID.randomUUID().toString().replace("-", "").take(20)
    private val active = AtomicInteger(0)
    private val _activeStreams = MutableStateFlow(0)
    val activeStreams: StateFlow<Int> = _activeStreams.asStateFlow()

    @Volatile
    var lastActivityMs: Long = 0L
        private set

    fun startServer() {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
    }

    fun torrentUrl(hash: String, index: Int, name: String): String =
        "http://127.0.0.1:$listeningPort/s/$token/$hash/$index/${encodeName(name)}"

    fun fileUrl(file: File): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(file.absolutePath.toByteArray())
        return "http://127.0.0.1:$listeningPort/f/$token/$encoded/${encodeName(file.name)}"
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size < 3 || parts[1] != token) return notFound()
        lastActivityMs = System.currentTimeMillis()
        return try {
            when (parts[0]) {
                "s" -> {
                    if (parts.size < 4) return notFound()
                    val index = parts[3].toIntOrNull() ?: return notFound()
                    val target = source.resolveStream(parts[2], index) ?: return notFound()
                    ranged(session, target.fileSize, target.mime) { start, len ->
                        if (target.pieces.havePiece(pieceAt(target, start)).not()) target.pieces.clearPieceDeadlines()
                        TorrentInputStream(target, start, len, ::onStreamClosed).also { onStreamOpened() }
                    }
                }
                "f" -> {
                    val path = String(Base64.getUrlDecoder().decode(parts[2]))
                    val file = File(path)
                    if (!file.isFile || !source.isServableFile(file)) return notFound()
                    ranged(session, file.length(), source.mimeFor(file.name)) { start, len ->
                        BoundedFileInputStream(file, start, len, ::onStreamClosed).also { onStreamOpened() }
                    }
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            log("serve failed: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.message ?: "error")
        }
    }

    private fun ranged(
        session: IHTTPSession,
        size: Long,
        mime: String,
        open: (start: Long, length: Long) -> InputStream,
    ): Response {
        val range = parseRange(session.headers["range"], size)
        if (range == INVALID || (range != null && (range.first >= size || range.first > range.last))) {
            return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                addHeader("Content-Range", "bytes */$size")
            }
        }
        val start = range?.first ?: 0L
        val endInclusive = range?.last ?: (size - 1)
        val length = max(0L, endInclusive - start + 1)
        val body = if (session.method == Method.HEAD || length == 0L) ByteArrayInputStream(ByteArray(0)) else open(start, length)
        val status = if (range != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, mime, body, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$endInclusive/$size")
        }
    }

    private fun onStreamOpened() {
        _activeStreams.value = active.incrementAndGet()
        lastActivityMs = System.currentTimeMillis()
    }

    private fun onStreamClosed() {
        _activeStreams.value = max(0, active.decrementAndGet())
        lastActivityMs = System.currentTimeMillis()
    }

    private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")

    companion object {
        private val INVALID = LongRange(-1, -2)

        fun encodeName(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")

        internal fun pieceAt(t: StreamTarget, filePos: Long): Int = ((t.fileOffset + filePos) / t.pieceLength).toInt()

        /** Parses a single "bytes=a-b" range. Returns null when absent, [INVALID] when malformed. */
        fun parseRange(header: String?, size: Long): LongRange? {
            if (header.isNullOrBlank()) return null
            if (!header.startsWith("bytes=")) return INVALID
            val spec = header.removePrefix("bytes=").split(',')[0].trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return INVALID
            val a = spec.substring(0, dash).trim()
            val b = spec.substring(dash + 1).trim()
            return if (a.isEmpty()) {
                val suffix = b.toLongOrNull() ?: return INVALID
                LongRange(max(0L, size - suffix), size - 1)
            } else {
                val s = a.toLongOrNull() ?: return INVALID
                val e = (if (b.isEmpty()) size - 1 else b.toLongOrNull() ?: return INVALID).coerceAtMost(size - 1)
                LongRange(s, e)
            }
        }
    }
}

/** Reads a file region, blocking on pieces that libtorrent has not verified yet. */
class TorrentInputStream(
    private val t: StreamTarget,
    start: Long,
    length: Long,
    private val onClose: () -> Unit,
) : InputStream() {

    private var pos = start
    private val end = start + length
    private var raf: RandomAccessFile? = null
    private val closed = AtomicBoolean(false)
    private var prioritizedFrom = -1
    private val readAhead = max(MIN_READ_AHEAD_PIECES, (READ_AHEAD_BYTES / t.pieceLength).toInt())

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed.get()) throw IOException("Stream closed")
        if (len == 0) return 0
        if (pos >= end) return -1

        val piece = StreamServer.pieceAt(t, pos)
        awaitPiece(piece)

        val pieceEndInFile = (piece.toLong() + 1) * t.pieceLength - t.fileOffset
        val toRead = minOf(len.toLong(), end - pos, pieceEndInFile - pos).toInt()
        val file = raf ?: openFile().also { raf = it }

        var attempts = 0
        while (true) {
            file.seek(pos)
            val n = file.read(b, off, toRead)
            if (n > 0) {
                pos += n
                return n
            }
            // Verified data not visible yet (file still being extended); retry briefly.
            if (closed.get()) throw IOException("Stream closed")
            if (++attempts > 200) throw IOException("Data for piece $piece not readable")
            Thread.sleep(25)
        }
    }

    override fun available(): Int = 0

    private fun openFile(): RandomAccessFile {
        var attempts = 0
        while (!t.file.exists()) {
            if (closed.get()) throw IOException("Stream closed")
            if (++attempts > 400) throw IOException("File not created: ${t.file}")
            Thread.sleep(25)
        }
        return RandomAccessFile(t.file, "r")
    }

    private fun awaitPiece(piece: Int) {
        if (piece != prioritizedFrom) {
            prioritize(piece)
            prioritizedFrom = piece
        }
        if (t.pieces.havePiece(piece)) return
        var waited = 0L
        while (!closed.get()) {
            Thread.sleep(POLL_MS)
            if (t.pieces.havePiece(piece)) return
            waited += POLL_MS
            if (waited % REASSERT_MS == 0L) prioritize(piece)
            if (waited > MAX_WAIT_MS) throw IOException("Timed out waiting for piece $piece")
        }
        throw IOException("Stream closed")
    }

    /** Sets increasing deadlines on the next pieces so they arrive in playback order. */
    private fun prioritize(from: Int) {
        var p = from
        var i = 0
        while (p <= t.lastPiece && i < readAhead) {
            if (!t.pieces.havePiece(p)) t.pieces.setPieceDeadline(p, BASE_DEADLINE_MS + i * STEP_DEADLINE_MS)
            p++
            i++
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { raf?.close() }
            onClose()
        }
    }

    companion object {
        private const val READ_AHEAD_BYTES = 24L * 1024 * 1024
        private const val MIN_READ_AHEAD_PIECES = 4
        private const val BASE_DEADLINE_MS = 300
        private const val STEP_DEADLINE_MS = 250
        private const val POLL_MS = 100L
        private const val REASSERT_MS = 4000L
        private const val MAX_WAIT_MS = 10 * 60 * 1000L
    }
}

/** Plain file region stream for completed files. */
class BoundedFileInputStream(
    file: File,
    start: Long,
    length: Long,
    private val onClose: () -> Unit,
) : InputStream() {
    private val raf = RandomAccessFile(file, "r").apply { seek(start) }
    private var remaining = length
    private val closed = AtomicBoolean(false)

    override fun read(): Int {
        if (remaining <= 0) return -1
        val v = raf.read()
        if (v >= 0) remaining--
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = raf.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { raf.close() }
            onClose()
        }
    }
}
