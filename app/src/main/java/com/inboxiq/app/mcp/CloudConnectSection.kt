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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.inboxiq.app.R
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

/**
 * Cloud Connector setup — links this phone's number to inboxiq-mcp-backend so it can be
 * added as a real Claude Connector (claude.ai / Desktop) from any device. Phone-number
 * ownership is proven here with a code the relay reads out by voice call (see
 * [RelayLinkController]), and again by the OAuth /authorize step on the device that adds the
 * connector.
 */
@Composable
fun CloudConnectSection() {
    val context = LocalContext.current
    val controller = remember { RelayLinkUi.controller(context) }
    val ui by controller.state.collectAsState()
    val status by RelayRuntime.status().collectAsState()
    var phoneNumber by remember { mutableStateOf(RelayConfig.phoneNumber(context) ?: "") }
    var code by remember { mutableStateOf("") }
    var showConsent by remember { mutableStateOf(false) }
    val working = ui.phase == LinkPhase.WORKING
    val awaiting = ui.phase == LinkPhase.AWAITING_CODE || (working && ui.challengeId != null)

    // Tick once a second only while a cool-down is running, so the Resend button re-enables itself.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.blockedUntilMs) {
        nowMs = System.currentTimeMillis()
        while (nowMs < ui.blockedUntilMs) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }
    val waitSeconds = ((ui.blockedUntilMs - nowMs + 999) / 1000).coerceAtLeast(0)
    LaunchedEffect(ui.phase, ui.challengeId) { if (ui.phase == LinkPhase.IDLE) code = "" }

    // Consent is always accepted before anything is sent to the relay, including the voice call.
    val startVerify: () -> Unit = { controller.begin(phoneNumber) }
    if (showConsent) {
        RelayConsentDialog(
            onAccept = {
                showConsent = false
                RelayConfig.recordConsent(context)
                startVerify()
            },
            onDecline = { showConsent = false },
        )
    }

    Text("Connect to Claude", style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.relay_section_blurb),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    OutlinedTextField(
        value = phoneNumber,
        onValueChange = { phoneNumber = it },
        label = { Text("Phone number (e.g. +14155551234)") },
        singleLine = true,
        enabled = !working && !awaiting,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Enable connection", modifier = Modifier.weight(1f))
        if (working) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        } else {
            Switch(
                checked = ui.enabled,
                enabled = !awaiting,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (phoneNumber.isBlank()) controller.begin(phoneNumber)
                        else if (RelayConfig.hasConsent(context)) startVerify() else showConsent = true
                    } else {
                        // Clears the key on this phone, then asks the relay to revoke it (best effort).
                        controller.disconnect()
                    }
                },
            )
        }
    }
    noticeText(ui.notice, ui.retryAfterSeconds)?.let {
        Text(
            it, style = MaterialTheme.typography.bodySmall,
            color = if (ui.notice == LinkNotice.CODE_SENT) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (awaiting) {
        val codeLabel = stringResource(R.string.relay_code_label)
        OutlinedTextField(
            value = code,
            // Digits only, max 6. The value is never logged or persisted.
            onValueChange = { v -> code = v.filter { it in '0'..'9' }.take(6) },
            label = { Text(codeLabel) },
            singleLine = true,
            enabled = !working && ui.notice != LinkNotice.LOCKED,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { contentDescription = codeLabel },
        )
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { controller.submitCode(phoneNumber, code) }, enabled = !working && code.length == 6) {
                Text(stringResource(R.string.relay_code_confirm))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { controller.resend(phoneNumber) }, enabled = !working && waitSeconds == 0L) {
                Text(
                    if (waitSeconds > 0) stringResource(R.string.relay_code_resend_wait, waitSeconds.toInt())
                    else stringResource(R.string.relay_code_resend),
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { controller.cancel() }, enabled = !working) { Text(stringResource(R.string.relay_code_cancel)) }
        }
    } else if (!ui.enabled && ui.notice == LinkNotice.CONFLICT_NEEDS_CODE) {
        OutlinedButton(onClick = startVerify, enabled = !working, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.relay_verify_action))
        }
    } else if (!ui.enabled && waitSeconds > 0 && (ui.notice == LinkNotice.LOCKED || ui.notice == LinkNotice.SEND_RATE_LIMITED)) {
        Text(
            stringResource(R.string.relay_try_again_in, formatWait(waitSeconds)),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    if (ui.enabled) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(statusLabel(status)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.relay_foreground_only_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if ((status == RelayStatus.RECONNECT_NEEDED || status == RelayStatus.REPLACED) && !awaiting) {
            OutlinedButton(onClick = startVerify, enabled = !working, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.relay_verify_action))
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

@Composable
private fun noticeText(notice: LinkNotice, retryAfter: Long?): String? = when (notice) {
    LinkNotice.NONE -> null
    LinkNotice.ENTER_PHONE -> stringResource(R.string.relay_notice_enter_phone)
    LinkNotice.CODE_SENT -> stringResource(R.string.relay_notice_code_sent)
    LinkNotice.WRONG_CODE -> stringResource(R.string.relay_notice_wrong_code)
    LinkNotice.CODE_EXPIRED -> stringResource(R.string.relay_notice_code_expired)
    LinkNotice.ENTER_SIX_DIGITS -> stringResource(R.string.relay_notice_six_digits)
    LinkNotice.LOCKED ->
        if (retryAfter != null) stringResource(R.string.relay_notice_locked_after, formatWait(retryAfter))
        else stringResource(R.string.relay_notice_locked)
    LinkNotice.SEND_RATE_LIMITED ->
        if (retryAfter != null) stringResource(R.string.relay_notice_rate_limited_after, formatWait(retryAfter))
        else stringResource(R.string.relay_notice_rate_limited)
    LinkNotice.INVALID_PHONE -> stringResource(R.string.relay_notice_invalid_phone)
    LinkNotice.DESTINATION_NOT_ALLOWED -> stringResource(R.string.relay_notice_destination)
    LinkNotice.DELIVERY_FAILED -> stringResource(R.string.relay_notice_delivery_failed)
    LinkNotice.NETWORK -> stringResource(R.string.relay_notice_network)
    LinkNotice.CONFLICT_NEEDS_CODE -> stringResource(R.string.relay_notice_conflict)
    LinkNotice.DISCONNECTED_SERVER_UNREACHABLE -> stringResource(R.string.relay_notice_disconnected_unreachable)
    LinkNotice.DISCONNECTED_NO_REMOTE_REVOKE -> stringResource(R.string.relay_notice_disconnected_no_revoke)
    LinkNotice.REJECTED -> stringResource(R.string.relay_status_reconnect_needed)
}

/** "5 minutes" style wait; rounds up so we never tell the user to retry too early. */
fun formatWait(seconds: Long): String = when {
    seconds < 90 -> "$seconds seconds"
    seconds < 3600 -> "${(seconds + 59) / 60} minutes"
    else -> "${(seconds + 3599) / 3600} hour" + if (seconds > 3600) "s" else ""
}

/** Process-wide holder so the flow (and a pending code challenge) survives rotation. */
object RelayLinkUi {
    @Volatile private var instance: RelayLinkController? = null
    fun controller(context: Context): RelayLinkController = instance ?: synchronized(this) {
        instance ?: RelayLinkController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            client = HttpRelayLinkClient(),
            store = ContextLinkStore(context.applicationContext),
        ).also { instance = it }
    }
}

fun statusLabel(status: RelayStatus): Int = when (status) {
    RelayStatus.OFF -> R.string.relay_status_off
    RelayStatus.CONNECTING -> R.string.relay_status_connecting
    RelayStatus.CONNECTED -> R.string.relay_status_connected
    RelayStatus.OFFLINE -> R.string.relay_status_offline
    RelayStatus.PAUSED -> R.string.relay_status_paused
    RelayStatus.RECONNECT_NEEDED -> R.string.relay_status_reconnect_needed
    RelayStatus.REPLACED -> R.string.relay_status_replaced
}
