package com.bettertorrent.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class DownloadLocation { APP, PUBLIC }
enum class PlayerChoice { BUILT_IN, ASK }

/** App-wide typeface. SYSTEM follows the phone's own font. All others are bundled, SIL OFL 1.1. */
enum class AppFont(val label: String, val serif: Boolean) {
    SYSTEM("System default", false),
    INTER("Inter", false),
    POPPINS("Poppins", false),
    NUNITO("Nunito", false),
    MANROPE("Manrope", false),
    LORA("Lora", true),
    MERRIWEATHER("Merriweather", true),
    SOURCE_SERIF("Source Serif 4", true),
    LITERATA("Literata", true),
}

data class AppSettings(
    val downloadLimitKb: Int = 0,
    val uploadLimitKb: Int = 0,
    /** Allowed networks. At least one stays on (enforced in the UI). Ethernet counts as Wi‑Fi. */
    val downloadOnWifi: Boolean = true,
    val downloadOnMobile: Boolean = true,
    val maxActiveDownloads: Int = 3,
    val location: DownloadLocation = DownloadLocation.APP,
    val seedAfterComplete: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val font: AppFont = AppFont.SYSTEM,
    val clipboardDetect: Boolean = true,
    val player: PlayerChoice = PlayerChoice.BUILT_IN,
    /** The battery-optimization exemption dialog has been shown once. */
    val batteryPromptShown: Boolean = false,
    /** The phone-maker background setup guide has been shown once. */
    val oemGuideShown: Boolean = false,
    /** Start unfinished transfers again after the phone reboots. */
    val resumeAfterReboot: Boolean = true,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val downloadLimit = intPreferencesKey("download_limit_kb")
        val uploadLimit = intPreferencesKey("upload_limit_kb")
        val legacyWifiOnly = booleanPreferencesKey("wifi_only")
        val onWifi = booleanPreferencesKey("download_on_wifi")
        val onMobile = booleanPreferencesKey("download_on_mobile")
        val batteryPrompt = booleanPreferencesKey("battery_prompt_shown")
        val oemGuide = booleanPreferencesKey("oem_guide_shown")
        val resumeAfterReboot = booleanPreferencesKey("resume_after_reboot")
        val maxActive = intPreferencesKey("max_active_downloads")
        val location = stringPreferencesKey("download_location")
        val seed = booleanPreferencesKey("seed_after_complete")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val font = stringPreferencesKey("font")
        val clipboard = booleanPreferencesKey("clipboard_detect")
        val player = stringPreferencesKey("player")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map(::read)
        .distinctUntilChanged()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            downloadLimitKb = p[Keys.downloadLimit] ?: d.downloadLimitKb,
            uploadLimitKb = p[Keys.uploadLimit] ?: d.uploadLimitKb,
            downloadOnWifi = p[Keys.onWifi] ?: d.downloadOnWifi,
            // Migrate the old "Download only on Wi‑Fi" switch.
            downloadOnMobile = p[Keys.onMobile] ?: p[Keys.legacyWifiOnly]?.not() ?: d.downloadOnMobile,
            maxActiveDownloads = p[Keys.maxActive] ?: d.maxActiveDownloads,
            location = enumOr(p[Keys.location], d.location),
            seedAfterComplete = p[Keys.seed] ?: d.seedAfterComplete,
            themeMode = enumOr(p[Keys.theme], d.themeMode),
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            font = enumOr(p[Keys.font], d.font),
            clipboardDetect = p[Keys.clipboard] ?: d.clipboardDetect,
            player = enumOr(p[Keys.player], d.player),
            batteryPromptShown = p[Keys.batteryPrompt] ?: d.batteryPromptShown,
            oemGuideShown = p[Keys.oemGuide] ?: d.oemGuideShown,
            resumeAfterReboot = p[Keys.resumeAfterReboot] ?: d.resumeAfterReboot,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.downloadLimit] = s.downloadLimitKb
        p[Keys.uploadLimit] = s.uploadLimitKb
        p.remove(Keys.legacyWifiOnly)
        p[Keys.onWifi] = s.downloadOnWifi
        p[Keys.onMobile] = s.downloadOnMobile
        p[Keys.batteryPrompt] = s.batteryPromptShown
        p[Keys.oemGuide] = s.oemGuideShown
        p[Keys.resumeAfterReboot] = s.resumeAfterReboot
        p[Keys.maxActive] = s.maxActiveDownloads
        p[Keys.location] = s.location.name
        p[Keys.seed] = s.seedAfterComplete
        p[Keys.theme] = s.themeMode.name
        p[Keys.dynamic] = s.dynamicColor
        p[Keys.font] = s.font.name
        p[Keys.clipboard] = s.clipboardDetect
        p[Keys.player] = s.player.name
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}
