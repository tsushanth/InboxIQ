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

@RunWith(RobolectricTestRunner::class)
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
}
