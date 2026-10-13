package com.inboxiq.app.mcp

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.inboxiq.app.R
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Cloud Connector setup — links this phone's number to inboxiq-mcp-backend so it can be
 * added as a real Claude Connector (claude.ai / Desktop) from any device. Phone-number
 * ownership is verified separately, via SMS OTP, during the OAuth /authorize step on
 * whichever device adds the connector — this screen just establishes the phone's side of
 * the relay connection.
 */
@Composable
fun CloudConnectSection() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val linkClient = remember { HttpRelayLinkClient() }
    // The persisted preference says what the user asked for; the live status says what is
    // actually happening. Both are shown, because "on" with no connection must not look fine.
    var enabled by remember { mutableStateOf(RelayConfig.isEnabled(context)) }
    val status by RelayRuntime.status().collectAsState()
    var phoneNumber by remember { mutableStateOf(RelayConfig.phoneNumber(context) ?: "") }
    var isLinking by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val startLinking: () -> Unit = {
        isLinking = true
        coroutineScope.launch {
            val result = linkClient.link(phoneNumber, RelayConfig.relayToken(context))
            isLinking = false
            when (result) {
                LinkResult.Linked -> {
                    RelayConfig.setPhoneNumber(context, phoneNumber)
                    RelayConfig.clearReconnectNeeded(context)
                    RelayConfig.setEnabled(context, true)
                    enabled = true
                    statusText = null
                    RelayRuntime.refresh()
                }
                LinkResult.Unauthorized -> {
                    RelayConfig.markReconnectNeeded(context)
                    RelayRuntime.refresh()
                    statusText = context.getString(R.string.relay_status_reconnect_needed)
                }
                is LinkResult.Failed -> statusText = "Couldn't connect. Check your connection and try again."
            }
        }
    }
    if (showConsent) {
        RelayConsentDialog(
            onAccept = {
                showConsent = false
                RelayConfig.recordConsent(context)
                startLinking()
            },
            onDecline = { showConsent = false },
        )
    }

    Text("Connect to Claude", style = MaterialTheme.typography.titleSmall)
    Text(
        androidx.compose.ui.res.stringResource(com.inboxiq.app.R.string.relay_section_blurb),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    OutlinedTextField(
        value = phoneNumber,
        onValueChange = { phoneNumber = it },
        label = { Text("Phone number (e.g. +14155551234)") },
        singleLine = true,
        enabled = !isLinking,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable connection", modifier = Modifier.weight(1f))
        if (isLinking) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        } else {
            Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                    if (checked && phoneNumber.isBlank()) {
                        statusText = "Enter your phone number first."
                        return@Switch
                    }
                    if (checked) {
                        if (RelayConfig.hasConsent(context)) startLinking() else showConsent = true
                    } else {
                        // Also discards the relay key, so nothing can reach this phone until re-enabled.
                        RelayConfig.disable(context)
                        enabled = false
                        RelayRuntime.refresh()
                    }
                },
            )
        }
    }
    statusText?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(statusLabel(status)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.relay_foreground_only_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status == RelayStatus.RECONNECT_NEEDED) {
            OutlinedButton(onClick = startLinking, enabled = !isLinking, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.relay_reconnect_action))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Now add InboxIQ as a Connector in claude.ai (Settings → Connectors) — you'll " +
                "get a call there reading out a verification code for this same phone number.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

fun statusLabel(status: RelayStatus): Int = when (status) {
    RelayStatus.OFF -> R.string.relay_status_off
    RelayStatus.CONNECTING -> R.string.relay_status_connecting
    RelayStatus.CONNECTED -> R.string.relay_status_connected
    RelayStatus.OFFLINE -> R.string.relay_status_offline
    RelayStatus.PAUSED -> R.string.relay_status_paused
    RelayStatus.RECONNECT_NEEDED -> R.string.relay_status_reconnect_needed
}
