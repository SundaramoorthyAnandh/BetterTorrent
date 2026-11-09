package com.bettertorrent.app.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettertorrent.app.engine.PlayRequest
import com.bettertorrent.app.engine.TorrentFileInfo
import com.bettertorrent.app.ui.AddState
import com.bettertorrent.app.ui.MainViewModel
import com.bettertorrent.app.ui.components.KindBadge
import com.bettertorrent.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTorrentSheet(state: AddState, vm: MainViewModel, onPlay: (PlayRequest) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = vm::dismissAdd,
        sheetState = sheetState,
    ) {
        when (state) {
            is AddState.Loading -> LoadingContent(state.message, onCancel = vm::dismissAdd)
            is AddState.Failed -> FailedContent(state.message, onRetry = vm::retryAdd, onClose = vm::dismissAdd)
            is AddState.Ready -> ReadyContent(
                state = state,
                onToggle = vm::toggleFile,
                onSelectAll = vm::selectAllFiles,
                onDownload = vm::confirmDownload,
                onStream = { index -> vm.confirmStream(index, onPlay) },
                canStream = vm.bestStreamIndex(state.preview, state.selected) != null,
            )
            AddState.Hidden -> Unit
        }
    }
}

@Composable
private fun LoadingContent(message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Magnet links can take up to a minute while peers are found.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text("Couldn't load this torrent", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onClose) { Text("Close") }
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AddState.Ready,
    onToggle: (Int) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onStream: (Int?) -> Unit,
    canStream: Boolean,
) {
    val preview = state.preview
    val selectedSize = preview.files.filter { it.index in state.selected }.sumOf { it.size }
    val allState = when (state.selected.size) {
        0 -> ToggleableState.Off
        preview.files.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text(
                preview.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${preview.files.size} file${if (preview.files.size == 1) "" else "s"} · ${Fmt.bytes(preview.totalSize)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.alreadyAdded) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Already in your transfers. Selected files will be added to it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectAll(allState != ToggleableState.On) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = allState, onClick = { onSelectAll(allState != ToggleableState.On) })
            Text("Select all", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                "${state.selected.size} selected · ${Fmt.bytes(selectedSize)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(preview.files, key = { it.index }) { f ->
                FileRow(
                    file = f,
                    checked = f.index in state.selected,
                    onToggle = { onToggle(f.index) },
                    onStream = if (f.isPlayable) ({ onStream(f.index) }) else null,
                    enabled = !state.busy,
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = { onStream(null) },
                enabled = canStream && !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Stream")
            }
            Button(
                onClick = onDownload,
                enabled = state.selected.isNotEmpty() && !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("Download")
            }
        }
    }
}

@Composable
private fun FileRow(
    file: TorrentFileInfo,
    checked: Boolean,
    onToggle: () -> Unit,
    onStream: (() -> Unit)?,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        KindBadge(kind = file.kind, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(file.folder.takeIf { it.isNotEmpty() }, Fmt.bytes(file.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onStream != null) {
            IconButton(onClick = onStream, enabled = enabled) {
                Icon(Icons.Rounded.PlayCircle, contentDescription = "Stream ${file.name}", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
