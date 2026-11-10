package com.bettertorrent.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

/** One thing the user should change on their phone, with settings screens to try in order. */
class OemStep(
    val title: String,
    val body: String,
    val intents: List<Intent> = emptyList(),
)

class OemGuide(
    /** Brand name shown to the user, e.g. "Vivo". */
    val brand: String,
    /** Brands known to stop background apps beyond stock Android. */
    val aggressive: Boolean,
    val steps: List<OemStep>,
    val helpUrl: String,
)

/**
 * Phone-maker battery managers (Vivo, Xiaomi, Oppo, Huawei, Samsung…) stop background apps
 * even with a foreground service. Their settings screens are private, so each step lists
 * several known screens to try, falling back to the app's info page.
 */
object OemBackground {

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun guide(context: Context): OemGuide {
        val maker = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.US)
        val details = appDetails(context)
        val lockStep = OemStep(
            "Lock it in recent apps",
            "Open recent apps, then long-press or swipe down on the BetterTorrent card and tap Lock. “Clear all” won't close it then.",
        )
        return when {
            "vivo" in maker || "iqoo" in maker -> OemGuide(
                brand = "Vivo",
                aggressive = true,
                steps = listOf(
                    OemStep(
                        "Allow high background power use",
                        "Settings → Battery → Background power consumption management → BetterTorrent → Allow.",
                        listOf(
                            component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                            details,
                        ),
                    ),
                    OemStep(
                        "Turn on Autostart",
                        "i Manager → App manager → Autostart manager → turn on BetterTorrent. Needed to resume after a restart.",
                        listOf(
                            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.SoftwareManagerActivity"),
                            details,
                        ),
                    ),
                    lockStep,
                ),
                helpUrl = "https://dontkillmyapp.com/vivo",
            )
            "xiaomi" in maker || "redmi" in maker || "poco" in maker -> OemGuide(
                brand = "Xiaomi",
                aggressive = true,
                steps = listOf(
                    OemStep(
                        "Turn on Autostart",
                        "Security → Permissions → Autostart → turn on BetterTorrent.",
                        listOf(
                            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                            details,
                        ),
                    ),
                    OemStep(
                        "Set battery saver to No restrictions",
                        "App info → Battery saver → No restrictions.",
                        listOf(
                            component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                                .putExtra("package_name", context.packageName)
                                .putExtra("package_label", "BetterTorrent"),
                            details,
                        ),
                    ),
                    lockStep,
                ),
                helpUrl = "https://dontkillmyapp.com/xiaomi",
            )
            "oppo" in maker || "realme" in maker || "oneplus" in maker -> OemGuide(
                brand = when {
                    "realme" in maker -> "Realme"
                    "oneplus" in maker -> "OnePlus"
                    else -> "Oppo"
                },
                aggressive = true,
                steps = listOf(
                    OemStep(
                        "Allow background activity",
                        "App info → Battery usage → turn on Allow background activity (and Allow auto launch if shown).",
                        listOf(details),
                    ),
                    OemStep(
                        "Allow auto launch",
                        "Settings → Apps → Auto launch (or Startup manager) → turn on BetterTorrent.",
                        listOf(
                            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                            details,
                        ),
                    ),
                    lockStep,
                ),
                helpUrl = when {
                    "realme" in maker -> "https://dontkillmyapp.com/realme"
                    "oneplus" in maker -> "https://dontkillmyapp.com/oneplus"
                    else -> "https://dontkillmyapp.com/oppo"
                },
            )
            "huawei" in maker || "honor" in maker -> OemGuide(
                brand = if ("honor" in maker) "Honor" else "Huawei",
                aggressive = true,
                steps = listOf(
                    OemStep(
                        "Manage app launch manually",
                        "Settings → Battery → App launch → BetterTorrent → Manage manually, then turn on Auto-launch, Secondary launch and Run in background.",
                        listOf(
                            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                            details,
                        ),
                    ),
                    lockStep,
                ),
                helpUrl = if ("honor" in maker) "https://dontkillmyapp.com/honor" else "https://dontkillmyapp.com/huawei",
            )
            "samsung" in maker -> OemGuide(
                brand = "Samsung",
                aggressive = true,
                steps = listOf(
                    OemStep(
                        "Never put it to sleep",
                        "Settings → Battery → Background usage limits → Never sleeping apps → add BetterTorrent. Make sure it isn't under Sleeping or Deep sleeping apps.",
                        listOf(
                            component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                            details,
                        ),
                    ),
                    OemStep("Set battery to Unrestricted", "App info → Battery → Unrestricted.", listOf(details)),
                ),
                helpUrl = "https://dontkillmyapp.com/samsung",
            )
            else -> {
                val autostart = when {
                    "asus" in maker -> listOf(
                        component("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"),
                        component("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"),
                    )
                    "tecno" in maker || "infinix" in maker || "itel" in maker -> listOf(
                        component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                    )
                    "nokia" in maker || "hmd" in maker -> listOf(
                        component("com.evenwell.powersaving.g3", "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity"),
                    )
                    else -> emptyList()
                }
                val aggressive = autostart.isNotEmpty() || "meizu" in maker
                val brand = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.US) }
                OemGuide(
                    brand = brand,
                    aggressive = aggressive,
                    steps = buildList {
                        add(OemStep("Set battery to Unrestricted", "App info → Battery → Unrestricted (or Allow background activity).", listOf(details)))
                        if (autostart.isNotEmpty()) {
                            add(OemStep("Allow auto start", "Turn on auto start / background running for BetterTorrent.", autostart + details))
                        }
                        if (aggressive) add(lockStep)
                    },
                    helpUrl = "https://dontkillmyapp.com/" + Build.MANUFACTURER.lowercase(Locale.US).replace(" ", "-"),
                )
            }
        }
    }

    /** Opens the first settings screen that exists on this phone. Returns false if none did. */
    fun open(context: Context, step: OemStep): Boolean {
        for (intent in step.intents) {
            val ok = runCatching {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        return false
    }
}
