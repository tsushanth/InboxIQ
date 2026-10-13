package com.inboxiq.app.feature

import android.app.Application
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inboxiq.app.R
import com.inboxiq.app.mcp.AgentDraftsSection
import com.inboxiq.app.mcp.CloudConnectSection
import com.inboxiq.app.mcp.RelayRuntime

/** `relay` variant: the optional, consent-gated, foreground-only Connect to Claude relay. */
object RelayFeatures {
    val instance: RelayFeature = object : RelayFeature {
        override fun onAppStart(app: Application) {
            // Relay connects only while the app is visible (ProcessLifecycleOwner); see RelayRuntime.
            RelayRuntime.install(app)
        }

        @Composable
        override fun SettingsSection() {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
            CloudConnectSection()
            AgentDraftsSection()
        }

        @Composable
        override fun introExceptionText(): String = stringResource(R.string.intro_relay_exception)
    }
}
