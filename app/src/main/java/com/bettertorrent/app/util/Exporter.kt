package com.bettertorrent.app.util

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Copies files from app storage into the phone's public Download/BetterTorrent folder. */
object Exporter {
    private const val RELATIVE_ROOT = "Download/BetterTorrent"

    suspend fun exportToDownloads(context: Context, source: File, onProgress: (Float) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val files = if (source.isDirectory) source.walkTopDown().filter { it.isFile }.toList() else listOf(source)
            val total = files.sumOf { it.length() }.coerceAtLeast(1)
            var copied = 0L
            val base = source.parentFile ?: source
            val resolver = context.contentResolver
            val buffer = ByteArray(256 * 1024)
            for (file in files) {
                coroutineContext.ensureActive()
                val relDir = file.parentFile?.relativeTo(base)?.path.orEmpty()
                val relativePath = if (relDir.isEmpty()) RELATIVE_ROOT else "$RELATIVE_ROOT/$relDir"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                    put(MediaStore.Downloads.MIME_TYPE, FileKinds.mimeOf(file.name))
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Couldn't create ${file.name} in Downloads")
                try {
                    resolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                copied += n
                                onProgress(copied.toFloat() / total)
                            }
                        }
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Throwable) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            files.size
        }
}
