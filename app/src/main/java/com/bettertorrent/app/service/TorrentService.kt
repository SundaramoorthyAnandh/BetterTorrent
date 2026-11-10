package com.bettertorrent.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bettertorrent.app.AppEvents
import com.bettertorrent.app.BetterTorrentApp
import com.bettertorrent.app.R
import com.bettertorrent.app.engine.EngineStats
import com.bettertorrent.app.engine.NetworkBlock
import com.bettertorrent.app.engine.TorrentItem
import com.bettertorrent.app.engine.TorrentState
import com.bettertorrent.app.util.Fmt
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Keeps torrents running in the background with a progress notification. */
class TorrentService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var idleSince = 0L
    private var exiting = false
    private var watchdogScheduled = false

    override fun onCreate() {
        super.onCreate()
        running = true
        if (!enterForeground(buildNotification(emptyList(), EngineStats(), 0))) {
            stopSelf()
            return
        }
        val engine = (application as BetterTorrentApp).engine
        lifecycleScope.launch {
            engine.ensureStarted()
            combine(engine.torrents, engine.stats, engine.streamServer.activeStreams) { list, stats, streams ->
                Triple(list, stats, streams)
            }.collect { (list, stats, streams) -> onUpdate(list, stats, streams) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val engine = (application as BetterTorrentApp).engine
        when (intent?.action) {
            ACTION_PAUSE_ALL -> engine.pauseAll()
            ACTION_RESUME_ALL -> engine.resumeAll()
            ACTION_EXIT -> {
                exiting = true
                // User chose to stop: no kill alert, no auto-restart, no resume after reboot.
                BackgroundState.setActive(this, false)
                Watchdog.cancel(this)
                AppEvents.requestExit()
                lifecycleScope.launch {
                    engine.shutdown()
                    stopEverything()
                }
            }
        }
        // If the phone kills the process, Android restarts the service (allowed once the app is
        // exempt from battery optimization); onCreate then restores the torrents.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Some phones kill the process right after the app is swiped away: check back shortly.
        if (BackgroundState.wasActive(this)) Watchdog.checkSoon(this)
    }

    private fun onUpdate(list: List<TorrentItem>, stats: EngineStats, streams: Int) {
        if (exiting) return
        val busy = list.any { it.state.isActive } || streams > 0
        val now = System.currentTimeMillis()
        if (!busy) {
            if (idleSince == 0L) idleSince = now
            if (now - idleSince > IDLE_STOP_MS) {
                // Finished cleanly: nothing to restart or resume.
                BackgroundState.setActive(this, false)
                Watchdog.cancel(this)
                stopEverything()
                return
            }
        } else {
            idleSince = 0L
            if (list.any { it.state.isActive }) {
                BackgroundState.setActive(this, true)
                if (!watchdogScheduled) {
                    Watchdog.schedule(this)
                    watchdogScheduled = true
                }
            }
        }
        // Keep the CPU awake for anything that needs the network, so the screen turning off
        // doesn't stall libtorrent. Nothing to do while the network settings block transfers.
        if (busy && stats.networkBlock == NetworkBlock.NONE) acquireLocks() else releaseLocks()
        if (Notifications.canPost(this)) {
            try {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(list, stats, streams))
            } catch (_: SecurityException) {
            }
        }
    }

    private fun buildNotification(list: List<TorrentItem>, stats: EngineStats, streams: Int): Notification {
        val downloading = list.filter { it.state == TorrentState.DOWNLOADING || it.state == TorrentState.FETCHING_METADATA }
        val seeding = list.filter { it.state == TorrentState.SEEDING }
        val queued = list.count { it.state == TorrentState.QUEUED }
        val anyRunning = list.any { it.state.isActive }
        val anyPaused = list.any { it.state == TorrentState.PAUSED }

        val title = when {
            stats.networkBlock == NetworkBlock.MOBILE_OFF -> "Waiting for Wi‑Fi"
            stats.networkBlock == NetworkBlock.WIFI_OFF -> "Waiting for mobile data"
            downloading.isNotEmpty() -> "Downloading ${downloading.size} · ↓ ${Fmt.speed(stats.downRate)}"
            seeding.isNotEmpty() -> "Seeding ${seeding.size} · ↑ ${Fmt.speed(stats.upRate)}"
            streams > 0 -> "Streaming"
            queued > 0 -> "$queued queued"
            else -> "BetterTorrent"
        }
        val first = downloading.firstOrNull() ?: seeding.firstOrNull()
        val text = when {
            first != null -> "${first.name} — ${Fmt.percent(first.progress)}" +
                if (queued > 0) " · $queued queued" else ""
            list.isEmpty() -> "Ready"
            else -> "${list.size} torrent${if (list.size == 1) "" else "s"}"
        }

        val builder = NotificationCompat.Builder(this, Notifications.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(Notifications.openAppIntent(this))

        if (downloading.isNotEmpty()) {
            val wanted = downloading.sumOf { it.totalWanted }.coerceAtLeast(1)
            val done = downloading.sumOf { it.wantedDone }
            builder.setProgress(1000, ((done * 1000) / wanted).toInt(), false)
        }
        if (anyRunning) {
            builder.addAction(0, "Pause all", actionIntent(ACTION_PAUSE_ALL))
        } else if (anyPaused) {
            builder.addAction(0, "Resume all", actionIntent(ACTION_RESUME_ALL))
        }
        builder.addAction(0, "Exit", actionIntent(ACTION_EXIT))
        return builder.build()
    }

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, TorrentService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun enterForeground(notification: Notification): Boolean = try {
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BetterTorrent:transfer")
                .apply { setReferenceCounted(false) }
        }
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        // High-perf Wi‑Fi lock keeps the radio awake with the screen off (no-op from Android 14).
        if (wifiLock == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BetterTorrent:wifi")
                ?.apply { setReferenceCounted(false) }
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    private fun stopEverything() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TorrentService"
        private const val NOTIFICATION_ID = 1001
        private const val IDLE_STOP_MS = 20_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        const val ACTION_PAUSE_ALL = "com.bettertorrent.app.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.bettertorrent.app.RESUME_ALL"
        const val ACTION_EXIT = "com.bettertorrent.app.EXIT"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
            } catch (e: Exception) {
                // Background start not allowed right now; it will be retried from the foreground.
                Log.w(TAG, "could not start service", e)
            }
        }
    }
}
