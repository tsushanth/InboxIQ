package com.inboxiq.app.sms

import android.app.Activity
import android.telephony.SmsManager
import com.inboxiq.app.sms.MmsDownloadPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decisions that turn SmsManager's raw download result into "retry", "stop" or "done". Plain JVM. */
class MmsDownloadPolicyTest {

    @Test
    fun `RESULT_OK is success`() {
        assertEquals(Outcome.SUCCESS, MmsDownloadPolicy.classify(Activity.RESULT_OK))
    }

    @Test
    fun `network and carrier hiccups are transient`() {
        listOf(
            SmsManager.MMS_ERROR_UNSPECIFIED, SmsManager.MMS_ERROR_INVALID_APN, SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS,
            SmsManager.MMS_ERROR_HTTP_FAILURE, SmsManager.MMS_ERROR_IO_ERROR, SmsManager.MMS_ERROR_RETRY,
            SmsManager.MMS_ERROR_NO_DATA_NETWORK, SmsManager.MMS_ERROR_DATA_DISABLED,
        ).forEach { assertEquals(MmsDownloadPolicy.codeName(it), Outcome.TRANSIENT, MmsDownloadPolicy.classify(it)) }
    }

    @Test
    fun `errors no retry can fix are permanent`() {
        listOf(
            SmsManager.MMS_ERROR_CONFIGURATION_ERROR, SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID,
            SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION, SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER,
        ).forEach { assertEquals(MmsDownloadPolicy.codeName(it), Outcome.PERMANENT, MmsDownloadPolicy.classify(it)) }
    }

    @Test
    fun `an http failure with a gone link is permanent, any other http status stays transient`() {
        assertEquals(Outcome.PERMANENT, MmsDownloadPolicy.classify(SmsManager.MMS_ERROR_HTTP_FAILURE, 404))
        assertEquals(Outcome.PERMANENT, MmsDownloadPolicy.classify(SmsManager.MMS_ERROR_HTTP_FAILURE, 410))
        assertEquals(Outcome.TRANSIENT, MmsDownloadPolicy.classify(SmsManager.MMS_ERROR_HTTP_FAILURE, 503))
        assertEquals(Outcome.TRANSIENT, MmsDownloadPolicy.classify(SmsManager.MMS_ERROR_HTTP_FAILURE, 0))
    }

    @Test
    fun `an unknown code is transient and named by number`() {
        assertEquals(Outcome.TRANSIENT, MmsDownloadPolicy.classify(4242))
        assertEquals("UNKNOWN_4242", MmsDownloadPolicy.codeName(4242))
    }

    @Test
    fun `codes are named with the SDK names`() {
        assertEquals("RESULT_OK", MmsDownloadPolicy.codeName(Activity.RESULT_OK))
        assertEquals("MMS_ERROR_NO_DATA_NETWORK", MmsDownloadPolicy.codeName(SmsManager.MMS_ERROR_NO_DATA_NETWORK))
        assertEquals("MMS_ERROR_UNABLE_CONNECT_MMS", MmsDownloadPolicy.codeName(SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS))
    }

    @Test
    fun `backoff doubles from 30 seconds and is capped at 6 hours`() {
        assertEquals(30_000L, MmsDownloadPolicy.delayMillis(1))
        assertEquals(60_000L, MmsDownloadPolicy.delayMillis(2))
        assertEquals(120_000L, MmsDownloadPolicy.delayMillis(3))
        assertEquals(6L * 3600 * 1000, MmsDownloadPolicy.delayMillis(20))
        assertEquals(6L * 3600 * 1000, MmsDownloadPolicy.delayMillis(1000))
        assertEquals(30_000L, MmsDownloadPolicy.delayMillis(0))
    }

    @Test
    fun `the attempt cap spans the carrier's expiry window without passing it`() {
        val total = (1 until MmsDownloadPolicy.MAX_ATTEMPTS).sumOf { MmsDownloadPolicy.delayMillis(it) }
        assertTrue("retries should cover more than a day: $total", total > 24L * 3600 * 1000)
        assertTrue("retries must end inside the age cap: $total", total < MmsDownloadPolicy.MAX_AGE_MS)
    }

    @Test
    fun `retry is allowed until the cap or the age limit`() {
        val t0 = 1_000L
        assertTrue(MmsDownloadPolicy.mayRetry(0, t0, t0))
        assertTrue(MmsDownloadPolicy.mayRetry(MmsDownloadPolicy.MAX_ATTEMPTS - 1, t0, t0))
        assertFalse(MmsDownloadPolicy.mayRetry(MmsDownloadPolicy.MAX_ATTEMPTS, t0, t0))
        assertTrue(MmsDownloadPolicy.mayRetry(1, t0, t0 + MmsDownloadPolicy.MAX_AGE_MS - 1))
        assertFalse(MmsDownloadPolicy.mayRetry(1, t0, t0 + MmsDownloadPolicy.MAX_AGE_MS))
    }
}
