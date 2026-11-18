package com.inboxiq.app.ui

import com.inboxiq.app.R
import androidx.compose.ui.res.stringResource
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inboxiq.app.autoreply.AutoReplyConfig
import com.inboxiq.app.autoreply.AutoReplyConfirmation
import com.inboxiq.app.autoreply.AutoReplyLogEntry
import com.inboxiq.app.autoreply.MessageProblem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

class AutoReplyCallbacks(
    val onBack: () -> Unit = {},
    val onSave: (AutoReplyConfig) -> Unit = {},
    /** Called with the exact confirmation text the user just accepted. */
    val onEnable: (String) -> Unit = {},
    val onDisable: () -> Unit = {},
    val onOpenLog: () -> Unit = {},
)

/** A minutes-after-midnight value as a time of day in the phone's language and 12/24-hour style. */
private fun fmtMin(m: Int): String =
    java.time.LocalTime.of(m / 60 % 24, m % 60).format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))

/** Next 6 PM local time strictly in the future. */
private fun nextSixPm(now: Long, zone: ZoneId): Long {
    val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
    var d = t.toLocalDate().atTime(18, 0).atZone(zone)
    if (!d.toInstant().isAfter(t.toInstant())) d = d.plusDays(1)
    return d.toInstant().toEpochMilli()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutoReplyScreen(
    config: AutoReplyConfig,
    isDefaultSms: Boolean,
    sentCount: Int,
    callbacks: AutoReplyCallbacks,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var text by remember(config.message) { mutableStateOf(config.message) }
    val problem = AutoReplyConfig.validateMessage(text)

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back_to_settings)) }
            Text(stringResource(R.string.ui_auto_reply_when_away), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.ui_sends_one_short_text_for),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isDefaultSms) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ui_inboxiq_is_not_your_default),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 12.dp)
                    .toggleable(value = config.enabled, role = Role.Switch, onValueChange = { on -> if (on) confirm = true else callbacks.onDisable() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(if (config.enabled) R.string.autoreply_on else R.string.autoreply_off), style = MaterialTheme.typography.titleMedium)
                    Text(if (config.enabled) statusLine(context, config, now) else stringResource(R.string.autoreply_nothing_sent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = config.enabled, onCheckedChange = null)
            }

            ASection(stringResource(R.string.autoreply_section_message))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                isError = problem != null, supportingText = { Text(if (problem != null) problem.text() else stringResource(R.string.autoreply_counter_hint, text.length, AutoReplyConfig.MAX_MESSAGE_CHARS)) },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoReplyConfig.TEMPLATES.forEach { t -> AssistChip(onClick = { text = t; if (AutoReplyConfig.validateMessage(t) == null) callbacks.onSave(config.copy(message = t)) }, label = { Text(t) }) }
            }
            OutlinedButton(onClick = { callbacks.onSave(config.copy(message = text.trim())) }, enabled = problem == null && text.trim() != config.message, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.ui_save_message)) }

            ASection(stringResource(R.string.autoreply_section_schedule))
            ASwitch(stringResource(R.string.autoreply_only_certain_times), stringResource(R.string.autoreply_only_certain_times_body), config.scheduleOn) { callbacks.onSave(config.copy(scheduleOn = it)) }
            if (config.scheduleOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> callbacks.onSave(config.copy(startMin = h * 60 + m)) }, config.startMin / 60, config.startMin % 60, false).show() }) { Text(stringResource(R.string.autoreply_from_time, fmtMin(config.startMin))) }
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> callbacks.onSave(config.copy(endMin = h * 60 + m)) }, config.endMin / 60, config.endMin % 60, false).show() }) { Text(stringResource(R.string.autoreply_until_time, fmtMin(config.endMin))) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(selected = d in config.days, onClick = { callbacks.onSave(config.copy(days = if (d in config.days) config.days - d else config.days + d)) },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                    }
                }
                Text(
                    stringResource(if (config.startMin > config.endMin) R.string.autoreply_crosses_midnight else R.string.autoreply_time_zone),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }

            ASection(stringResource(R.string.autoreply_section_turn_off))
            val zone = ZoneId.systemDefault()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.autoreply_no_end_time to 0L, R.string.schedule_in_one_hour to now + 3_600_000L, R.string.autoreply_in_four_hours to now + 4 * 3_600_000L, R.string.autoreply_at_six_pm to nextSixPm(now, zone)).forEach { (labelRes, until) ->
                    FilterChip(selected = until == 0L && config.untilMillis == 0L, onClick = { callbacks.onSave(config.copy(untilMillis = until)) }, label = { Text(stringResource(labelRes)) })
                }
            }
            if (config.untilMillis != 0L) Text(stringResource(R.string.autoreply_switches_off_at, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(config.untilMillis))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ASection(stringResource(R.string.autoreply_section_limits))
            Text(stringResource(R.string.ui_one_automatic_text_per_person), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(24, 12, 6, 1).forEach { h -> ChoiceRowA(pluralStringResource(R.plurals.autoreply_hours, h, h), config.perSenderHours == h) { callbacks.onSave(config.copy(perSenderHours = h)) } }
            Text(stringResource(R.string.ui_at_most_this_many_a), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            listOf(20, 10, 5).forEach { n -> ChoiceRowA(stringResource(R.string.autoreply_per_day, n), config.dailyCap == n) { callbacks.onSave(config.copy(dailyCap = n)) } }
            Text(stringResource(R.string.ui_these_can_be_lowered_but), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ASection(stringResource(R.string.autoreply_section_sent))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = callbacks.onOpenLog), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.autoreply_sent_count, sentCount), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Text(stringResource(R.string.ui_your_carrier_may_charge_for), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
        }
    }

    if (confirm) {
        val body = AutoReplyConfirmation.text(context, config)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.ui_turn_on_auto_reply)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body) } },
            confirmButton = { Button(onClick = { confirm = false; callbacks.onEnable(body) }, enabled = config.validate() == null) { Text(stringResource(R.string.ui_turn_on)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}

private fun statusLine(context: android.content.Context, c: AutoReplyConfig, now: Long): String {
    val until = if (c.untilMillis > now) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(c.untilMillis)) else null
    return when {
        c.scheduleOn && until != null -> context.getString(R.string.autoreply_status_schedule_until, fmtMin(c.startMin), fmtMin(c.endMin), until)
        c.scheduleOn -> context.getString(R.string.autoreply_status_schedule, fmtMin(c.startMin), fmtMin(c.endMin))
        until != null -> context.getString(R.string.autoreply_status_until, until)
        else -> context.getString(R.string.autoreply_status_always)
    }
}

/** The explanation shown under the message field for a message that cannot be sent automatically. */
@Composable
private fun MessageProblem.text(): String =
    if (this == MessageProblem.TOO_LONG) stringResource(res, AutoReplyConfig.MAX_MESSAGE_CHARS) else stringResource(res)

@Composable
fun AutoReplyLogScreen(
    entries: List<AutoReplyLogEntry>,
    enabled: Boolean,
    nameOf: (String) -> String?,
    bodyOf: suspend (Long) -> String?,
    onTurnOff: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
            Text(stringResource(R.string.ui_auto_replies_sent), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (enabled) Button(onClick = onTurnOff, modifier = Modifier.padding(end = 12.dp)) { Text(stringResource(R.string.ui_turn_off)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (entries.isEmpty()) {
            StateMessage(Icons.Filled.Reply, stringResource(R.string.autoreply_log_empty_title), stringResource(R.string.autoreply_log_empty_body), modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(Modifier.navigationBarsPadding()) {
                items(entries, key = { it.at.toString() + it.address }) { e ->
                    val body by produceState<String?>(null, e.messageId) { value = bodyOf(e.messageId) }
                    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(nameOf(e.address) ?: e.address, style = MaterialTheme.typography.titleSmall)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(e.at)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                item { TextButton(onClick = onClear, modifier = Modifier.padding(8.dp)) { Text(stringResource(R.string.ui_clear_this_list)) } }
            }
        }
    }
}

@Composable private fun ASection(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(4.dp))
}

@Composable private fun ChoiceRowA(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable private fun ASwitch(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
