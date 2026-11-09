package com.bettertorrent.app.util

import java.util.Locale

enum class FileKind { VIDEO, AUDIO, SUBTITLE, IMAGE, ARCHIVE, DOCUMENT, OTHER }

object FileKinds {
    private val video = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "3gp", "ogv")
    private val audio = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "alac", "ape")
    private val subtitle = setOf("srt", "vtt", "ass", "ssa", "sub")
    private val image = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    private val archive = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
    private val document = setOf("pdf", "txt", "nfo", "epub", "doc", "docx", "md", "mobi")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

    fun kindOf(name: String): FileKind = when (ext(name)) {
        in video -> FileKind.VIDEO
        in audio -> FileKind.AUDIO
        in subtitle -> FileKind.SUBTITLE
        in image -> FileKind.IMAGE
        in archive -> FileKind.ARCHIVE
        in document -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }

    fun isPlayable(name: String): Boolean = kindOf(name).let { it == FileKind.VIDEO || it == FileKind.AUDIO }

    fun mimeOf(name: String): String = when (ext(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "mpg", "mpeg" -> "video/mpeg"
        "ts", "m2ts" -> "video/mp2t"
        "3gp" -> "video/3gpp"
        "ogv" -> "video/ogg"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "alac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "wma" -> "audio/x-ms-wma"
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "txt", "nfo", "md" -> "text/plain"
        "epub" -> "application/epub+zip"
        "zip" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "torrent" -> "application/x-bittorrent"
        else -> "application/octet-stream"
    }

    /** Media3 MIME type for sideloaded subtitles, or null if unsupported. */
    fun subtitleMime(name: String): String? = when (ext(name)) {
        "srt" -> "application/x-subrip"
        "vtt" -> "text/vtt"
        "ass", "ssa" -> "text/x-ssa"
        else -> null
    }
}
