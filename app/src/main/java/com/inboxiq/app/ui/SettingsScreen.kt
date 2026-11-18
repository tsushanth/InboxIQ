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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.inboxiq.app.R
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
    val onOpenAutoReply: () -> Unit = {},
    val onOpenDefaultSettings: () -> Unit = {},
    val onOpenPreviousApp: () -> Unit = {},
    val onMakeDefault: () -> Unit = {},
    val onOpenSetupLog: () -> Unit = {},
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
    /** null hides the entry (build flag off); otherwise whether auto-reply is on. */
    autoReplyEnabled: Boolean? = null,
    isDefault: Boolean = true,
    /** Name of the SMS app InboxIQ replaced, when known. */
    previousAppName: String? = null,
    canOpenPreviousApp: Boolean = false,
    /** Debug builds only: shows the on-device setup log entry. */
    showSetupLog: Boolean = false,
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
            IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.ui_settings), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---------- default SMS app ----------
            Section(stringResource(R.string.default_section))
            Text(
                stringResource(if (isDefault) R.string.default_status_yes else R.string.default_status_no),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (previousAppName != null) stringResource(R.string.default_back_body_named, previousAppName)
                else stringResource(R.string.default_back_body_generic),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (!isDefault) {
                Button(onClick = callbacks.onMakeDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.default_make_default)) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = callbacks.onOpenDefaultSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.default_open_settings))
            }
            if (canOpenPreviousApp && previousAppName != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = callbacks.onOpenPreviousApp, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.default_open_previous, previousAppName))
                }
            }

            // ---------- appearance ----------
            Section(stringResource(R.string.settings_appearance))
            ThemeMode.entries.forEach { m ->
                ChoiceRow(stringResource(m.labelRes), theme.mode == m) { callbacks.onTheme(theme.copy(mode = m)) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(stringResource(R.string.settings_wallpaper_colours), stringResource(R.string.settings_wallpaper_colours_body), theme.dynamicColor) {
                    callbacks.onTheme(theme.copy(dynamicColor = it))
                }
            }
            Sub(stringResource(R.string.settings_text_size))
            ThemeSettings.FONT_SCALES.forEach { s ->
                ChoiceRow(stringResource(ThemeSettings.fontScaleLabelRes(s)), theme.fontScale == s) { callbacks.onTheme(theme.copy(fontScale = s)) }
            }
            Text(stringResource(R.string.ui_on_top_of_your_phone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Sub(stringResource(R.string.settings_list_density))
            Density.entries.forEach { d ->
                ChoiceRow(stringResource(d.labelRes), theme.density == d) { callbacks.onTheme(theme.copy(density = d)) }
            }

            // ---------- sending ----------
            Section(stringResource(R.string.settings_sending))
            Sub(stringResource(R.string.settings_undo_send))
            listOf(0 to R.string.settings_undo_off, 5 to R.string.settings_undo_5s, 10 to R.string.settings_undo_10s).forEach { (secs, labelRes) ->
                ChoiceRow(stringResource(labelRes), undoSeconds == secs) { callbacks.onUndoSeconds(secs) }
            }
            Text(
                stringResource(R.string.ui_applies_to_text_messages_picture),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---------- sorting ----------
            Section(stringResource(R.string.settings_sorting))
            Text(
                stringResource(R.string.sorting_transactions_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
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
                    Text(stringResource(tier.labelRes), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
                if (tier == ClassifierTier.MID && current == ClassifierTier.MID) {
                    val hint = when (val s = modelState) {
                        is ModelDownloadState.Downloading -> stringResource(R.string.settings_model_downloading, if (s.totalBytes > 0) (100 * s.bytesDownloaded / s.totalBytes).toInt() else 0)
                        is ModelDownloadState.WaitingForWifi -> stringResource(R.string.settings_model_waiting_wifi)
                        is ModelDownloadState.RequiresConfirmation -> stringResource(R.string.settings_model_confirm)
                        is ModelDownloadState.Failed -> stringResource(R.string.settings_model_failed)
                        is ModelDownloadState.Ready -> stringResource(R.string.settings_model_ready)
                        is ModelDownloadState.NotDownloaded -> stringResource(R.string.settings_model_starting)
                    }
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 40.dp, bottom = 8.dp))
                }
            }

            // ---------- auto reply (only when compiled in) ----------
            if (autoReplyEnabled != null) {
                Section(stringResource(R.string.settings_auto_reply))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = callbacks.onOpenAutoReply),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_auto_reply_when_away), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(if (autoReplyEnabled) R.string.settings_auto_reply_summary_on else R.string.settings_auto_reply_summary_off), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            // ---------- blocked ----------
            Section(stringResource(R.string.settings_blocked_numbers))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = callbacks.onOpenBlocked),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ui_manage_blocked_numbers), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (blockedCount == 0) stringResource(R.string.settings_none_blocked) else pluralStringResource(R.plurals.settings_blocked_count, blockedCount, blockedCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }

            // ---------- backup ----------
            Section(stringResource(R.string.settings_backup))
            Text(
                stringResource(R.string.ui_save_your_conversations_to_a),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = callbacks.onExport) { Text(stringResource(R.string.ui_save_backup)) }
                OutlinedButton(onClick = callbacks.onImport) { Text(stringResource(R.string.ui_restore_from_file)) }
            }
            backupStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                stringResource(R.string.ui_restored_messages_are_added_to),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---------- privacy ----------
            Section(stringResource(R.string.settings_privacy))
            Text(
                stringResource(R.string.settings_privacy_note),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = callbacks.onShowIntro, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_read_what_inboxiq_does_and)) }
            if (showSetupLog) {
                OutlinedButton(onClick = callbacks.onOpenSetupLog, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_setup_log_debug, stringResource(R.string.funnel_title))) }
            }

            if (showCloudSections) {
                com.inboxiq.app.feature.RelayFeatures.instance.SettingsSection()
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
