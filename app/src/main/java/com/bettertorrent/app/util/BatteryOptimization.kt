package com.bettertorrent.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption. Without it, Doze and phone-maker battery savers
 * (Samsung, Xiaomi, OnePlus, Oppo, Vivo…) can freeze the app when the screen is off.
 */
object BatteryOptimization {

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** System dialog: "Let app always run in background?" */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** System list of battery-optimized apps, to change it later. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
