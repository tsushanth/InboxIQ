package com.inboxiq.app.ui

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inboxiq.app.autoreply.AutoReplyConfig
import com.inboxiq.app.autoreply.AutoReplyConfirmation
import com.inboxiq.app.autoreply.AutoReplyLogEntry
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

private fun fmtMin(m: Int) = "%d:%02d %s".format(if (m / 60 % 12 == 0) 12 else m / 60 % 12, m % 60, if (m / 60 < 12) "AM" else "PM")

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
            IconButton(onClick = callbacks.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings") }
            Text("Auto-reply when away", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Sends one short text for you when someone you know messages while you are away. It is off until you turn it on, and it only ever answers people you have texted before or saved as contacts. These are real texts sent from your number, so they may cost money on plans that charge per text. To turn it off, switch it off below or tap Turn off in the list of auto-replies sent.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isDefaultSms) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "InboxIQ is not your default SMS app right now, so nothing can be sent automatically. Make it the default from the inbox to use this.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                )
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 12.dp)
                    .toggleable(value = config.enabled, role = Role.Switch, onValueChange = { on -> if (on) confirm = true else callbacks.onDisable() }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (config.enabled) "On" else "Off", style = MaterialTheme.typography.titleMedium)
                    Text(if (config.enabled) statusLine(config, now) else "Nothing is sent automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = config.enabled, onCheckedChange = null)
            }

            ASection("Message")
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                isError = problem != null, supportingText = { Text(problem ?: "${text.length}/${AutoReplyConfig.MAX_MESSAGE_CHARS}. Changing the text turns auto-reply off until you confirm again.") },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoReplyConfig.TEMPLATES.forEach { t -> AssistChip(onClick = { text = t; if (AutoReplyConfig.validateMessage(t) == null) callbacks.onSave(config.copy(message = t)) }, label = { Text(t) }) }
            }
            OutlinedButton(onClick = { callbacks.onSave(config.copy(message = text.trim())) }, enabled = problem == null && text.trim() != config.message, modifier = Modifier.padding(top = 8.dp)) { Text("Save message") }

            ASection("Schedule")
            ASwitch("Only at certain times", "Otherwise it answers whenever it is on.", config.scheduleOn) { callbacks.onSave(config.copy(scheduleOn = it)) }
            if (config.scheduleOn) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> callbacks.onSave(config.copy(startMin = h * 60 + m)) }, config.startMin / 60, config.startMin % 60, false).show() }) { Text("From ${fmtMin(config.startMin)}") }
                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> callbacks.onSave(config.copy(endMin = h * 60 + m)) }, config.endMin / 60, config.endMin % 60, false).show() }) { Text("Until ${fmtMin(config.endMin)}") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(selected = d in config.days, onClick = { callbacks.onSave(config.copy(days = if (d in config.days) config.days - d else config.days + d)) },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                    }
                }
                Text(
                    if (config.startMin > config.endMin) "The window crosses midnight; the days are the days it starts on." else "Uses the phone's current time zone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }

            ASection("Turn off automatically")
            val zone = ZoneId.systemDefault()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("No end time" to 0L, "In 1 hour" to now + 3_600_000L, "In 4 hours" to now + 4 * 3_600_000L, "At 6 PM" to nextSixPm(now, zone)).forEach { (label, until) ->
                    FilterChip(selected = until == 0L && config.untilMillis == 0L, onClick = { callbacks.onSave(config.copy(untilMillis = until)) }, label = { Text(label) })
                }
            }
            if (config.untilMillis != 0L) Text("Switches off at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(config.untilMillis)) + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ASection("Limits")
            Text("One automatic text per person every:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(24, 12, 6, 1).forEach { h -> ChoiceRowA("$h ${if (h == 1) "hour" else "hours"}", config.perSenderHours == h) { callbacks.onSave(config.copy(perSenderHours = h)) } }
            Text("At most this many a day in total:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            listOf(20, 10, 5).forEach { n -> ChoiceRowA("$n a day", config.dailyCap == n) { callbacks.onSave(config.copy(dailyCap = n)) } }
            Text("These can be lowered but not raised. It never replies to businesses, short codes, groups, spam, blocked numbers, picture messages, old texts, or texts that look automatic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ASection("Sent so far")
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = callbacks.onOpenLog), verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-replies sent ($sentCount)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Text("Your carrier may charge for these texts, as for any text you send.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
        }
    }

    if (confirm) {
        val body = AutoReplyConfirmation.text(config)
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Turn on auto-reply?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(body) } },
            confirmButton = { Button(onClick = { confirm = false; callbacks.onEnable(body) }, enabled = config.validate() == null) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

private fun statusLine(c: AutoReplyConfig, now: Long): String = buildString {
    append("Replying to people you know")
    if (c.scheduleOn) append(", ${fmtMin(c.startMin)} to ${fmtMin(c.endMin)}")
    if (c.untilMillis > now) append(", until " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(c.untilMillis)))
    append(".")
}

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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Auto-replies sent", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
            if (enabled) Button(onClick = onTurnOff, modifier = Modifier.padding(end = 12.dp)) { Text("Turn off") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (entries.isEmpty()) {
            StateMessage(Icons.Filled.Reply, "Nothing sent yet", "Every text InboxIQ sends for you automatically is listed here, and marked \"Sent automatically\" in the conversation.", modifier = Modifier.fillMaxSize())
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
                item { TextButton(onClick = onClear, modifier = Modifier.padding(8.dp)) { Text("Clear this list") } }
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
