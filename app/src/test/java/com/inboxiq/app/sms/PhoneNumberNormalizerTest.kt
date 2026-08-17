package com.inboxiq.app.sms

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTelephonyManager

/**
 * Regression coverage for the thread-splitting bug: a contact-picked or platform-backfilled
 * address without a country code (e.g. "8136000258") created a second, permanently-separate
 * thread from the same contact's E.164-normalized history ("+18136000258") — confirmed live,
 * merged via a one-time migration, but the address had to be normalized at every ingestion
 * point going forward or backfill would keep resurrecting the split on every launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PhoneNumberNormalizerTest {

    private fun setNetworkCountryIso(iso: String) {
        val telephonyManager = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
        Shadows.shadowOf(telephonyManager).setNetworkCountryIso(iso)
    }

    @Test
    fun `a bare 10-digit US number gets a plus-one country code`() {
        setNetworkCountryIso("us")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("+18136000258", PhoneNumberNormalizer.normalize(context, "8136000258"))
    }

    @Test
    fun `an already-normalized E164 number is left exactly as-is`() {
        setNetworkCountryIso("us")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("+18136000258", PhoneNumberNormalizer.normalize(context, "+18136000258"))
    }

    @Test
    fun `a short code passes through unchanged rather than being mangled`() {
        setNetworkCountryIso("us")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Short codes (5-6 digits, e.g. bank/verification senders) aren't real phone numbers —
        // formatNumberToE164 can't format them, and normalize() must fall back to the raw
        // value rather than dropping or corrupting the sender address.
        assertEquals("22395", PhoneNumberNormalizer.normalize(context, "22395"))
    }

    @Test
    fun `a non-numeric sender id passes through unchanged`() {
        setNetworkCountryIso("us")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertEquals("AMAZON", PhoneNumberNormalizer.normalize(context, "AMAZON"))
    }

    @Test
    fun `falls back to device locale when no SIM country is available`() {
        // No SIM (WiFi-only device, airplane mode) — networkCountryIso is blank. The bug this
        // guards against: silently normalizing against the wrong country code (or crashing)
        // instead of falling back to Locale.getDefault().country.
        setNetworkCountryIso("")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val result = PhoneNumberNormalizer.normalize(context, "8136000258")
        // Whatever the test JVM's default locale is, this must not crash and must not silently
        // return the un-normalized raw input for a plausible domestic-shaped number.
        assert(result.startsWith("+")) { "Expected a normalized E.164 number, got: $result" }
    }
}
