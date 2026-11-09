package com.bettertorrent.app.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long): String {
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        val pattern = if (v >= 100) "%.0f %s" else if (v >= 10) "%.1f %s" else "%.2f %s"
        return String.format(Locale.US, pattern, v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    fun percent(fraction: Float): String {
        val p = (fraction * 100f).coerceIn(0f, 100f)
        return if (p > 0f && p < 10f) String.format(Locale.US, "%.1f%%", p) else "${p.toInt()}%"
    }

    fun eta(seconds: Long): String {
        if (seconds < 0) return "∞"
        if (seconds < 60) return "${seconds}s"
        val m = seconds / 60
        if (m < 60) return "${m}m ${seconds % 60}s"
        val h = m / 60
        if (h < 24) return "${h}h ${m % 60}m"
        return "${h / 24}d ${h % 24}h"
    }

    fun date(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
