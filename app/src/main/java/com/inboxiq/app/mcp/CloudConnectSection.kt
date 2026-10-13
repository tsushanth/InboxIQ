package com.inboxiq.app.mcp

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    // Reflects whether the service is actually alive, not just the persisted preference —
    // those can drift apart (e.g. the process was killed since), and showing "on" for a dead
    // connection is worse than showing "off": it tells the user everything's fine when it isn't.
    var enabled by remember { mutableStateOf(RelayForegroundService.isRunning) }
    var phoneNumber by remember { mutableStateOf(RelayConfig.phoneNumber(context) ?: "") }
    var isLinking by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    // The user can grant the exemption from system Settings (reached via the banner below,
    // or independently) and come straight back here without the app process restarting, so
    // recheck on every resume rather than only once at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOptimizations = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startLinking: () -> Unit = {
        isLinking = true
        coroutineScope.launch {
            val result = RelayApi.linkPhone(phoneNumber, RelayConfig.relayToken(context))
            isLinking = false
            if (result.isSuccess) {
                RelayConfig.setPhoneNumber(context, phoneNumber)
                RelayConfig.setEnabled(context, true)
                enabled = true
                statusText = null
                context.startForegroundService(Intent(context, RelayForegroundService::class.java))
            } else {
                statusText = "Couldn't connect — check your connection and try again."
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
                        context.stopService(Intent(context, RelayForegroundService::class.java))
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

    Spacer(Modifier.height(12.dp))
    BatteryOptimizationStatusRow(
        ignoringBatteryOptimizations = ignoringBatteryOptimizations,
        onRequestExemption = { BatteryOptimizationHelper.requestExemption(context) },
    )
}

/**
 * Persistent status row for the relay's Doze exemption. Shown regardless of whether the
 * relay switch above is currently on — the exemption matters the moment it's enabled, and a
 * user checking this screen while the relay is off should still see it's not protected yet.
 */
@Composable
private fun BatteryOptimizationStatusRow(
    ignoringBatteryOptimizations: Boolean,
    onRequestExemption: () -> Unit,
) {
    if (ignoringBatteryOptimizations) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            Text(
                "Background delivery: reliable",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
        ) {
            Text(
                "Background reliability may be limited",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Android's battery optimization can silently stop the relay connection " +
                    "while InboxIQ is in the background. You can exempt InboxIQ in system settings. The connection also stops if you restart your phone; reopen InboxIQ to reconnect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            Button(onClick = onRequestExemption) {
                Text("Open battery settings")
            }
        }
    }
}
