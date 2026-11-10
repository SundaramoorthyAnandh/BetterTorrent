package com.bettertorrent.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.bettertorrent.app.BuildConfig
import kotlin.math.abs

/**
 * Remembers whether transfers were running, so we can tell when the phone killed the app
 * mid-download, restart it, and resume after a reboot.
 */
object BackgroundState {
    private const val PREFS = "background_state"
    private const val KEY_ACTIVE = "active"
    private const val KEY_BOOT_AT = "boot_at"
    private const val KEY_VERSION = "version"
    private const val KEY_KILL_ALERT = "kill_alert"
    private const val KEY_RESUME_ON_BOOT = "resume_on_boot"

    @Volatile
    private var lastActive: Boolean? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    fun setActive(context: Context, active: Boolean) {
        if (lastActive == active) return
        lastActive = active
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, active)
            .putLong(KEY_BOOT_AT, bootTime())
            .putInt(KEY_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    fun wasActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    /**
     * Call once at process start. If the last process died while transferring, and it wasn't a
     * reboot or an app update, the phone (or the user) killed it: raise the alert.
     */
    fun checkForKill(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return
        val sameBoot = abs(p.getLong(KEY_BOOT_AT, 0) - bootTime()) < 60_000
        val sameVersion = p.getInt(KEY_VERSION, -1) == BuildConfig.VERSION_CODE
        if (sameBoot && sameVersion) p.edit().putBoolean(KEY_KILL_ALERT, true).apply()
    }

    fun killAlertPending(context: Context): Boolean = prefs(context).getBoolean(KEY_KILL_ALERT, false)

    fun clearKillAlert(context: Context) {
        prefs(context).edit().putBoolean(KEY_KILL_ALERT, false).apply()
    }

    /** Mirrors the Settings switch so the boot receiver can read it without DataStore. */
    fun setResumeOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RESUME_ON_BOOT, enabled).apply()
    }

    fun resumeOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_RESUME_ON_BOOT, true)
}

/** Periodic and one-shot alarms that bring the service back if the phone killed it. */
object Watchdog {
    private const val TAG = "Watchdog"
    private const val INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    private fun intent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            intent(context, 0),
        )
    }

    /** One quick check shortly after the app is swiped away (some phones kill it then). */
    fun checkSoon(context: Context, delayMs: Long = 5_000) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, intent(context, 1))
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(intent(context, 0))
        am.cancel(intent(context, 1))
    }

    fun restartIfNeeded(context: Context) {
        if (!BackgroundState.wasActive(context)) {
            cancel(context)
            return
        }
        if (!TorrentService.running) {
            Log.i(TAG, "transfers were running but the service is gone; restarting")
            TorrentService.start(context)
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Watchdog.restartIfNeeded(context.applicationContext)
    }
}

/** Resumes unfinished transfers after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (BackgroundState.resumeOnBoot(app) && BackgroundState.wasActive(app)) {
            TorrentService.start(app)
            Watchdog.schedule(app)
        }
    }
}
