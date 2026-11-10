package com.bettertorrent.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.bettertorrent.app.engine.PlayRequest
import java.io.File

/** Things only the Activity can do (pickers, intents, other apps). */
interface AppActions {
    fun pickTorrentFile()
    fun play(request: PlayRequest, external: Boolean = false)
    fun playTorrentFile(hash: String, index: Int, external: Boolean = false)
    fun playLocalFile(file: File, external: Boolean = false)
    fun openFileWith(file: File)
    fun shareFile(file: File)
    fun requestAllFilesAccess(onResult: (Boolean) -> Unit)
    fun copyText(label: String, text: String)
    /** Asks for (or opens settings to review) the battery-optimization exemption. */
    fun requestBatteryExemption()
}

val LocalAppActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
