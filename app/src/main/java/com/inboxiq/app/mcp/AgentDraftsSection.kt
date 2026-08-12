package com.inboxiq.app.mcp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.inboxiq.app.data.AgentDraftEntity
import com.inboxiq.app.data.AgentDraftStatus
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.sms.SmsSender
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where drafts queued by send_message (see McpTools.sendMessage) actually get sent — the agent
 * never sends directly. Shows the full body and resolved contact name so the concern that a
 * bare phone number in a notification isn't enough to confirm the right recipient doesn't apply
 * here: this is the one place a send is a considered, in-app decision.
 */
@Composable
fun AgentDraftsSection() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dao = AppDatabase.get(context).agentDraftDao()
    val drafts by dao.observePending().collectAsState(initial = emptyList())

    if (drafts.isEmpty()) return

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    Text("Agent drafts", style = MaterialTheme.typography.titleSmall)
    Text(
        "Your agent asked to send these. Nothing has been sent — review and choose for each one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    drafts.forEach { draft ->
        DraftCard(
            draft = draft,
            onSend = {
                coroutineScope.launch {
                    SmsSender.send(context, draft.address, draft.body)
                    dao.setStatus(draft.id, AgentDraftStatus.SENT)
                }
            },
            onDelete = {
                coroutineScope.launch { dao.setStatus(draft.id, AgentDraftStatus.DISCARDED) }
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DraftCard(draft: AgentDraftEntity, onSend: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        val who = draft.resolvedName?.let { "${it} (${draft.address})" } ?: draft.address
        Text("To: $who", style = MaterialTheme.typography.labelLarge)
        Text(
            timeFormat.format(Date(draft.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(draft.body, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSend) { Text("Send") }
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

private val timeFormat get() = SimpleDateFormat("MMM d, h:mm a", Locale.US)
