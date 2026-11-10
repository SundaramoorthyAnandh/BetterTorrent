@file:OptIn(UnstableApi::class)

package com.bettertorrent.app.ui.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bettertorrent.app.AppEvents
import com.bettertorrent.app.BetterTorrentApp
import com.bettertorrent.app.data.AppFont
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.map
import com.bettertorrent.app.engine.PlayRequest
import com.bettertorrent.app.engine.TorrentFileInfo
import com.bettertorrent.app.ui.theme.PlayerTheme
import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.FileKinds
import com.bettertorrent.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SubtitleChoice(val uri: Uri, val mime: String, val label: String)

class PlayerActivity : ComponentActivity() {

    private val engine by lazy { (application as BetterTorrentApp).engine }
    private lateinit var request: PlayRequest
    private val playerState = mutableStateOf<ExoPlayer?>(null)
    private val subtitleState = mutableStateOf<SubtitleChoice?>(null)
    private val subtitlesOff = mutableStateOf(false)
    private var resumePositionMs = 0L
    private var playWhenReady = true

    /** Keeps the screen on while the video plays or buffers; normal timeout when paused. */
    private val screenOnListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateKeepScreenOn(player)
    }

    private fun updateKeepScreenOn(player: Player?) {
        val active = player != null && player.playWhenReady &&
            (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = displayName(uri) ?: "Subtitle"
        val mime = FileKinds.subtitleMime(name) ?: "application/x-subrip"
        applySubtitle(SubtitleChoice(uri, mime, name))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = PlayRequest(
            url = intent.getStringExtra(EXTRA_URL).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            mime = intent.getStringExtra(EXTRA_MIME) ?: "video/*",
            hash = intent.getStringExtra(EXTRA_HASH),
            fileIndex = intent.getIntExtra(EXTRA_INDEX, -1),
        )
        if (request.url.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch { AppEvents.exit.collect { finish() } }
        resumePositionMs = savedInstanceState?.getLong(STATE_POSITION) ?: 0L
        playWhenReady = savedInstanceState?.getBoolean(STATE_PLAYING) ?: true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            val font by (application as BetterTorrentApp).settings.settings
                .map { it.font }
                .collectAsState(initial = AppFont.SYSTEM)
            PlayerTheme(font = font) {
                PlayerScreen(
                    player = playerState,
                    request = request,
                    subtitleActive = subtitleState.value != null && !subtitlesOff.value,
                    statsProvider = ::statsLine,
                    subtitleFiles = { request.hash?.let { h -> engine.fileList(h).filter { FileKinds.subtitleMime(it.name) != null } }.orEmpty() },
                    onBack = { finish() },
                    onOpenExternal = ::openExternally,
                    onPickTorrentSubtitle = ::useTorrentSubtitle,
                    onPickDeviceSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
                    onSubtitlesOff = ::disableSubtitles,
                    onRetry = ::retry,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        playerState.value?.let {
            resumePositionMs = it.currentPosition
            playWhenReady = it.playWhenReady
        }
        outState.putLong(STATE_POSITION, resumePositionMs)
        outState.putBoolean(STATE_PLAYING, playWhenReady)
    }

    private fun initPlayer() {
        if (playerState.value != null) return
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            // Reads block while pieces download, so allow long gaps.
            .setReadTimeoutMs(120_000)
            .setAllowCrossProtocolRedirects(true)
        val dataSource = DefaultDataSource.Factory(this, http)
        val errorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 30
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 120_000, 2_000, 4_000)
            .build()
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(errorPolicy))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(screenOnListener)
        player.setMediaItem(buildMediaItem(), resumePositionMs)
        applyTextTrackState(player)
        player.playWhenReady = playWhenReady
        player.prepare()
        playerState.value = player
        updateKeepScreenOn(player)
    }

    private fun releasePlayer() {
        playerState.value?.let {
            resumePositionMs = it.currentPosition
            playWhenReady = it.playWhenReady
            it.removeListener(screenOnListener)
            it.release()
        }
        playerState.value = null
        updateKeepScreenOn(null)
    }

    private fun buildMediaItem(): MediaItem {
        val sub = subtitleState.value
        return MediaItem.Builder()
            .setUri(request.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .setSubtitleConfigurations(
                if (sub == null) {
                    emptyList()
                } else {
                    listOf(
                        MediaItem.SubtitleConfiguration.Builder(sub.uri)
                            .setMimeType(sub.mime)
                            .setLabel(sub.label)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                            .build(),
                    )
                },
            )
            .build()
    }

    private fun applyTextTrackState(player: ExoPlayer) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesOff.value)
            .build()
    }

    private fun applySubtitle(choice: SubtitleChoice) {
        subtitleState.value = choice
        subtitlesOff.value = false
        val player = playerState.value ?: return
        val pos = player.currentPosition
        player.setMediaItem(buildMediaItem(), pos)
        applyTextTrackState(player)
        player.prepare()
        player.play()
    }

    private fun useTorrentSubtitle(file: TorrentFileInfo) {
        val hash = request.hash ?: return
        lifecycleScope.launch {
            val req = withContext(Dispatchers.IO) { engine.prepareStream(hash, file.index) } ?: return@launch
            applySubtitle(SubtitleChoice(Uri.parse(req.url), FileKinds.subtitleMime(file.name) ?: "application/x-subrip", file.name))
        }
    }

    private fun disableSubtitles() {
        subtitlesOff.value = true
        playerState.value?.let { applyTextTrackState(it) }
    }

    private fun retry() {
        val player = playerState.value ?: return
        val pos = player.currentPosition
        player.setMediaItem(buildMediaItem(), pos)
        player.prepare()
        player.play()
    }

    private fun openExternally() {
        playerState.value?.pause()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(request.url), request.mime)
            .putExtra("title", request.title)
            .putExtra("position", playerState.value?.currentPosition?.toInt() ?: 0)
        try {
            startActivity(Intent.createChooser(intent, "Play with"))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** One-line torrent status for the overlay, or null for local files. */
    private suspend fun statsLine(): String? {
        val hash = request.hash ?: return null
        val item = engine.torrents.value.firstOrNull { it.hash == hash } ?: return null
        val file = withContext(Dispatchers.IO) { engine.files(hash) }.firstOrNull { it.info.index == request.fileIndex }
        return buildString {
            append("↓ ").append(Fmt.speed(item.downRate))
            append(" · ").append(item.peers).append(" peers")
            if (file != null) append(" · ").append(Fmt.percent(file.progress)).append(" downloaded")
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MIME = "mime"
        private const val EXTRA_HASH = "hash"
        private const val EXTRA_INDEX = "index"
        private const val STATE_POSITION = "position"
        private const val STATE_PLAYING = "playing"

        fun intent(context: Context, req: PlayRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, req.url)
                .putExtra(EXTRA_TITLE, req.title)
                .putExtra(EXTRA_MIME, req.mime)
                .putExtra(EXTRA_HASH, req.hash)
                .putExtra(EXTRA_INDEX, req.fileIndex)
    }
}

@Composable
private fun PlayerScreen(
    player: MutableState<ExoPlayer?>,
    request: PlayRequest,
    subtitleActive: Boolean,
    statsProvider: suspend () -> String?,
    subtitleFiles: () -> List<TorrentFileInfo>,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onPickTorrentSubtitle: (TorrentFileInfo) -> Unit,
    onPickDeviceSubtitle: () -> Unit,
    onSubtitlesOff: () -> Unit,
    onRetry: () -> Unit,
) {
    val exo = player.value
    var controlsVisible by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var audioUnsupported by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<String?>(null) }
    var subtitleDialog by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    LaunchedEffect(Unit) {
        while (true) {
            stats = runCatching { statsProvider() }.getOrNull()
            delay(1000)
        }
    }

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error = null
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    -> "The built-in player can't play this format. Try VLC or MX Player."
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    -> "The stream stalled. The torrent may have too few peers right now."
                    else -> "Playback stopped (${e.errorCodeName}). Retry, or open it in another player."
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                audioUnsupported = audio.isNotEmpty() && audio.none { it.isSupported }
            }
        }
        exo?.addListener(listener)
        onDispose { exo?.removeListener(listener) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerShowTimeoutMs = 3500
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowSubtitleButton(true)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { v -> controlsVisible = v == View.VISIBLE },
                    )
                }
            },
            update = { view ->
                if (view.player !== exo) view.player = exo
                view.resizeMode = resizeMode
            },
        )

        AnimatedVisibility(
            visible = controlsVisible || error != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close player", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(request.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    stats?.let { Text(it, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
                IconButton(onClick = {
                    resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }) { Icon(Icons.Rounded.AspectRatio, contentDescription = "Change aspect ratio", tint = Color.White) }
                IconButton(onClick = { subtitleDialog = true }) {
                    Icon(
                        if (subtitleActive) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled,
                        contentDescription = "Subtitles",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onOpenExternal) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in another player", tint = Color.White)
                }
            }
        }

        if (buffering && error == null && stats != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp),
                color = Color.Black.copy(alpha = 0.6f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Buffering · $stats", modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (audioUnsupported && error == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clickable(onClick = onOpenExternal),
                color = Color.Black.copy(alpha = 0.75f),
                contentColor = Color.White,
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("This audio format isn't supported here. Tap to open in another player.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        error?.let { msg ->
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Can't play this right now", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            error = null
                            onRetry()
                        }) { Text("Retry") }
                        Button(onClick = onOpenExternal) { Text("Open in another player") }
                    }
                }
            }
        }
    }

    if (subtitleDialog) {
        val files = remember { subtitleFiles() }
        AlertDialog(
            onDismissRequest = { subtitleDialog = false },
            icon = { Icon(Icons.Rounded.Subtitles, null) },
            title = { Text("Subtitles") },
            text = {
                LazyColumn {
                    if (files.isNotEmpty()) {
                        item {
                            Text("From this torrent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        items(files, key = { it.index }) { f ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    subtitleDialog = false
                                    onPickTorrentSubtitle(f)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = { Icon(Icons.Rounded.Subtitles, null) },
                                headlineContent = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    item {
                        ListItem(
                            modifier = Modifier.clickable {
                                subtitleDialog = false
                                onPickDeviceSubtitle()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Rounded.FileOpen, null) },
                            headlineContent = { Text("Load from phone…") },
                            supportingContent = { Text(".srt, .vtt, .ass or .ssa") },
                        )
                    }
                    item {
                        ListItem(
                            modifier = Modifier.clickable {
                                subtitleDialog = false
                                onSubtitlesOff()
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(Icons.Rounded.ClosedCaptionDisabled, null) },
                            headlineContent = { Text("Turn off subtitles") },
                        )
                    }
                    item {
                        Text(
                            "Subtitles built into the video are in the player's CC button.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { subtitleDialog = false }) { Text("Close") } },
        )
    }
}
