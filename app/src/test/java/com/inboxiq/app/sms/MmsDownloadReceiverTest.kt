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

    // ---- the whole result handling, including reading the downloaded file ----

    private val syncs = ArrayList<Unit>()
    private lateinit var fake: FakeMmsStore

    private fun setUpProvider() {
        fake = FakeMmsStore(context).also { it.register() }
        fake.notificationRows[50L] = android.content.ContentValues().apply {
            put(android.provider.Telephony.Mms.DATE, 1_700_000_555L)
            put(android.provider.Telephony.Mms.TRANSACTION_ID, "TX")
        }
    }

    private fun run(id: Long, code: Int, bytes: ByteArray?, name: String = "dl_${id}_1_x.pdu"): Pair<Outcome, java.io.File> {
        val file = MmsDownloadFiles.fileFor(context, name)
        if (bytes != null) file.writeBytes(bytes)
        store.recordAttempt(id, now)
        val outcome = MmsDownloadReceiver.process(
            context, id, name, code, 0, store, { _, _ -> }, { i, d -> retries += i to d }, { syncs += Unit }, now, { events += it },
        )
        return outcome to file
    }

    @Test
    fun `RESULT_OK with a good PDU stores the message, removes the file, and runs the sync`() {
        setUpProvider()
        val (outcome, file) = run(50L, Activity.RESULT_OK, MmsTestPdus.retrieveConf())

        assertEquals(Outcome.SUCCESS, outcome)
        assertEquals(1, fake.messageInserts().size)
        assertTrue(fake.deletes.any { it.toString() == "content://mms/50" })
        assertFalse(file.exists())
        assertEquals(1, syncs.size)
        assertTrue(retries.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `RESULT_OK with bytes that are not an MMS is a permanent parse failure, reported once, not retried`() {
        setUpProvider()
        val (outcome, file) = run(50L, Activity.RESULT_OK, ByteArray(40) { 9 })

        assertEquals(Outcome.PERMANENT, outcome)
        assertTrue(retries.isEmpty())
        val e = events.single()
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_PERMANENT, e.kind)
        assertEquals("PDU_PARSE_FAILED", e.codeName)
        assertTrue(fake.inserts.isEmpty())
        assertFalse(file.exists())
        assertEquals(1, syncs.size)
    }

    @Test
    fun `RESULT_OK with an empty file is transient and retried`() {
        setUpProvider()
        val (outcome, _) = run(50L, Activity.RESULT_OK, null)

        assertEquals(Outcome.TRANSIENT, outcome)
        assertEquals(listOf(50L to MmsDownloadPolicy.delayMillis(1)), retries)
        assertEquals(MmsDownloadPolicy.CODE_EMPTY_DOWNLOAD, store.entry(50L)?.lastCode)
    }

    @Test
    fun `RESULT_OK but the system store refuses the message is transient and keeps the notification row`() {
        setUpProvider()
        fake.failInserts = true
        val (outcome, _) = run(50L, Activity.RESULT_OK, MmsTestPdus.retrieveConf())

        assertEquals(Outcome.TRANSIENT, outcome)
        assertEquals(1, retries.size)
        assertTrue("content://mms/50" !in fake.deletes.map { it.toString() })
        assertEquals(MmsDownloadPolicy.CODE_PERSIST_FAILED, store.entry(50L)?.lastCode)
    }

    @Test
    fun `an MMS_ERROR result never reads the file, removes it, and retries`() {
        setUpProvider()
        val (outcome, file) = run(50L, SmsManager.MMS_ERROR_NO_DATA_NETWORK, MmsTestPdus.retrieveConf())

        assertEquals(Outcome.TRANSIENT, outcome)
        assertTrue(fake.inserts.isEmpty())
        assertFalse(file.exists())
        assertEquals(1, retries.size)
    }

    @Test
    fun `a bad file name in the broadcast is ignored, not followed`() {
        setUpProvider()
        val outcome = MmsDownloadReceiver.process(
            context, 50L, "../../etc/passwd", Activity.RESULT_OK, 0, store, { _, _ -> }, { i, d -> retries += i to d }, { syncs += Unit }, now, { events += it },
        )
        assertEquals(Outcome.SUCCESS, outcome) // legacy path: no file, nothing stored
        assertTrue(fake.inserts.isEmpty())
    }
}
