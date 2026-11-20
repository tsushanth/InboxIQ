package com.inboxiq.app.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Regression for the duplicate-conversation bug seen on 0.6.1: a number typed on the New message screen was used as
 * the thread address as typed ("2245061194"), while the platform provider stored the same conversation under
 * "+12245061194", so the inbox showed two threads for one person. Picked contacts were already normalized; typed
 * numbers were not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NewConversationAddressTest {

    private fun context(iso: String = "us"): android.content.Context {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val telephony = context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        Shadows.shadowOf(telephony).setNetworkCountryIso(iso)
        return context
    }

    @Test
    fun `a typed 10-digit number is keyed the same way as its E164 form`() {
        assertEquals("+12245061194", newConversationAddress(context(), "2245061194"))
        assertEquals(
            newConversationAddress(context(), "+12245061194"),
            newConversationAddress(context(), "2245061194"),
        )
    }

    @Test
    fun `punctuation and surrounding spaces do not change the address`() {
        val expected = "+18136000258"
        assertEquals(expected, newConversationAddress(context(), "(813) 600-0258"))
        assertEquals(expected, newConversationAddress(context(), "  813-600-0258 "))
        assertEquals(expected, newConversationAddress(context(), "813 600 0258"))
        assertEquals(expected, newConversationAddress(context(), "+1 813 600 0258"))
    }

    @Test
    fun `an already-normalized number is unchanged`() {
        assertEquals("+18136000258", newConversationAddress(context(), "+18136000258"))
    }

    @Test
    fun `a short code is kept as typed instead of being mangled`() {
        assertEquals("22395", newConversationAddress(context(), "22395"))
    }
}
