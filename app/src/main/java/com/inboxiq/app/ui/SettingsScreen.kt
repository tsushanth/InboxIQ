package com.inboxiq.app.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.classify.GemmaModelStore
import com.inboxiq.app.classify.ModelDownloadState
import com.inboxiq.app.ui.theme.Density
import com.inboxiq.app.ui.theme.ThemeMode
import com.inboxiq.app.ui.theme.ThemeSettings

class SettingsCallbacks(
    val onBack: () -> Unit = {},
    val onSelectTier: (ClassifierTier) -> Unit = {},
    val onTheme: (ThemeSettings) -> Unit = {},
    val onUndoSeconds: (Int) -> Unit = {},
    val onOpenBlocked: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onShowIntro: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    selectedTier: ClassifierTier,
    theme: ThemeSettings,
    undoSeconds: Int,
    blockedCount: Int,
    backupStatus: String?,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
    showCloudSections: Boolean = true,
) {
    var current by remember { mutableStateOf(selectedTier) }
    val context = LocalContext.current
    var modelState by remember {
        mutableStateOf<ModelDownloadState>(
            if (!showCloudSections) ModelDownloadState.NotDownloaded
            else if (GemmaModelStore.isReady(context)) ModelDownloadState.Ready else ModelDownloadState.NotDownloaded,
        )
    }
    if (showCloudSections) LaunchedEffect(Unit) { GemmaModelStore.observeDownloadState(context).collect { modelState = it } }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---------- appearance ----------
            Section("Appearance")
            ThemeMode.entries.forEach { m ->
                ChoiceRow(m.label, theme.mode == m) { callbacks.onTheme(theme.copy(mode = m)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Use wallpaper colours", "Material You. Label colours stay fixed so they keep their meaning.", theme.dynamicColor) {
                    callbacks.onTheme(theme.copy(dynamicColor = it))
                }
            }
            Sub("Text size")
            ThemeSettings.FONT_SCALES.forEach { s ->
                ChoiceRow(ThemeSettings.fontScaleLabel(s), theme.fontScale == s) { callbacks.onTheme(theme.copy(fontScale = s)) }
            }
            Text("On top of your phone's own font size setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Sub("List density")
            Density.entries.forEach { d ->
                ChoiceRow(d.label, theme.density == d) { callbacks.onTheme(theme.copy(density = d)) }
            }

            // ---------- sending ----------
            Section("Sending")
            Sub("Undo send")
            listOf(0 to "Off, send immediately", 5 to "5 seconds", 10 to "10 seconds").forEach { (secs, label) ->
                ChoiceRow(label, undoSeconds == secs) { callbacks.onUndoSeconds(secs) }
            }
            Text(
                "Applies to text messages. Picture messages always send immediately.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------- sorting ----------
            Section("Sorting")
            ClassifierTier.entries.forEach { tier ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = current == tier, enabled = tier.available, role = Role.RadioButton) {
                            current = tier
                            callbacks.onSelectTier(tier)
                            if (tier == ClassifierTier.MID && modelState !is ModelDownloadState.Ready) GemmaModelStore.requestDownload(context)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = current == tier, onClick = null, enabled = tier.available)
                    Text(tier.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
                if (tier == ClassifierTier.MID && current == ClassifierTier.MID) {
                    val hint = when (val s = modelState) {
                        is ModelDownloadState.Downloading -> "Downloading model, ${if (s.totalBytes > 0) (100 * s.bytesDownloaded / s.totalBytes) else 0}%"
                        is ModelDownloadState.WaitingForWifi -> "Waiting for Wi-Fi to download the model"
                        is ModelDownloadState.RequiresConfirmation -> "Tap to confirm the model download"
                        is ModelDownloadState.Failed -> "The download failed. Choose this option again to retry."
                        is ModelDownloadState.Ready -> "Model ready. Sorting uses Gemma 3 270M."
                        is ModelDownloadState.NotDownloaded -> "Starting download"
                    }
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
                }
            }

            // ---------- blocked ----------
            Section("Blocked numbers")
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = callbacks.onOpenBlocked),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Manage blocked numbers", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (blockedCount == 0) "None blocked" else "$blockedCount blocked. Texts from them are not saved.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }

            // ---------- backup ----------
            Section("Backup")
            Text(
                "Save your conversations to a file you choose, or restore from one. Nothing is uploaded. Photos are not included; only text, labels, pins, archive and mute settings, and blocked numbers.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = callbacks.onExport) { Text("Save backup") }
                OutlinedButton(onClick = callbacks.onImport) { Text("Restore from file") }
            }
            backupStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "Restored messages are added to InboxIQ only. They are not written to Android's own message storage.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---------- privacy ----------
            Section("Privacy")
            Text(
                "Sorting happens on this phone. InboxIQ has no accounts, ads or analytics. Nothing leaves the phone unless you turn on Connect to Claude below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = callbacks.onShowIntro, modifier = Modifier.padding(top = 8.dp)) { Text("Read what InboxIQ does and does not access") }

            if (showCloudSections) {
                Spacer(Modifier.height(24.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(16.dp))
                com.inboxiq.app.mcp.CloudConnectSection()
                com.inboxiq.app.mcp.AgentDraftsSection()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Sub(title: String) {
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
