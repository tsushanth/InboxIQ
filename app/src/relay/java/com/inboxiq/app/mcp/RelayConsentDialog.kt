package com.inboxiq.app.mcp

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.inboxiq.app.R

/**
 * Informed-consent gate for the Claude relay. Names exactly what leaves the phone and who
 * receives it. The relay cannot be enabled (RelayConfig.isEnabled) without this having been
 * accepted for the current RelayConfig.CONSENT_VERSION.
 */
@Composable
fun RelayConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.relay_consent_title)) },
        text = { Text(stringResource(R.string.relay_consent_body), modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.relay_consent_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.relay_consent_decline)) } },
    )
}
