package com.bettertorrent.app.engine

import org.json.JSONObject
import java.io.File

/** Per-torrent metadata persisted next to the .torrent and resume-data files. */
data class TorrentMeta(
    val hash: String,
    val name: String,
    val savePath: String,
    val addedAt: Long,
    val sequential: Boolean,
    val completedNotified: Boolean,
    /** Comma separated libtorrent file priorities, used only if resume data is missing. */
    val priorities: String,
)

class TorrentStore(baseDir: File) {
    private val dir = File(baseDir, "torrents").apply { mkdirs() }

    fun torrentFile(hash: String) = File(dir, "$hash.torrent")
    fun resumeFile(hash: String) = File(dir, "$hash.resume")
    private fun metaFile(hash: String) = File(dir, "$hash.json")

    fun saveTorrentBytes(hash: String, bytes: ByteArray) = atomicWrite(torrentFile(hash), bytes)

    fun saveResume(hash: String, bytes: ByteArray) = atomicWrite(resumeFile(hash), bytes)

    fun save(meta: TorrentMeta) {
        val json = JSONObject()
            .put("hash", meta.hash)
            .put("name", meta.name)
            .put("savePath", meta.savePath)
            .put("addedAt", meta.addedAt)
            .put("sequential", meta.sequential)
            .put("completedNotified", meta.completedNotified)
            .put("priorities", meta.priorities)
        atomicWrite(metaFile(meta.hash), json.toString().toByteArray())
    }

    fun loadAll(): List<TorrentMeta> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            runCatching {
                val o = JSONObject(f.readText())
                TorrentMeta(
                    hash = o.getString("hash"),
                    name = o.optString("name"),
                    savePath = o.getString("savePath"),
                    addedAt = o.optLong("addedAt", f.lastModified()),
                    sequential = o.optBoolean("sequential", false),
                    completedNotified = o.optBoolean("completedNotified", false),
                    priorities = o.optString("priorities", ""),
                )
            }.getOrNull()
        }

    fun delete(hash: String) {
        torrentFile(hash).delete()
        resumeFile(hash).delete()
        metaFile(hash).delete()
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}
