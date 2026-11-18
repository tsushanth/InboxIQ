package com.inboxiq.app.sms

import android.app.Activity
import android.content.Context
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.sms.MmsDownloadPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What happens with each real download outcome: logged by id, recorded, retried with backoff or given up on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsDownloadReceiverTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = MmsDownloadStore(context)
    private val now = 5_000_000L
    private val retries = mutableListOf<Pair<Long, Long>>()
    private val events = mutableListOf<MmsDownloadReport.Event>()

    private fun handle(id: Long, code: Int, http: Int = 0, at: Long = now) = MmsDownloadReceiver.handleResult(
        context, id, code, http, store, { i, d -> retries += i to d }, at, { events += it },
    )

    @Test
    fun `a transient failure schedules a retry with the policy backoff and records the code`() {
        store.recordAttempt(40L, now)
        assertEquals(Outcome.TRANSIENT, handle(40L, SmsManager.MMS_ERROR_NO_DATA_NETWORK))

        assertEquals(listOf(40L to MmsDownloadPolicy.delayMillis(1)), retries)
        assertEquals(SmsManager.MMS_ERROR_NO_DATA_NETWORK, store.entry(40L)?.lastCode)
        assertEquals(now + MmsDownloadPolicy.delayMillis(1), store.notBefore(40L))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the backoff grows with each attempt`() {
        repeat(3) { store.recordAttempt(41L, now) }
        handle(41L, SmsManager.MMS_ERROR_IO_ERROR)
        assertEquals(MmsDownloadPolicy.delayMillis(3), retries.single().second)
    }

    @Test
    fun `a permanent error stops without a retry and reports once`() {
        store.recordAttempt(42L, now)
        assertEquals(Outcome.PERMANENT, handle(42L, SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER))
        handle(42L, SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER)

        assertTrue(retries.isEmpty())
        val e = events.single()
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_PERMANENT, e.kind)
        assertEquals("MMS_ERROR_MMS_DISABLED_BY_CARRIER", e.codeName)
        assertEquals(1, e.attempts)
        assertFalse(e.imageArrived)
    }

    @Test
    fun `a transient failure on the last attempt gives up as exhausted instead of retrying`() {
        repeat(MmsDownloadPolicy.MAX_ATTEMPTS) { store.recordAttempt(43L, now) }
        handle(43L, SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS)

        assertTrue(retries.isEmpty())
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, events.single().kind)
        assertEquals(MmsDownloadPolicy.MAX_ATTEMPTS, events.single().attempts)
    }

    @Test
    fun `no retry past the carrier's expiry`() {
        store.recordAttempt(44L, now)
        handle(44L, SmsManager.MMS_ERROR_IO_ERROR, at = now + MmsDownloadPolicy.MAX_AGE_MS + 1)
        assertTrue(retries.isEmpty())
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, events.single().kind)
    }

    @Test
    fun `success schedules nothing, reports nothing, and holds off a re-download for a few minutes`() {
        store.recordAttempt(45L, now)
        assertEquals(Outcome.SUCCESS, handle(45L, Activity.RESULT_OK))

        assertTrue(retries.isEmpty())
        assertTrue(events.isEmpty())
        assertTrue(store.notBefore(45L) >= now + MmsDownloadPolicy.SUCCESS_RECHECK_MS)
    }

    @Test
    fun `a result with no row id is classified but tracks nothing`() {
        assertEquals(Outcome.TRANSIENT, handle(-1L, SmsManager.MMS_ERROR_IO_ERROR))
        assertTrue(retries.isEmpty())
        assertTrue(store.knownIds().isEmpty())
    }
}
