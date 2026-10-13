package com.inboxiq.app.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class) // skip InboxIqApp (WorkManager init)
class RelayConfigConsentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        context.getSharedPreferences("relay_config", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `relay is off by default`() {
        assertFalse(RelayConfig.isEnabled(context))
    }

    @Test
    fun `enabled flag alone is not enough without consent - covers upgrades from builds with no disclosure`() {
        RelayConfig.setEnabled(context, true)
        assertFalse(RelayConfig.hasConsent(context))
        assertFalse(RelayConfig.isEnabled(context))
    }

    @Test
    fun `enabled plus consent is on`() {
        RelayConfig.recordConsent(context)
        RelayConfig.setEnabled(context, true)
        assertTrue(RelayConfig.isEnabled(context))
    }

    @Test
    fun `disable turns relay off and rotates the relay token`() {
        RelayConfig.recordConsent(context)
        RelayConfig.setEnabled(context, true)
        val before = RelayConfig.relayToken(context)
        RelayConfig.disable(context)
        assertFalse(RelayConfig.isEnabled(context))
        assertNotEquals(before, RelayConfig.relayToken(context))
    }

    @Test
    fun `clearing consent also turns the relay off`() {
        RelayConfig.recordConsent(context)
        RelayConfig.setEnabled(context, true)
        RelayConfig.clearConsent(context)
        assertEquals(false, RelayConfig.isEnabled(context))
    }

    @Test
    fun `consent given under version 1 (0_4_x, background service) no longer counts`() {
        context.getSharedPreferences("relay_config", Context.MODE_PRIVATE).edit()
            .putInt("consent_version", 1).putBoolean("enabled", true).commit()
        assertTrue(RelayConfig.CONSENT_VERSION >= 2)
        assertFalse(RelayConfig.hasConsent(context))
        assertFalse(RelayConfig.isEnabled(context))
    }

    @Test
    fun `canConnect needs enabled, consent, a linked phone and no pending reconnect`() {
        RelayConfig.recordConsent(context)
        RelayConfig.setEnabled(context, true)
        assertFalse(RelayConfig.canConnect(context)) // no phone yet
        RelayConfig.setPhoneNumber(context, "+14155551234")
        assertTrue(RelayConfig.canConnect(context))
        RelayConfig.markReconnectNeeded(context)
        assertFalse(RelayConfig.canConnect(context))
        RelayConfig.clearReconnectNeeded(context)
        assertTrue(RelayConfig.canConnect(context))
    }

    @Test
    fun `disable clears the reconnect-needed flag`() {
        RelayConfig.markReconnectNeeded(context)
        RelayConfig.disable(context)
        assertFalse(RelayConfig.needsReconnect(context))
    }

    @Test
    fun `consent given under version 2 no longer counts - the link flow now places a voice call`() {
        context.getSharedPreferences("relay_config", Context.MODE_PRIVATE).edit()
            .putInt("consent_version", 2).putBoolean("enabled", true).commit()
        assertEquals(3, RelayConfig.CONSENT_VERSION)
        assertFalse(RelayConfig.hasConsent(context))
        assertFalse(RelayConfig.isEnabled(context))
    }

    @Test
    fun `replaced-elsewhere flag needs reconnect and is cleared by re-verification and by disable`() {
        RelayConfig.markReplaced(context)
        assertTrue(RelayConfig.needsReconnect(context)); assertTrue(RelayConfig.replacedElsewhere(context))
        RelayConfig.clearReconnectNeeded(context)
        assertFalse(RelayConfig.needsReconnect(context)); assertFalse(RelayConfig.replacedElsewhere(context))
        RelayConfig.markReplaced(context); RelayConfig.disable(context)
        assertFalse(RelayConfig.replacedElsewhere(context))
    }

    @Test
    fun `existingRelayToken never creates a token`() {
        assertEquals(null, RelayConfig.existingRelayToken(context))
        val t = RelayConfig.relayToken(context)
        assertEquals(t, RelayConfig.existingRelayToken(context))
    }
}
