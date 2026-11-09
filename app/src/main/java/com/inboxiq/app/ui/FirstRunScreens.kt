package com.inboxiq.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.inboxiq.app.R
import com.inboxiq.app.data.MessageLabel

/**
 * First-run introduction and pre-switch explanation (docs/FIRST_RUN.md). It shows what the app does with an
 * inbox (made-up example), what changes and what stays when the default SMS app is switched, how to switch
 * back, and, on the core build only, how to check the "no internet" claim without trusting us. The system
 * role dialog is only launched from the button at the bottom.
 */
@Composable
fun OnboardingScreen(
    isDefault: Boolean,
    onChooseDefault: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    firstRun: Boolean = true,
    /** Name of the SMS app InboxIQ would replace, when known. */
    previousAppName: String? = null,
    /** The system dialog was closed without switching: say so, nothing changed. */
    declined: Boolean = false,
    showPrivacyProof: Boolean = booleanResource(R.bool.privacy_no_internet),
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).padding(horizontal = 24.dp, vertical = 24.dp)) {
                Text(stringResource(R.string.intro_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.intro_lead), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(20.dp))
                ExampleInbox()
                Spacer(Modifier.height(24.dp))

                IntroBlock(stringResource(R.string.intro_changes_title)) {
                    Text(
                        if (previousAppName != null) stringResource(R.string.intro_changes_body_named, previousAppName)
                        else stringResource(R.string.intro_changes_body_generic),
                    )
                    Text(stringResource(R.string.intro_changes_rcs))
                }
                IntroBlock(stringResource(R.string.intro_stays_title)) { Text(stringResource(R.string.intro_stays_body)) }
                IntroBlock(stringResource(R.string.intro_back_title)) { Text(stringResource(R.string.intro_back_body)) }
                IntroBlock(stringResource(R.string.intro_does_title)) { Text(stringResource(R.string.intro_does_body)) }
                IntroBlock(stringResource(R.string.intro_doesnt_title)) { Text(stringResource(R.string.intro_doesnt_body)) }
                if (showPrivacyProof) {
                    IntroBlock(stringResource(R.string.proof_title)) {
                        Text(stringResource(R.string.proof_lead))
                        listOf(R.string.proof_step_1, R.string.proof_step_2, R.string.proof_step_3, R.string.proof_step_4)
                            .forEachIndexed { i, id -> Text("${i + 1}. " + stringResource(id)) }
                        Text(stringResource(R.string.proof_exception))
                    }
                }
                com.inboxiq.app.feature.RelayFeatures.instance.introExceptionText()?.let { text ->
                    IntroBlock("One optional exception") { Text(text) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.fillMaxWidth().padding(16.dp).widthIn(max = 560.dp).align(Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (declined && !isDefault) {
                Text(stringResource(R.string.intro_cta_declined), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isDefault) {
                Text(stringResource(R.string.intro_next_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onChooseDefault, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.intro_cta_choose)) }
            }
            OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(
                    stringResource(
                        when {
                            !firstRun -> R.string.intro_back
                            isDefault -> R.string.intro_continue_default
                            else -> R.string.intro_skip_first_run
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun IntroBlock(title: String, body: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        ) { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { body() } }
    }
}

/** A made-up inbox, drawn with the real label chips. Clearly badged so nobody mistakes it for their own messages. */
@Composable
fun ExampleInbox(modifier: Modifier = Modifier) {
    val rows = listOf(
        Triple(R.string.intro_example_1_name, R.string.intro_example_1_body, MessageLabel.PERSONAL),
        Triple(R.string.intro_example_2_name, R.string.intro_example_2_body, MessageLabel.OTP_2FA),
        Triple(R.string.intro_example_3_name, R.string.intro_example_3_body, MessageLabel.PROMO),
        Triple(R.string.intro_example_4_name, R.string.intro_example_4_body, MessageLabel.SCAM),
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.intro_example_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            rows.forEachIndexed { i, (nameId, bodyId, label) ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val name = stringResource(nameId)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactAvatar(name = if (label == MessageLabel.PERSONAL) name else null, address = name, label = label, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(stringResource(bodyId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        // Under the text, not beside it: beside it the chip starves the message at large font sizes.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabelChip(label)
                            if (label == MessageLabel.OTP_2FA) {
                                // Inert: the real chip is in the conversation. A button here would be a false affordance.
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.intro_example_copy_code), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.intro_example_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Shown once, right after the copy finishes: what happened to the inbox and three things worth trying. */
@Composable
fun FirstLookCard(summaryText: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.firstlook_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.semantics { heading() })
            }
            val c = MaterialTheme.colorScheme.onTertiaryContainer
            Text(stringResource(R.string.firstlook_summary, summaryText), style = MaterialTheme.typography.bodyMedium, color = c)
            Text(stringResource(R.string.firstlook_try_title), style = MaterialTheme.typography.labelLarge, color = c, modifier = Modifier.padding(top = 4.dp))
            listOf(R.string.firstlook_try_codes, R.string.firstlook_try_junk, R.string.firstlook_try_swipe).forEach {
                Text("• " + stringResource(it), style = MaterialTheme.typography.bodySmall, color = c)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(stringResource(R.string.firstlook_dismiss)) }
        }
    }
}

/** The one warning that cannot be dismissed: a default SMS app with notifications off silently loses the user's texts. */
@Composable
fun NotificationsOffCard(opensSettings: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.notif_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.notif_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (opensSettings) R.string.notif_open_settings else R.string.notif_enable))
            }
        }
    }
}

/** Debug builds only: the on-device counters of the first-run steps. Step names and times; nothing about messages. */
@Composable
fun SetupLogScreen(entries: List<com.inboxiq.app.firstrun.FunnelEntry>, onClear: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to settings")
            }
            Text(stringResource(R.string.funnel_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Counts of first-run steps on this phone. Step names and times only: no message text, numbers or names. Nothing is sent anywhere, and release builds keep no such log.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US) }
            entries.forEach { e ->
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(e.step.label + ": " + (if (e.count == 0) "not reached" else "${e.count}x"), style = MaterialTheme.typography.bodyMedium)
                    if (e.count > 0) Text("first " + fmt.format(java.util.Date(e.firstAt)) + ", last " + fmt.format(java.util.Date(e.lastAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp)) { Text("Clear log") }
    }
}
