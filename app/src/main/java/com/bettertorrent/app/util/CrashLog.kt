package com.bettertorrent.app.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.bettertorrent.app.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so the user can share it. Covers Kotlin/Java
 * exceptions (uncaught handler) and, on Android 11+, native crashes and freezes
 * (from the system's exit records). Nothing leaves the device unless the user shares it.
 */
/** How the previous process ended, as far as we can tell. */
enum class PreviousExit { CRASH, UPDATED, OTHER }

object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val KEY_LAST_START_TS = "last_start_ts"

    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                write(app, "Crash in thread \"${thread.name}\"\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Call at startup. Records a native crash or freeze from the previous run (Android 11+)
     * and says whether the previous process crashed or was replaced by an app update.
     */
    fun checkPreviousExit(context: Context): PreviousExit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previousStart = prefs.getLong(KEY_LAST_START_TS, 0)
        prefs.edit().putLong(KEY_LAST_START_TS, System.currentTimeMillis()).apply()
        val javaCrash = file(context).let { it.exists() && it.lastModified() > previousStart }
        val fallback = if (javaCrash) PreviousExit.CRASH else PreviousExit.OTHER
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        val am = context.getSystemService(ActivityManager::class.java) ?: return fallback
        val info = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }
            .getOrNull() ?: return fallback
        if (info.timestamp <= prefs.getLong(KEY_LAST_EXIT_TS, 0)) return fallback
        prefs.edit().putLong(KEY_LAST_EXIT_TS, info.timestamp).apply()
        val kind = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App froze (not responding)"
            ApplicationExitInfo.REASON_CRASH -> return PreviousExit.CRASH // the uncaught handler already saved it
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> return PreviousExit.UPDATED
            else -> return fallback
        }
        val details = buildString {
            append(kind).append("\n")
            append("Description: ").append(info.description ?: "-").append("\n")
            append("Signal/status: ").append(info.status).append("\n")
            append("At: ").append(stamp(info.timestamp)).append("\n")
            if (info.reason == ApplicationExitInfo.REASON_ANR) {
                runCatching {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        val text = CharArray(48 * 1024)
                        val n = r.read(text)
                        if (n > 0) append("\n").append(String(text, 0, n))
                    }
                }
            }
        }
        write(context, details)
        return PreviousExit.CRASH
    }

    /** The saved report, or null if there is none to show. */
    fun pendingReport(context: Context): String? {
        val f = file(context)
        if (!f.exists() || f.lastModified() <= lastSeenTs(context)) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("dismissed_ts", file(context).lastModified()).apply()
    }

    private fun lastSeenTs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dismissed_ts", 0)

    private fun write(context: Context, body: String) {
        val header = buildString {
            append("BetterTorrent ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Saved: ").append(stamp(System.currentTimeMillis())).append("\n\n")
        }
        file(context).writeText(header + body.take(64 * 1024))
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
