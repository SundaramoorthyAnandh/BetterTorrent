package com.bettertorrent.app.engine

import org.junit.AfterClass
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.random.Random

/**
 * End-to-end check of streaming: a local seeder serves a torrent to a rate-limited
 * leecher, and the StreamServer must return correct bytes for ranged reads while
 * pieces are still arriving.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StreamServerTest {

    companion object {
        private const val SEED_PORT = 46881
        private const val LEECH_PORT = 46882
        private const val FILE_SIZE = 6 * 1024 * 1024 + 12345
        private const val PIECE = 64 * 1024

        private lateinit var work: File
        private lateinit var payload: ByteArray
        private lateinit var ti: TorrentInfo
        private lateinit var seeder: SessionManager
        private lateinit var leecher: SessionManager
        private lateinit var leechHandle: TorrentHandle
        private lateinit var server: StreamServer
        private lateinit var leechDir: File

        @BeforeClass
        @JvmStatic
        fun setUp() {
            loadNative()
            work = Files.createTempDirectory("bt-test").toFile()
            val seedDir = File(work, "seed").apply { mkdirs() }
            leechDir = File(work, "leech").apply { mkdirs() }
            payload = Random(42).nextBytes(FILE_SIZE)
            val content = File(seedDir, "movie.mp4").apply { writeBytes(payload) }

            val result = TorrentBuilder().path(content).pieceSize(PIECE).generate()
            ti = TorrentInfo.bdecode(result.entry().bencode())

            seeder = startSession(SEED_PORT, rateLimit = 0)
            leecher = startSession(LEECH_PORT, rateLimit = 0)

            seeder.download(ti, seedDir, null, null, null, torrent_flags_t())
            val seedHandle = waitFor("seeder handle") { seeder.find(ti.infoHash()) }
            // Start it now, otherwise the leecher's first connect is refused and backs off for a minute.
            seedHandle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            seedHandle.resume()
            // Session limits don't apply to local-network peers; throttle the torrent itself (~12 s total).
            seedHandle.setUploadLimit(512 * 1024)
            waitFor("seeder seeding") { seedHandle.status().takeIf { it.isSeeding } }

            val flags: torrent_flags_t = TorrentFlags.SEQUENTIAL_DOWNLOAD
            leecher.download(ti, leechDir, null, null, listOf(TcpEndpoint("127.0.0.1", SEED_PORT)), flags)
            leechHandle = waitFor("leecher handle") { leecher.find(ti.infoHash()) }
            leechHandle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            leechHandle.resume()

            val source = object : StreamSource {
                override fun resolveStream(hash: String, index: Int): StreamTarget? {
                    if (hash != ti.infoHash().toHex() || index != 0) return null
                    val fs = ti.files()
                    return StreamTarget(
                        pieces = HandlePieceSource(leechHandle),
                        file = File(fs.filePath(0, leechDir.absolutePath)),
                        fileOffset = fs.fileOffset(0),
                        fileSize = fs.fileSize(0),
                        pieceLength = ti.pieceLength(),
                        lastPiece = fs.lastPieceIndexAtFile(0),
                        name = fs.fileName(0),
                        mime = "video/mp4",
                    )
                }

                override fun isServableFile(file: File) = file.canonicalPath.startsWith(work.canonicalPath)
                override fun mimeFor(name: String) = "application/octet-stream"
            }
            server = StreamServer(source) { println("server: $it") }
            server.startServer()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.stop() }
            runCatching { leecher.stop() }
            runCatching { seeder.stop() }
            work.deleteRecursively()
        }

        private fun startSession(port: Int, rateLimit: Int): SessionManager {
            val sp = SettingsPack()
                .listenInterfaces("127.0.0.1:$port")
                .downloadRateLimit(rateLimit)
            sp.setEnableDht(false)
            sp.setEnableLsd(false)
            sp.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
            // Plain TCP on loopback: uTP's first handshake can stall and trigger a long reconnect backoff.
            sp.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
            sp.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
            // Same disk I/O backend as the app.
            return SessionManager(false).apply { start(SessionParams(sp).apply { setPosixDiskIO() }) }
        }

        private fun <T> waitFor(what: String, timeoutMs: Long = 10_000, get: () -> T?): T {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                get()?.let { return it }
                Thread.sleep(50)
            }
            throw AssertionError("timed out waiting for $what")
        }

        private fun loadNative() {
            if (System.getProperty("libtorrent4j.jni.path") != null) return
            // The Android x86_64 jar ships a same-named (bionic) library; pick the desktop Linux one.
            val res = StreamServerTest::class.java.classLoader!!.getResources("lib/x86_64/libtorrent4j.so")
                .toList()
                .firstOrNull { it.toString().contains("libtorrent4j-linux") }
                ?.openStream()
                ?: error("desktop native lib not on test classpath")
            val out = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
            res.use { input -> out.outputStream().use { input.copyTo(it) } }
            System.setProperty("libtorrent4j.jni.path", out.absolutePath)
        }
    }

    private fun url() = server.torrentUrl(ti.infoHash().toHex(), 0, "movie.mp4")

    private fun get(range: String?, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val c = URL(url()).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.readTimeout = 60_000
        if (range != null) c.setRequestProperty("Range", range)
        val code = c.responseCode
        val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
        val body = if (code < 400 && method == "GET") c.inputStream.use { it.readBytes() } else ByteArray(0)
        return Triple(code, headers, body)
    }

    @Test
    fun a_rangedReadFromMiddleStreamsBeforeDownloadFinishes() {
        val start = 4 * 1024 * 1024 + 777
        val end = start + 300_000
        val firstPiece = ((ti.files().fileOffset(0) + start) / ti.pieceLength()).toInt()
        assertTrue("piece should not be downloaded yet", !leechHandle.havePiece(firstPiece))

        val t0 = System.currentTimeMillis()
        val (code, headers, body) = get("bytes=$start-$end")
        val progress = leechHandle.status().progress()
        println("middle range served in ${System.currentTimeMillis() - t0} ms at torrent progress $progress")

        assertEquals(206, code)
        assertEquals("bytes $start-$end/$FILE_SIZE", headers["content-range"])
        assertEquals("bytes", headers["accept-ranges"])
        assertArrayEquals(payload.copyOfRange(start, end + 1), body)
        assertTrue("stream should be served before the whole torrent finishes (progress=$progress)", progress < 1f)
    }

    @Test
    fun b_suffixRangeReadsFileTail() {
        val (code, headers, body) = get("bytes=-5000")
        assertEquals(206, code)
        assertEquals("bytes ${FILE_SIZE - 5000}-${FILE_SIZE - 1}/$FILE_SIZE", headers["content-range"])
        assertArrayEquals(payload.copyOfRange(FILE_SIZE - 5000, FILE_SIZE), body)
    }

    @Test
    fun c_fullReadMatchesOriginal() {
        val (code, headers, body) = get(null)
        assertEquals(200, code)
        assertEquals(FILE_SIZE.toString(), headers["content-length"])
        assertArrayEquals(payload, body)
    }

    @Test
    fun d_headReportsLengthWithoutBody() {
        val (code, headers, _) = get("bytes=0-", method = "HEAD")
        assertEquals(206, code)
        assertEquals(FILE_SIZE.toString(), headers["content-length"])
    }

    @Test
    fun e_unsatisfiableRangeIs416() {
        val (code, headers, _) = get("bytes=${FILE_SIZE + 10}-")
        assertEquals(416, code)
        assertEquals("bytes */$FILE_SIZE", headers["content-range"])
    }

    @Test
    fun f_wrongTokenOrHashIs404() {
        val bad = url().replace("/s/", "/x/")
        assertEquals(404, (URL(bad).openConnection() as HttpURLConnection).responseCode)
        val badHash = server.torrentUrl("0".repeat(40), 0, "x")
        assertEquals(404, (URL(badHash).openConnection() as HttpURLConnection).responseCode)
    }

    @Test
    fun g_localFileRouteServesOnlyAllowedFiles() {
        val f = File(work, "note.txt").apply { writeText("hello library") }
        val c = URL(server.fileUrl(f)).openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        assertEquals("hello library", c.inputStream.bufferedReader().readText())

        val outside = File.createTempFile("outside", ".txt").apply { writeText("secret"); deleteOnExit() }
        assertEquals(404, (URL(server.fileUrl(outside)).openConnection() as HttpURLConnection).responseCode)
    }

    @Test
    fun h_parseRangeEdgeCases() {
        assertNull(StreamServer.parseRange(null, 100))
        assertEquals(10L..99L, StreamServer.parseRange("bytes=10-", 100))
        assertEquals(10L..20L, StreamServer.parseRange("bytes=10-20", 100))
        assertEquals(90L..99L, StreamServer.parseRange("bytes=-10", 100))
        assertEquals(0L..99L, StreamServer.parseRange("bytes=0-500", 100))
        assertTrue(StreamServer.parseRange("items=1-2", 100)!!.isEmpty())
    }
}
