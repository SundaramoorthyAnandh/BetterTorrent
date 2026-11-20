package com.bettertorrent.app.ui.transfers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SignalCellularOff
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bettertorrent.app.engine.EngineStats
import com.bettertorrent.app.engine.NetworkBlock
import com.bettertorrent.app.engine.TorrentItem
import com.bettertorrent.app.engine.TorrentState
import com.bettertorrent.app.ui.LocalAppActions
import com.bettertorrent.app.ui.MainViewModel
import com.bettertorrent.app.ui.components.EmptyState
import com.bettertorrent.app.ui.components.KindBadge
import com.bettertorrent.app.ui.components.SectionHeader
import com.bettertorrent.app.ui.components.StatusPill
import com.bettertorrent.app.ui.components.stateColor
import com.bettertorrent.app.util.FileKind
import com.bettertorrent.app.util.Fmt
import com.bettertorrent.app.util.Magnet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(vm: MainViewModel, onOpenTorrent: (String) -> Unit) {
    val torrents by vm.torrents.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val clipboardMagnet by vm.clipboardMagnet.collectAsStateWithLifecycle()
    val killAlert by vm.killAlert.collectAsStateWithLifecycle()
    val crashReport by vm.crashReport.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var removing by remember { mutableStateOf<TorrentItem?>(null) }
    val actions = LocalAppActions.current

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("BetterTorrent", fontWeight = FontWeight.Bold) },
                actions = { SpeedSummary(stats) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "add") {
                AddCard(
                    onSubmit = vm::addFromText,
                    onOpenFile = actions::pickTorrentFile,
                )
            }
            clipboardMagnet?.let { magnet ->
                item(key = "clipboard") {
                    ClipboardBanner(magnet = magnet, onAdd = vm::addClipboard, onDismiss = vm::dismissClipboard)
                }
            }
            crashReport?.let { report ->
                item(key = "crash") {
                    CrashBanner(
                        onShare = {
                            actions.shareText("BetterTorrent crash report", report)
                            vm.dismissCrashReport()
                        },
                        onDismiss = vm::dismissCrashReport,
                    )
                }
            }
            if (killAlert) {
                item(key = "killed") { KillBanner(brand = vm.oemGuide.brand, onFix = vm::fixKillAlert, onDismiss = vm::dismissKillAlert) }
            }
            if (stats.networkBlock != NetworkBlock.NONE) {
                item(key = "network") { NetworkBanner(stats.networkBlock) }
            }
            if (torrents.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.Link,
                        title = "No torrents yet",
                        body = "Paste a magnet link above or open a .torrent file. You can stream it right away or download it to your library.",
                    )
                }
            } else {
                item(key = "header") { SectionHeader("Transfers · ${torrents.size}") }
                items(torrents, key = { it.hash }) { t ->
                    TorrentCard(
                        item = t,
                        onClick = { onOpenTorrent(t.hash) },
                        onPauseResume = { if (t.state == TorrentState.PAUSED || t.state == TorrentState.COMPLETED || t.state == TorrentState.QUEUED) vm.resume(t.hash) else vm.pause(t.hash) },
                        onPlay = { actions.playTorrentFile(t.hash, t.bestMediaIndex) },
                        onRemove = { removing = t },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    removing?.let { t ->
        RemoveTorrentDialog(
            name = t.name,
            onConfirm = { deleteFiles ->
                vm.remove(t.hash, deleteFiles)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun SpeedSummary(stats: EngineStats) {
    if (stats.downRate == 0L && stats.upRate == 0L) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(end = 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Text(Fmt.speed(stats.downRate), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
            Text(Fmt.speed(stats.upRate), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AddCard(onSubmit: (String) -> Boolean, onOpenFile: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current

    fun submit() {
        if (text.isBlank()) return
        if (onSubmit(text)) {
            text = ""
            error = null
            focus.clearFocus()
        } else {
            error = "Paste a link that starts with magnet:? or a 40-character info-hash."
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Add a torrent", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stream instantly or save it to your library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Magnet link") },
                placeholder = { Text("magnet:?xt=urn:btih:…") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                trailingIcon = {
                    if (text.isEmpty()) {
                        IconButton(onClick = {
                            val pasted = clipboard.getText()?.text.orEmpty()
                            text = Magnet.extract(pasted) ?: pasted.trim()
                        }) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") }
                    } else {
                        IconButton(onClick = { text = ""; error = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::submit, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add magnet")
                }
                FilledTonalButton(onClick = onOpenFile, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open .torrent")
                }
            }
        }
    }
}

@Composable
private fun ClipboardBanner(magnet: String, onAdd: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ContentPaste, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Magnet link on your clipboard", style = MaterialTheme.typography.titleSmall)
                Text(
                    Magnet.displayName(magnet) ?: magnet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onAdd) { Text("Add it") }
        }
    }
}

@Composable
private fun CrashBanner(onShare: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("BetterTorrent crashed last time", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Share the crash report with the developer so it can be fixed. It only contains app and phone details, no files or links.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onShare) { Text("Share report") }
        }
    }
}

@Composable
private fun KillBanner(brand: String, onFix: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Your phone stopped BetterTorrent", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Your $brand phone closed the app in the background while it was downloading. Set it up once so downloads keep going.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            TextButton(onClick = onFix) { Text("Fix it") }
        }
    }
}

@Composable
private fun NetworkBanner(block: NetworkBlock) {
    val (title, body) = when (block) {
        NetworkBlock.MOBILE_OFF -> "Waiting for Wi‑Fi" to
            "Downloads on mobile data are off. Transfers resume on Wi‑Fi, or turn on “Download on mobile data” in Settings."
        else -> "Waiting for mobile data" to
            "Downloads on Wi‑Fi are off. Transfers resume on mobile data, or turn on “Download on Wi‑Fi” in Settings."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (block == NetworkBlock.MOBILE_OFF) Icons.Rounded.WifiOff else Icons.Rounded.SignalCellularOff, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TorrentCard(
    item: TorrentItem,
    onClick: () -> Unit,
    onPauseResume: () -> Unit,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = stateColor(item.state)
    val isRunning = item.state == TorrentState.DOWNLOADING || item.state == TorrentState.SEEDING ||
        item.state == TorrentState.FETCHING_METADATA || item.state == TorrentState.CHECKING
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                KindBadge(kind = if (item.bestMediaIndex >= 0) FileKind.VIDEO else null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(item.state)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${Fmt.bytes(item.wantedDone)} of ${Fmt.bytes(item.totalWanted)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(6.dp),
                color = color,
                trackColor = color.copy(alpha = 0.16f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        Fmt.percent(item.progress),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        detailLine(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == TorrentState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedVisibility(item.bestMediaIndex >= 0) {
                    FilledTonalButton(
                        onClick = onPlay,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                }
                IconButton(onClick = onPauseResume) {
                    Icon(
                        if (isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isRunning) "Pause" else "Resume",
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove")
                }
            }
        }
    }
}

private fun detailLine(t: TorrentItem): String = when (t.state) {
    TorrentState.DOWNLOADING -> buildString {
        append("↓ ").append(Fmt.speed(t.downRate))
        if (t.etaSeconds >= 0) append(" · ").append(Fmt.eta(t.etaSeconds)).append(" left")
        append(" · ").append(t.peers).append(" peers")
    }
    TorrentState.SEEDING -> "↑ ${Fmt.speed(t.upRate)} · ratio ${"%.2f".format(t.ratio)} · ${t.peers} peers"
    TorrentState.COMPLETED -> "Ready in Library · ${Fmt.bytes(t.totalWanted)}"
    TorrentState.PAUSED -> "Paused · tap ▶ to resume"
    TorrentState.QUEUED -> "Waiting for a free download slot"
    TorrentState.CHECKING -> "Checking existing data…"
    TorrentState.FETCHING_METADATA -> "Finding peers…"
    TorrentState.ERROR -> t.error ?: "Something went wrong"
}

@Composable
fun RemoveTorrentDialog(name: String, onConfirm: (deleteFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, null) },
        title = { Text("Remove “$name”?", maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("The torrent stops and leaves your transfers.")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text("Also delete downloaded files")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(deleteFiles) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (deleteFiles) "Remove and delete" else "Remove torrent") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } },
    )
}
