package com.inboxiq.app.mcp

import android.content.Intent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    var enabled by remember { mutableStateOf(RelayConfig.isEnabled(context)) }
    var phoneNumber by remember { mutableStateOf(RelayConfig.phoneNumber(context) ?: "") }
    var isLinking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    Text("Connect to Claude", style = MaterialTheme.typography.titleSmall)
    Text(
        "Add InboxIQ as a Connector in claude.ai or Claude Desktop, on any device — synced " +
            "to your Claude account, not tied to this Wi-Fi. It can read messages and propose " +
            "texts, but they're only queued as drafts below for you to review and send yourself.",
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
                    val intent = Intent(context, RelayForegroundService::class.java)
                    if (checked) {
                        isLinking = true
                        coroutineScope.launch {
                            val result = RelayApi.linkPhone(phoneNumber, RelayConfig.relayToken(context))
                            isLinking = false
                            if (result.isSuccess) {
                                RelayConfig.setPhoneNumber(context, phoneNumber)
                                RelayConfig.setEnabled(context, true)
                                enabled = true
                                statusText = null
                                context.startForegroundService(intent)
                            } else {
                                statusText = "Couldn't connect — check your connection and try again."
                            }
                        }
                    } else {
                        RelayConfig.setEnabled(context, false)
                        enabled = false
                        context.stopService(intent)
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
            "Now add InboxIQ as a Connector in claude.ai (Settings → Connectors) — you'll " +
                "get a call there reading out a verification code for this same phone number.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
