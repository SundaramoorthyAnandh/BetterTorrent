package com.bettertorrent.app.util

import java.net.URLDecoder

object Magnet {
    private val magnetRegex = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val hexHash = Regex("""^[0-9a-fA-F]{40}$""")
    private val base32Hash = Regex("""^[A-Za-z2-7]{32}$""")

    /** Finds a magnet link in free text. A bare info-hash is turned into a magnet link. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null
        magnetRegex.find(text)?.let { return it.value }
        val trimmed = text.trim()
        if (hexHash.matches(trimmed) || base32Hash.matches(trimmed)) return "magnet:?xt=urn:btih:$trimmed"
        return null
    }

    /** The display name (dn=) of a magnet link, if present. */
    fun displayName(magnet: String): String? = runCatching {
        magnet.substringAfter('?').split('&')
            .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Lower-case hex info-hash from xt=urn:btih: when it is hex-encoded. */
    fun infoHash(magnet: String): String? =
        Regex("""xt=urn:btih:([0-9a-fA-F]{40})""").find(magnet)?.groupValues?.get(1)?.lowercase()
}
