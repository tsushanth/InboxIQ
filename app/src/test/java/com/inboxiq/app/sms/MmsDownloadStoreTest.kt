package com.inboxiq.app.sms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsDownloadStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `counts attempts per provider id and survives a new store instance`() {
        val a = MmsDownloadStore(context)
        assertEquals(1, a.recordAttempt(1L, 100))
        assertEquals(2, a.recordAttempt(1L, 200))
        assertEquals(1, a.recordAttempt(2L, 300))

        val b = MmsDownloadStore(context)
        assertEquals(2, b.attempts(1L))
        assertEquals(1, b.attempts(2L))
        assertEquals(100L, b.firstSeenAt(1L, 999))
        assertEquals(setOf(1L, 2L), b.knownIds())
    }

    @Test
    fun `in-flight lasts the policy window and ends on a result`() {
        val s = MmsDownloadStore(context)
        s.recordAttempt(1L, 1_000)
        assertTrue(s.isInFlight(1L, 1_000 + MmsDownloadPolicy.IN_FLIGHT_MS - 1))
        assertFalse(s.isInFlight(1L, 1_000 + MmsDownloadPolicy.IN_FLIGHT_MS))
        s.recordAttempt(1L, 2_000)
        s.recordResult(1L, 5, notBefore = 9_000)
        assertFalse(s.isInFlight(1L, 2_001))
        assertEquals(9_000L, s.notBefore(1L))
    }

    @Test
    fun `a new attempt clears the backoff and a reset clears the count but keeps the first-seen time`() {
        val s = MmsDownloadStore(context)
        s.recordAttempt(1L, 100)
        s.recordResult(1L, 3, notBefore = 5_000)
        s.recordAttempt(1L, 6_000)
        assertEquals(0L, s.notBefore(1L))
        s.markGaveUpReported(1L)
        s.resetAttempts(1L)
        assertEquals(0, s.attempts(1L))
        assertFalse(s.gaveUpReported(1L))
        assertEquals(100L, s.firstSeenAt(1L, 999))
    }

    @Test
    fun `an old first-seen time is kept when the row is older than the first attempt`() {
        val s = MmsDownloadStore(context)
        s.recordAttempt(1L, now = 10_000, firstSeenAt = 4_000)
        assertEquals(4_000L, s.firstSeenAt(1L, 999))
    }

    @Test
    fun `forget removes everything about an id`() {
        val s = MmsDownloadStore(context)
        s.recordAttempt(1L, 100)
        s.forget(1L)
        assertNull(s.entry(1L))
        assertTrue(s.knownIds().isEmpty())
    }
}
