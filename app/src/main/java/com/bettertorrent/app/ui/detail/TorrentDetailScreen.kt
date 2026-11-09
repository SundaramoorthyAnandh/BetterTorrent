package com.bettertorrent.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bettertorrent.app.engine.TorrentFileState
import com.bettertorrent.app.engine.TorrentItem
import com.bettertorrent.app.engine.TorrentState
import com.bettertorrent.app.ui.LocalAppActions
import com.bettertorrent.app.ui.MainViewModel
import com.bettertorrent.app.ui.components.EmptyState
import com.bettertorrent.app.ui.components.KindBadge
import com.bettertorrent.app.ui.components.SectionHeader
import com.bettertorrent.app.ui.components.StatusPill
import com.bettertorrent.app.ui.components.stateColor
import com.bettertorrent.app.ui.transfers.RemoveTorrentDialog
import com.bettertorrent.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentDetailScreen(vm: MainViewModel, hash: String, onBack: () -> Unit, onOpenLibrary: () -> Unit) {
    val torrents by vm.torrents.collectAsStateWithLifecycle()
    val item = torrents.firstOrNull { it.hash == hash }
    var files by remember { mutableStateOf<List<TorrentFileState>>(emptyList()) }
    var menuOpen by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val actions = LocalAppActions.current

    LaunchedEffect(hash) {
        while (true) {
            files = withContext(Dispatchers.IO) { vm.engine.files(hash) }
            loaded = true
            delay(1000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = { Text(item?.name ?: "Torrent", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (item != null) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copy magnet link") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = {
                                    menuOpen = false
                                    vm.engine.magnetUri(hash)?.let { actions.copyText("Magnet link", it) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Show in Library") },
                                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                                onClick = {
                                    menuOpen = false
                                    onOpenLibrary()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                onClick = {
                                    menuOpen = false
                                    removing = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (item == null) {
            if (loaded) {
                EmptyState(
                    icon = Icons.Rounded.SearchOff,
                    title = "Torrent not found",
                    body = "It may have been removed.",
                    actionLabel = "Back to transfers",
                    onAction = onBack,
                    modifier = Modifier.padding(padding),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "header") {
                HeaderCard(
                    item = item,
                    onPauseResume = {
                        if (item.state == TorrentState.PAUSED || item.state == TorrentState.COMPLETED || item.state == TorrentState.QUEUED) {
                            vm.resume(hash)
                        } else {
                            vm.pause(hash)
                        }
                    },
                    onPlay = { actions.playTorrentFile(hash, item.bestMediaIndex) },
                    onSequential = { vm.setSequential(hash, it) },
                )
            }
            item(key = "files-header") {
                val allWanted = files.isNotEmpty() && files.all { it.wanted }
                SectionHeader("Files · ${files.size}") {
                    if (files.size > 1) {
                        TextButton(onClick = { vm.setAllFilesWanted(hash, !allWanted) }) {
                            Text(if (allWanted) "Skip all" else "Download all")
                        }
                    }
                }
            }
            items(files, key = { it.info.index }) { f ->
                FileStateRow(
                    file = f,
                    onToggle = { vm.setFileWanted(hash, f.info.index, !f.wanted) },
                    onPlay = { actions.playTorrentFile(hash, f.info.index) },
                    onExternal = { actions.playTorrentFile(hash, f.info.index, external = true) },
                )
            }
            item(key = "path") {
                Text(
                    "Saved to ${item.savePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .navigationBarsPadding(),
                )
            }
        }
    }

    if (removing && item != null) {
        RemoveTorrentDialog(
            name = item.name,
            onConfirm = { deleteFiles ->
                removing = false
                vm.remove(hash, deleteFiles)
                onBack()
            },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun HeaderCard(
    item: TorrentItem,
    onPauseResume: () -> Unit,
    onPlay: () -> Unit,
    onSequential: (Boolean) -> Unit,
) {
    val color = stateColor(item.state)
    val running = item.state == TorrentState.DOWNLOADING || item.state == TorrentState.SEEDING ||
        item.state == TorrentState.CHECKING || item.state == TorrentState.FETCHING_METADATA
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.percent(item.progress), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.width(12.dp))
                StatusPill(item.state)
            }
            Text(
                "${Fmt.bytes(item.wantedDone)} of ${Fmt.bytes(item.totalWanted)}" +
                    if (item.totalWanted < item.totalSize) " (torrent ${Fmt.bytes(item.totalSize)})" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = color,
                trackColor = color.copy(alpha = 0.16f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            item.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Download", Fmt.speed(item.downRate), Modifier.weight(1f))
                Stat("Upload", Fmt.speed(item.upRate), Modifier.weight(1f))
                Stat("Time left", if (item.state == TorrentState.DOWNLOADING) Fmt.eta(item.etaSeconds) else "—", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("Peers", "${item.peers} (${item.seeds} seeds)", Modifier.weight(1f))
                Stat("Ratio", String.format(Locale.US, "%.2f", item.ratio), Modifier.weight(1f))
                Stat("Added", Fmt.date(item.addedAt), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSequential(!item.sequential) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Sequential download", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Download in order — best for streaming",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = item.sequential, onCheckedChange = onSequential)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onPauseResume, modifier = Modifier.weight(1f)) {
                    Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "Pause" else "Resume")
                }
                if (item.bestMediaIndex >= 0) {
                    Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play")
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FileStateRow(
    file: TorrentFileState,
    onToggle: () -> Unit,
    onPlay: () -> Unit,
    onExternal: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = file.wanted, onCheckedChange = { onToggle() })
        KindBadge(kind = file.info.kind, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.info.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (file.wanted) {
                LinearProgressIndicator(
                    progress = { file.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                if (file.wanted) "${Fmt.percent(file.progress)} · ${Fmt.bytes(file.info.size)}" else "Skipped · ${Fmt.bytes(file.info.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.info.isPlayable) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play ${file.info.name}", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onExternal) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open ${file.info.name} in another player")
            }
        }
    }
}
