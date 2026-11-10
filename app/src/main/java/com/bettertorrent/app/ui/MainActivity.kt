package com.bettertorrent.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bettertorrent.app.AppEvents
import com.bettertorrent.app.data.PlayerChoice
import com.bettertorrent.app.engine.PlayRequest
import com.bettertorrent.app.service.Notifications
import com.bettertorrent.app.service.TorrentService
import com.bettertorrent.app.ui.player.PlayerActivity
import com.bettertorrent.app.ui.theme.BetterTorrentTheme
import com.bettertorrent.app.util.BatteryOptimization
import com.bettertorrent.app.util.FileKinds
import com.bettertorrent.app.util.OemBackground
import com.bettertorrent.app.util.OemStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity(), AppActions {

    private val vm: MainViewModel by viewModels()

    private val pickTorrent = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.addFromUri(uri)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}


    private var lastClipTimestamp = -1L

    private val batteryRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val exempt = BatteryOptimization.isExempt(this)
        lifecycleScope.launch {
            // First time on a phone that kills apps: continue with the brand's own settings.
            val guideOpened = vm.openGuideIfFirstTime()
            if (!exempt && !guideOpened && !vm.showGuide.value) {
                vm.message("Downloads may pause when the screen is off. Set it up in Settings → Keep downloading with screen off.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        askNotificationPermission()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                AppEvents.exit.collect { finishAndRemoveTask() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                vm.batteryPromptPending.collect { pending ->
                    if (pending) {
                        // Let a just-launched player or closing sheet settle; only ask while this screen has focus.
                        delay(800)
                        maybeShowBatteryPrompt()
                    }
                }
            }
        }

        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            BetterTorrentTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, font = settings.font) {
                CompositionLocalProvider(LocalAppActions provides this) {
                    AppRoot(vm)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Background starts can be refused; retry whenever the app comes to the foreground.
        if (vm.torrents.value.any { it.state.isActive }) TorrentService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            readClipboard()
            maybeShowBatteryPrompt()
        }
    }

    private fun maybeShowBatteryPrompt() {
        if (!vm.batteryPromptPending.value || !hasWindowFocus()) return
        vm.onBatteryPromptShown()
        if (BatteryOptimization.isExempt(this)) {
            lifecycleScope.launch { vm.openGuideIfFirstTime() }
        } else {
            launchBatteryRequest()
        }
    }

    private fun launchBatteryRequest() {
        try {
            batteryRequest.launch(BatteryOptimization.requestIntent(this))
        } catch (e: ActivityNotFoundException) {
            runCatching { batteryRequest.launch(BatteryOptimization.settingsIntent()) }
        }
    }

    override fun requestBatteryExemption() {
        if (BatteryOptimization.isExempt(this)) {
            runCatching { startActivity(BatteryOptimization.settingsIntent()) }
                .onFailure { vm.message("Open Settings → Apps → BetterTorrent → Battery to change this.") }
        } else {
            launchBatteryRequest()
        }
    }

    override fun openOemStep(step: OemStep) {
        if (!OemBackground.open(this, step)) {
            vm.message("Couldn't open that screen on this phone. Find it in Settings → Apps → BetterTorrent.")
        }
    }

    override fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { vm.message("No browser found to open $url") }
    }

    private fun readClipboard() {
        if (!vm.settings.value.clipboardDetect) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val desc = cm.primaryClipDescription ?: return
        // Only read a clip once, so Android's "pasted from clipboard" toast doesn't repeat.
        if (desc.timestamp == lastClipTimestamp) return
        lastClipTimestamp = desc.timestamp
        if (!desc.hasMimeType("text/*")) return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull()
        vm.onClipboardText(text)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Notifications.EXTRA_OPEN_HASH)?.let {
            vm.pendingDetail.value = it
            intent.removeExtra(Notifications.EXTRA_OPEN_HASH)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme.equals("magnet", ignoreCase = true)) {
                    if (!vm.addFromText(data.toString())) vm.message("That magnet link looks incomplete.")
                } else {
                    vm.addFromUri(data)
                }
            }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    vm.addFromUri(stream)
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (text == null || !vm.addFromText(text)) vm.message("No magnet link found in the shared text.")
                }
            }
        }
        intent.action = null
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------ AppActions

    override fun pickTorrentFile() {
        try {
            pickTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
        } catch (e: ActivityNotFoundException) {
            vm.message("No file picker app found on this device.")
        }
    }

    override fun play(request: PlayRequest, external: Boolean) {
        if (external || vm.settings.value.player == PlayerChoice.ASK) {
            openExternally(request)
        } else {
            startActivity(PlayerActivity.intent(this, request))
        }
    }

    override fun playTorrentFile(hash: String, index: Int, external: Boolean) {
        lifecycleScope.launch {
            val req = withContext(Dispatchers.IO) { vm.engine.prepareStream(hash, index) }
            if (req == null) vm.message("Couldn't open this file. It may have been removed.") else play(req, external)
        }
    }

    override fun playLocalFile(file: File, external: Boolean) {
        lifecycleScope.launch {
            val req = withContext(Dispatchers.IO) { vm.engine.playRequestForFile(file) }
            play(req, external)
        }
    }

    private fun openExternally(req: PlayRequest) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(req.url), req.mime)
            .putExtra("title", req.title)
        try {
            startActivity(Intent.createChooser(intent, "Play with"))
        } catch (e: ActivityNotFoundException) {
            vm.message("No other player found. Install VLC or MX Player, or use the built-in player.")
        }
    }

    private fun contentUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

    override fun openFileWith(file: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri(file), FileKinds.mimeOf(file.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            vm.message("No app on this phone can open ${file.name}.")
        }
    }

    override fun shareFile(file: File) {
        try {
            val intent = Intent(Intent.ACTION_SEND)
                .setType(FileKinds.mimeOf(file.name))
                .putExtra(Intent.EXTRA_STREAM, contentUri(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            vm.message("Couldn't share ${file.name}.")
        }
    }

    override fun copyText(label: String, text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) vm.message("Copied")
    }
}
