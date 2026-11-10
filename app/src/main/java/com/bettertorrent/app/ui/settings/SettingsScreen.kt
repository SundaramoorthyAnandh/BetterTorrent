package com.bettertorrent.app.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bettertorrent.app.util.BatteryOptimization
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bettertorrent.app.BuildConfig
import com.bettertorrent.app.data.DownloadLocation
import com.bettertorrent.app.data.PlayerChoice
import com.bettertorrent.app.data.ThemeMode
import com.bettertorrent.app.ui.LocalAppActions
import com.bettertorrent.app.ui.MainViewModel
import com.bettertorrent.app.ui.components.SectionHeader
import org.libtorrent4j.LibTorrent

private enum class LimitKind { DOWNLOAD, UPLOAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val actions = LocalAppActions.current
    var locationDialog by remember { mutableStateOf(false) }
    var limitDialog by remember { mutableStateOf<LimitKind?>(null) }
    val context = LocalContext.current
    var batteryExempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryExempt = BatteryOptimization.isExempt(context) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("Downloads") }
            item {
                SettingRow(
                    icon = Icons.Rounded.Folder,
                    title = "Download location",
                    subtitle = if (s.location == DownloadLocation.PUBLIC) "Downloads/BetterTorrent (visible to other apps)" else "App storage (private)",
                    onClick = { locationDialog = true },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.Layers,
                    title = "Max active downloads",
                    subtitle = "Extra torrents wait in the queue",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { vm.updateSettings { it.copy(maxActiveDownloads = (it.maxActiveDownloads - 1).coerceAtLeast(1)) } },
                                enabled = s.maxActiveDownloads > 1,
                            ) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer") }
                            Text("${s.maxActiveDownloads}", style = MaterialTheme.typography.titleMedium)
                            IconButton(
                                onClick = { vm.updateSettings { it.copy(maxActiveDownloads = (it.maxActiveDownloads + 1).coerceAtMost(10)) } },
                                enabled = s.maxActiveDownloads < 10,
                            ) { Icon(Icons.Rounded.Add, contentDescription = "More") }
                        }
                    },
                )
            }
            item {
                SwitchRow(
                    icon = Icons.Rounded.Upload,
                    title = "Keep seeding after download",
                    subtitle = "Share with other peers when finished. Uses data and battery.",
                    checked = s.seedAfterComplete,
                    onChange = { v -> vm.updateSettings { it.copy(seedAfterComplete = v) } },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = "Keep downloading with screen off",
                    subtitle = if (batteryExempt) {
                        "Battery optimization is off. Tap for ${vm.oemGuide.brand} phone settings."
                    } else {
                        "Restricted by battery optimization. Downloads may pause when the screen is off. Tap to set up."
                    },
                    onClick = vm::openGuide,
                    trailing = {
                        Icon(
                            if (batteryExempt) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = if (batteryExempt) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }

            item {
                SwitchRow(
                    icon = Icons.Rounded.RestartAlt,
                    title = "Resume after phone restart",
                    subtitle = "Start unfinished downloads again when the phone turns on",
                    checked = s.resumeAfterReboot,
                    onChange = { v -> vm.updateSettings { it.copy(resumeAfterReboot = v) } },
                )
            }

            item { SectionHeader("Network") }
            item {
                SwitchRow(
                    icon = Icons.Rounded.Wifi,
                    title = "Download on Wi‑Fi",
                    subtitle = "Includes Ethernet",
                    checked = s.downloadOnWifi,
                    onChange = { v ->
                        if (!v && !s.downloadOnMobile) {
                            vm.message("Keep at least one network on. To stop everything, pause your transfers instead.")
                        } else {
                            vm.updateSettings { it.copy(downloadOnWifi = v) }
                        }
                    },
                )
            }
            item {
                SwitchRow(
                    icon = Icons.Rounded.SignalCellularAlt,
                    title = "Download on mobile data",
                    subtitle = "Uses your data plan",
                    checked = s.downloadOnMobile,
                    onChange = { v ->
                        if (!v && !s.downloadOnWifi) {
                            vm.message("Keep at least one network on. To stop everything, pause your transfers instead.")
                        } else {
                            vm.updateSettings { it.copy(downloadOnMobile = v) }
                        }
                    },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.ArrowDownward,
                    title = "Download speed limit",
                    subtitle = limitLabel(s.downloadLimitKb),
                    onClick = { limitDialog = LimitKind.DOWNLOAD },
                )
            }
            item {
                SettingRow(
                    icon = Icons.Rounded.ArrowUpward,
                    title = "Upload speed limit",
                    subtitle = limitLabel(s.uploadLimitKb),
                    onClick = { limitDialog = LimitKind.UPLOAD },
                )
            }

            item { SectionHeader("Playback") }
            item {
                ChoiceRow(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Default player",
                    options = listOf("Built-in", "Ask every time"),
                    selected = if (s.player == PlayerChoice.BUILT_IN) 0 else 1,
                    onSelect = { i -> vm.updateSettings { it.copy(player = if (i == 0) PlayerChoice.BUILT_IN else PlayerChoice.ASK) } },
                    footnote = "“Ask every time” lets you pick VLC, MX Player or a cast app.",
                )
            }

            item { SectionHeader("Appearance") }
            item {
                ChoiceRow(
                    icon = Icons.Rounded.Contrast,
                    title = "Theme",
                    options = listOf("System", "Light", "Dark"),
                    selected = s.themeMode.ordinal,
                    onSelect = { i -> vm.updateSettings { it.copy(themeMode = ThemeMode.entries[i]) } },
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    SwitchRow(
                        icon = Icons.Rounded.Palette,
                        title = "Material You colors",
                        subtitle = "Match your wallpaper",
                        checked = s.dynamicColor,
                        onChange = { v -> vm.updateSettings { it.copy(dynamicColor = v) } },
                    )
                }
            }

            item { SectionHeader("General") }
            item {
                SwitchRow(
                    icon = Icons.Rounded.ContentPaste,
                    title = "Detect magnet links on clipboard",
                    subtitle = "Offer to add a link you just copied",
                    checked = s.clipboardDetect,
                    onChange = { v -> vm.updateSettings { it.copy(clipboardDetect = v) } },
                )
            }

            item { SectionHeader("About") }
            item {
                SettingRow(
                    icon = Icons.Rounded.Info,
                    title = "BetterTorrent ${BuildConfig.VERSION_NAME}",
                    subtitle = "Engine: libtorrent ${runCatching { LibTorrent.version() }.getOrDefault("?")}",
                )
            }
        }
    }

    if (locationDialog) {
        LocationDialog(
            current = s.location,
            onSelect = { loc ->
                locationDialog = false
                if (loc == DownloadLocation.PUBLIC) {
                    actions.requestAllFilesAccess { granted ->
                        if (granted) {
                            vm.updateSettings { it.copy(location = DownloadLocation.PUBLIC) }
                            vm.message("New downloads go to Downloads/BetterTorrent")
                        } else {
                            vm.message("All files access wasn't granted, so downloads stay in app storage.")
                        }
                    }
                } else {
                    vm.updateSettings { it.copy(location = DownloadLocation.APP) }
                }
            },
            onDismiss = { locationDialog = false },
        )
    }

    limitDialog?.let { kind ->
        SpeedLimitDialog(
            title = if (kind == LimitKind.DOWNLOAD) "Download speed limit" else "Upload speed limit",
            currentKb = if (kind == LimitKind.DOWNLOAD) s.downloadLimitKb else s.uploadLimitKb,
            onSave = { kb ->
                vm.updateSettings { if (kind == LimitKind.DOWNLOAD) it.copy(downloadLimitKb = kb) else it.copy(uploadLimitKb = kb) }
                limitDialog = null
            },
            onDismiss = { limitDialog = null },
        )
    }
}

private fun limitLabel(kb: Int): String = when {
    kb <= 0 -> "Unlimited"
    kb >= 1024 && kb % 1024 == 0 -> "${kb / 1024} MB/s"
    else -> "$kb KB/s"
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    footnote: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            headlineContent = { Text(title) },
            supportingContent = footnote?.let { { Text(it) } },
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
        ) {
            options.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun LocationDialog(current: DownloadLocation, onSelect: (DownloadLocation) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download location") },
        text = {
            Column {
                LocationOption(
                    selected = current == DownloadLocation.APP,
                    title = "App storage",
                    body = "Private to BetterTorrent, like your own Seedr cloud. Use Library → Save to phone Downloads to export files.",
                    onClick = { onSelect(DownloadLocation.APP) },
                )
                Spacer(Modifier.height(8.dp))
                LocationOption(
                    selected = current == DownloadLocation.PUBLIC,
                    title = "Downloads/BetterTorrent",
                    body = "Files are visible to gallery and file manager apps. Needs “All files access”.",
                    onClick = { onSelect(DownloadLocation.PUBLIC) },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Applies to new torrents. Existing ones stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun LocationOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SpeedLimitDialog(title: String, currentKb: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(if (currentKb > 0) currentKb.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    val presets = listOf(0 to "Unlimited", 512 to "512 KB/s", 1024 to "1 MB/s", 5120 to "5 MB/s")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (kb, label) ->
                        FilterChip(
                            selected = (text.toIntOrNull() ?: 0) == kb,
                            onClick = {
                                text = if (kb == 0) "" else kb.toString()
                                error = null
                            },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        text = v.filter { it.isDigit() }.take(7)
                        error = null
                    },
                    label = { Text("Limit in KB/s") },
                    supportingText = { Text(error ?: "Leave empty for unlimited") },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val kb = text.toIntOrNull() ?: 0
                if (kb in 1..9) error = "Use at least 10 KB/s, or leave empty" else onSave(kb)
            }) { Text("Save limit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
