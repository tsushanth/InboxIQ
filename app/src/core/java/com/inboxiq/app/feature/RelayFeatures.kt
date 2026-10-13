package com.inboxiq.app.feature

import android.app.Application
import androidx.compose.runtime.Composable

/** `core` variant: no relay code, no network. Everything is a no-op. */
object RelayFeatures {
    val instance: RelayFeature = object : RelayFeature {
        override fun onAppStart(app: Application) = Unit

        @Composable
        override fun SettingsSection() = Unit

        @Composable
        override fun introExceptionText(): String? = null
    }
}
