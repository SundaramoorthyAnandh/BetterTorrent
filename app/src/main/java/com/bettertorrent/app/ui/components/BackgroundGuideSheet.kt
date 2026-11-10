package com.bettertorrent.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bettertorrent.app.util.BatteryOptimization
import com.bettertorrent.app.util.OemGuide
import com.bettertorrent.app.util.OemStep

/** "Keep downloads running" setup: Android battery exemption plus the phone maker's own settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundGuideSheet(
    guide: OemGuide,
    onAllowBattery: () -> Unit,
    onOpenStep: (OemStep) -> Unit,
    onOpenHelp: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(BatteryOptimization.isExempt(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = BatteryOptimization.isExempt(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Keep downloads running", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                if (guide.aggressive) {
                    "${guide.brand} phones close apps in the background to save battery. Do these once so downloads keep going with the screen off."
                } else {
                    "Do this once so downloads keep going with the screen off."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            GuideStep(
                number = 1,
                title = "Allow background use",
                body = if (exempt) {
                    "Done. Android battery optimization is off for BetterTorrent."
                } else {
                    "Turns off Android battery optimization for BetterTorrent. Tap Allow in the dialog."
                },
                done = exempt,
                actionLabel = if (exempt) null else "Allow",
                onAction = onAllowBattery,
            )
            guide.steps.forEachIndexed { i, step ->
                GuideStep(
                    number = i + 2,
                    title = step.title,
                    body = step.body,
                    done = false,
                    actionLabel = if (step.intents.isNotEmpty()) "Open settings" else null,
                    onAction = { onOpenStep(step) },
                )
            }

            TextButton(onClick = { onOpenHelp(guide.helpUrl) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("More tips for ${guide.brand} phones")
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GuideStep(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(Modifier.padding(bottom = 18.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(Icons.Rounded.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(18.dp))
            } else {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
