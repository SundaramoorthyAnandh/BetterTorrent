package com.bettertorrent.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bettertorrent.app.R
import com.bettertorrent.app.ui.MainActivity

object Notifications {
    const val CHANNEL_TRANSFERS = "transfers"
    const val CHANNEL_COMPLETED = "completed"
    const val EXTRA_OPEN_HASH = "open_hash"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, "Active transfers", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing download, seeding and streaming progress"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPLETED, "Completed downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alerts when a torrent finishes downloading"
            },
        )
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context, hash: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (hash != null) putExtra(EXTRA_OPEN_HASH, hash)
        }
        return PendingIntent.getActivity(
            context,
            hash?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun showCompleted(context: Context, hash: String, name: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_COMPLETED)
            .setSmallIcon(R.drawable.ic_stat_done)
            .setContentTitle("Download complete")
            .setContentText(name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(name))
            .setContentIntent(openAppIntent(context, hash))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(hash.hashCode(), n)
        } catch (_: SecurityException) {
        }
    }
}
