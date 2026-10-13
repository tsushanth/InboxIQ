package com.inboxiq.app.feature

import android.app.Application
import androidx.compose.runtime.Composable

/**
 * The only seam between the core app and the optional "Connect to Claude" relay.
 *
 * The core app (SMS/MMS, on-device sorting, quick replies) depends on nothing but this
 * interface. [RelayFeatures] is declared once per product flavor: the `core` source set
 * provides a no-op, the `relay` source set provides the real thing. See
 * docs/ARCHITECTURE_RELAY_OPTIONAL.md.
 */
interface RelayFeature {
    /** Called from Application.onCreate. */
    fun onAppStart(app: Application)

    /** Settings content shown below the Privacy section. Renders nothing in core. */
    @Composable
    fun SettingsSection()

    /** Extra paragraph for the first-run intro, or null when the variant has no relay. */
    @Composable
    fun introExceptionText(): String?
}
