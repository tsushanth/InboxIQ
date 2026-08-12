package com.inboxiq.app.mcp

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import com.inboxiq.app.data.AppDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Off by default — the whole point is that the local-network server (and everything it can do:
 * read messages, and with per-call on-device approval, send them) only exists when a user
 * explicitly turns it on, not ambiently.
 */
@Composable
fun ConnectedAgentsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(McpForegroundService.isRunning) }
    var showPairingDialog by remember { mutableStateOf(false) }
    val devices by AppDatabase.get(context).pairedDeviceDao().observeAll().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    Text("Connected agents", style = MaterialTheme.typography.titleSmall)
    Text(
        "Lets your own AI agent (running on your computer, same wifi network) read messages. It can " +
            "propose texts to send, but they're only queued as drafts below for you to review and send " +
            "yourself — it never sends anything directly. Off by default; nothing is reachable off your local network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable agent connection", modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { checked ->
                enabled = checked
                val intent = Intent(context, McpForegroundService::class.java)
                if (checked) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            },
        )
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showPairingDialog = true }) { Text("Pair new agent") }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Paired devices", style = MaterialTheme.typography.labelMedium)
            devices.forEach { device ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.displayName)
                        Text(
                            "Paired ${formatRelativeDate(device.pairedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        coroutineScope.launch { AppDatabase.get(context).pairedDeviceDao().revoke(device.id) }
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Revoke ${device.displayName}")
                    }
                }
                HorizontalDivider()
            }
        }
    }

    if (showPairingDialog) {
        PairingDialog(context = context, onDismiss = { showPairingDialog = false })
    }
}

@Composable
private fun PairingDialog(context: Context, onDismiss: () -> Unit) {
    var secondsLeft by remember { mutableStateOf(PAIRING_TTL_SECONDS) }
    val payload = remember {
        val host = NetworkUtil.localWifiAddress()
        val token = PairingManager.begin()
        JsonObject().apply {
            addProperty("host", host)
            addProperty("port", McpServer.PORT)
            addProperty("pairingToken", token)
        }.toString()
    }
    val qrBitmap = remember { QrCodeGenerator.generate(payload) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        PairingManager.cancel()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { PairingManager.cancel(); onDismiss() },
        title = { Text("Pair new agent") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.size(220.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If your agent's device has a camera, scan the code above. Expires in ${secondsLeft}s.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text(
                    "On a computer? Copy this and paste it to Claude Code — tell it to \"connect InboxIQ\" " +
                        "and it'll handle the rest:",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    payload,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { clipboard.setText(AnnotatedString(payload)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(0.dp))
                    Text("  Copy to clipboard")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "You'll get a confirmation on this phone before it's finalized.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = { PairingManager.cancel(); onDismiss() }) { Text("Cancel") }
        },
    )
}

private fun formatRelativeDate(millis: Long): String {
    val days = (System.currentTimeMillis() - millis) / (24 * 60 * 60 * 1000)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

private const val PAIRING_TTL_SECONDS = 120
