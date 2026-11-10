package com.bettertorrent.app.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bettertorrent.app.ui.LocalAppActions
import com.bettertorrent.app.ui.components.EmptyState
import com.bettertorrent.app.ui.components.KindBadge
import com.bettertorrent.app.util.Fmt
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onGoToTransfers: () -> Unit, vm: LibraryViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    val snackbar = remember { SnackbarHostState() }
    var sortOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LibEntry?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(2000)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(enabled = !ui.atRoot) { vm.up() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (!ui.atRoot) {
                        IconButton(onClick = { vm.up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up one folder") }
                    }
                },
                title = { Text(if (ui.atRoot) "Library" else ui.dir?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Sort") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                leadingIcon = { if (mode == ui.sort) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                                onClick = {
                                    vm.setSort(mode)
                                    sortOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (ui.atRoot) {
                item(key = "storage") { StorageCard(ui.storage) }
                if (ui.roots.size > 1) {
                    item(key = "roots") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            items(ui.roots, key = { it.absolutePath }) { root ->
                                FilterChip(
                                    selected = root == ui.root,
                                    onClick = { vm.selectRoot(root) },
                                    label = { Text(vm.rootLabel(root)) },
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "crumbs") { Breadcrumbs(root = ui.root!!, dir = ui.dir!!, rootLabel = vm.rootLabel(ui.root!!), onOpen = vm::openPath) }
            }

            if (ui.loaded && ui.entries.isEmpty()) {
                item(key = "empty") {
                    if (ui.atRoot) {
                        EmptyState(
                            icon = Icons.Rounded.FolderOpen,
                            title = "Your library is empty",
                            body = "Everything you download lands here. Stream it, open it, or save it to your phone's Downloads.",
                            actionLabel = "Add a torrent",
                            onAction = onGoToTransfers,
                        )
                    } else {
                        EmptyState(icon = Icons.Rounded.FolderOpen, title = "This folder is empty", body = "Files appear here as they download.")
                    }
                }
            }

            items(ui.entries, key = { it.file.absolutePath }) { entry ->
                LibraryRow(
                    entry = entry,
                    exportProgress = exporting[entry.file.absolutePath],
                    canExport = true,
                    onClick = {
                        when {
                            entry.isDir -> vm.open(entry.file)
                            entry.isPlayable -> actions.playLocalFile(entry.file)
                            entry.complete -> actions.openFileWith(entry.file)
                        }
                    },
                    onPlay = { actions.playLocalFile(entry.file) },
                    onPlayExternal = { actions.playLocalFile(entry.file, external = true) },
                    onOpenWith = { actions.openFileWith(entry.file) },
                    onExport = { vm.exportToDevice(entry) },
                    onShare = { actions.shareFile(entry.file) },
                    onDelete = { deleting = entry },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, null) },
            title = { Text("Delete “${entry.name}”?", maxLines = 3, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    if (entry.isDir) {
                        "This folder and everything in it (${Fmt.bytes(entry.size)}) will be deleted. Torrents that own these files are removed too."
                    } else {
                        "${Fmt.bytes(entry.size)} will be deleted. If it's part of a torrent, that file stops downloading."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(entry)
                        deleting = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun StorageCard(s: StorageInfo) {
    val total = s.totalBytes.coerceAtLeast(1)
    val other = (s.totalBytes - s.freeBytes - s.appBytes).coerceAtLeast(0)
    val appColor = MaterialTheme.colorScheme.primary
    val otherColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
    val freeColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.bytes(s.appBytes), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    "used by BetterTorrent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(freeColor),
            ) {
                val appF = s.appBytes.toFloat() / total
                val otherF = other.toFloat() / total
                if (appF > 0f) Box(Modifier.fillMaxHeight().weight(appF.coerceAtLeast(0.01f)).background(appColor))
                if (otherF > 0f) Box(Modifier.fillMaxHeight().weight(otherF).background(otherColor))
                val freeF = 1f - appF.coerceAtLeast(if (appF > 0f) 0.01f else 0f) - otherF
                if (freeF > 0f) Spacer(Modifier.weight(freeF))
            }
            Spacer(Modifier.height(10.dp))
            Row {
                Legend(appColor, "BetterTorrent", Modifier.weight(1f))
                Legend(otherColor, "Other", Modifier.weight(1f))
                Legend(freeColor, "${Fmt.bytes(s.freeBytes)} free", Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Breadcrumbs(root: File, dir: File, rootLabel: String, onOpen: (File) -> Unit) {
    val chain = generateSequence(dir) { if (it == root) null else it.parentFile }.toList().reversed()
    LazyRow(
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(chain, key = { _, f -> f.absolutePath }) { i, f ->
            if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            TextButton(onClick = { onOpen(f) }, enabled = i != chain.lastIndex) {
                Text(if (i == 0) rootLabel else f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun LibraryRow(
    entry: LibEntry,
    exportProgress: Float?,
    canExport: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onPlayExternal: () -> Unit,
    onOpenWith: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { KindBadge(kind = entry.kind) },
        headlineContent = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val meta = if (entry.isDir) {
                    "${entry.childCount} item${if (entry.childCount == 1) "" else "s"} · ${Fmt.bytes(entry.size)}"
                } else {
                    "${Fmt.bytes(entry.size)} · ${Fmt.date(entry.modified)}"
                }
                when {
                    exportProgress != null -> {
                        Text("Saving to Downloads · ${Fmt.percent(exportProgress)}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { exportProgress }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    }
                    entry.downloading != null -> {
                        Text("Downloading · ${Fmt.percent(entry.downloading)} · ${Fmt.bytes(entry.size)}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { entry.downloading }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                    }
                    else -> Text(meta)
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Actions for ${entry.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (entry.isPlayable) {
                        DropdownMenuItem(
                            text = { Text(if (entry.complete) "Play" else "Stream") },
                            leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                            onClick = { menu = false; onPlay() },
                        )
                        DropdownMenuItem(
                            text = { Text("Play in another app") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = { menu = false; onPlayExternal() },
                        )
                    } else if (!entry.isDir && entry.complete) {
                        DropdownMenuItem(
                            text = { Text("Open with…") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = { menu = false; onOpenWith() },
                        )
                    }
                    if (canExport && entry.complete) {
                        DropdownMenuItem(
                            text = { Text("Save to phone Downloads") },
                            leadingIcon = { Icon(Icons.Rounded.SaveAlt, null) },
                            onClick = { menu = false; onExport() },
                            enabled = exportProgress == null,
                        )
                    }
                    if (!entry.isDir && entry.complete) {
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; onShare() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        },
    )
}
