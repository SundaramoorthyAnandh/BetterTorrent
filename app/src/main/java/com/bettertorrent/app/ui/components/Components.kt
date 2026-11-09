package com.bettertorrent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bettertorrent.app.engine.TorrentState
import com.bettertorrent.app.util.FileKind

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.VIDEO -> Icons.Rounded.Movie
    FileKind.AUDIO -> Icons.Rounded.MusicNote
    FileKind.SUBTITLE -> Icons.Rounded.Subtitles
    FileKind.IMAGE -> Icons.Rounded.Image
    FileKind.ARCHIVE -> Icons.Rounded.Archive
    FileKind.DOCUMENT -> Icons.Rounded.Description
    FileKind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Tinted rounded square with an icon, used as a list leading element. */
@Composable
fun KindBadge(kind: FileKind?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val (bg, fg) = when (kind) {
        null -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        FileKind.VIDEO, FileKind.AUDIO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        FileKind.SUBTITLE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind?.icon() ?: Icons.Rounded.Folder,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun stateColor(state: TorrentState): Color = when (state) {
    TorrentState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    TorrentState.SEEDING, TorrentState.COMPLETED -> MaterialTheme.colorScheme.tertiary
    TorrentState.ERROR -> MaterialTheme.colorScheme.error
    TorrentState.PAUSED -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.secondary
}

@Composable
fun StatusPill(state: TorrentState, modifier: Modifier = Modifier) {
    val color = stateColor(state)
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(state.label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 32.dp, vertical = 40.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
