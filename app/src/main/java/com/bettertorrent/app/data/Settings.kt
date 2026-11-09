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

data class AppSettings(
    val downloadLimitKb: Int = 0,
    val uploadLimitKb: Int = 0,
    val wifiOnly: Boolean = false,
    val maxActiveDownloads: Int = 3,
    val location: DownloadLocation = DownloadLocation.APP,
    val seedAfterComplete: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val clipboardDetect: Boolean = true,
    val player: PlayerChoice = PlayerChoice.BUILT_IN,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val downloadLimit = intPreferencesKey("download_limit_kb")
        val uploadLimit = intPreferencesKey("upload_limit_kb")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val maxActive = intPreferencesKey("max_active_downloads")
        val location = stringPreferencesKey("download_location")
        val seed = booleanPreferencesKey("seed_after_complete")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
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
            wifiOnly = p[Keys.wifiOnly] ?: d.wifiOnly,
            maxActiveDownloads = p[Keys.maxActive] ?: d.maxActiveDownloads,
            location = enumOr(p[Keys.location], d.location),
            seedAfterComplete = p[Keys.seed] ?: d.seedAfterComplete,
            themeMode = enumOr(p[Keys.theme], d.themeMode),
            dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
            clipboardDetect = p[Keys.clipboard] ?: d.clipboardDetect,
            player = enumOr(p[Keys.player], d.player),
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.downloadLimit] = s.downloadLimitKb
        p[Keys.uploadLimit] = s.uploadLimitKb
        p[Keys.wifiOnly] = s.wifiOnly
        p[Keys.maxActive] = s.maxActiveDownloads
        p[Keys.location] = s.location.name
        p[Keys.seed] = s.seedAfterComplete
        p[Keys.theme] = s.themeMode.name
        p[Keys.dynamic] = s.dynamicColor
        p[Keys.clipboard] = s.clipboardDetect
        p[Keys.player] = s.player.name
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}
