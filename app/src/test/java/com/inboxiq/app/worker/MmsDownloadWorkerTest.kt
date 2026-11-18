package com.inboxiq.app.worker

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.sms.MmsDownloadPolicy
import com.inboxiq.app.sms.MmsDownloadReport
import com.inboxiq.app.sms.MmsDownloadStore
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Regression coverage for the actual, confirmed-live bug: this worker was querying
 * content://mms for notification-indication rows that nothing ever wrote there, so it
 * silently found zero pending downloads on every real incoming MMS, indefinitely, with no
 * error and no log output. Robolectric has no real Telephony content provider (a known,
 * long-standing gap — robolectric/robolectric#3005), so this registers a minimal fake one
 * backed by an in-memory row set, standing in for "the platform's own content://mms table
 * after MmsReceiver has persisted a notification-indication row into it."
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsDownloadWorkerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** id -> (msgBox row's message_type, content_location) */
    private val rows = mutableMapOf<Long, Pair<Int, String?>>()

    private fun registerFakeMmsProvider() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val cols = projection ?: arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.MESSAGE_TYPE)
                val cursor = MatrixCursor(cols)
                val wantType = if (selection == "${Telephony.Mms.MESSAGE_TYPE} = ?") selectionArgs?.get(0)?.toIntOrNull() else null
                for ((id, row) in rows) {
                    val (type, location) = row
                    if (wantType != null && type != wantType) continue
                    cursor.addRow(cols.map { col ->
                        when (col) {
                            Telephony.Mms._ID -> id
                            Telephony.Mms.CONTENT_LOCATION -> location
                            Telephony.Mms.MESSAGE_TYPE -> type
                            else -> null
                        }
                    })
                }
                return cursor
            }
            override fun insert(uri: Uri, values: ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri): String? = null
        }
        ShadowContentResolver.registerProviderInternal("mms", provider)
    }

    private var now = 1_000_000_000_000L
    private val events = mutableListOf<MmsDownloadReport.Event>()
    private var isDefault = true

    private fun buildWorker(
        inputId: Long? = null,
        trigger: (Context, String, Uri, PendingIntent) -> Unit,
    ): MmsDownloadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                return MmsDownloadWorker(appContext, workerParameters, trigger, { now }, { events += it }, { isDefault })
            }
        }
        return TestListenableWorkerBuilder<MmsDownloadWorker>(context)
            .setWorkerFactory(factory)
            .apply { if (inputId != null) setInputData(workDataOf(MmsDownloadWorker.KEY_ID to inputId)) }
            .build()
    }

    @Test
    fun `triggers a download for a pending notification-indication row`() = runTest {
        registerFakeMmsProvider()
        rows[42L] = 0x82 to "http://mmsc.example.com/msg?id=42" // MESSAGE_TYPE_NOTIFICATION_IND

        val triggered = mutableListOf<Pair<Long, String>>()
        val worker = buildWorker { _, location, msgUri, _ ->
            triggered.add(ContentUris.parseId(msgUri) to location)
        }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(42L to "http://mmsc.example.com/msg?id=42"), triggered)
    }

    @Test
    fun `does not trigger anything when content-mms has no pending rows — the exact bug this fix addresses`() = runTest {
        registerFakeMmsProvider()
        // Deliberately empty — this is what content://mms looked like on every real device
        // during tonight's live testing, for every incoming MMS, before MmsReceiver was
        // fixed to actually parse and persist the WAP push itself.

        var triggerCount = 0
        val worker = buildWorker { _, _, _, _ -> triggerCount++ }

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, triggerCount)
    }

    @Test
    fun `ignores rows that are not notification-indications`() = runTest {
        registerFakeMmsProvider()
        rows[1L] = 0x80 to "http://should-not-download.example.com" // MESSAGE_TYPE_SEND_REQ — our own outgoing mail
        rows[2L] = 0x84 to "http://should-not-download.example.com" // MESSAGE_TYPE_RETRIEVE_CONF — already downloaded

        var triggerCount = 0
        val worker = buildWorker { _, _, _, _ -> triggerCount++ }

        worker.doWork()

        assertEquals(0, triggerCount)
    }

    @Test
    fun `a download-trigger exception for one row does not stop the others from being attempted`() = runTest {
        registerFakeMmsProvider()
        rows[1L] = 0x82 to "http://mmsc.example.com/msg?id=1"
        rows[2L] = 0x82 to "http://mmsc.example.com/msg?id=2"

        val attempted = mutableListOf<Long>()
        val worker = buildWorker { _, _, msgUri, _ ->
            val id = ContentUris.parseId(msgUri)
            attempted.add(id)
            if (id == 1L) throw IllegalStateException("simulated downloadMultimediaMessage failure")
        }

        val result = worker.doWork()

        // A failed trigger asks WorkManager for another go (with backoff); the other row was still attempted.
        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(listOf(1L, 2L), attempted.sorted())
    }

    @Test
    fun `a row that is already being downloaded is not triggered again`() = runTest {
        registerFakeMmsProvider()
        rows[7L] = 0x82 to "http://mmsc.example.com/msg?id=7"
        var triggerCount = 0

        assertTrue(buildWorker { _, _, _, _ -> triggerCount++ }.doWork() is ListenableWorker.Result.Success)
        assertEquals(1, triggerCount)

        // A second pass moments later (the periodic one, a sync heal) must not fetch the same message again.
        now += 10_000
        buildWorker { _, _, _, _ -> triggerCount++ }.doWork()
        assertEquals(1, triggerCount)

        // Once the in-flight window has passed and the receiver reported nothing, it may be tried again.
        now += MmsDownloadPolicy.IN_FLIGHT_MS + 1
        buildWorker { _, _, _, _ -> triggerCount++ }.doWork()
        assertEquals(2, triggerCount)
    }

    @Test
    fun `counts attempts and stops with one report after the attempt cap`() = runTest {
        registerFakeMmsProvider()
        rows[9L] = 0x82 to "http://mmsc.example.com/msg?id=9"
        val store = MmsDownloadStore(context)
        var triggerCount = 0

        repeat(MmsDownloadPolicy.MAX_ATTEMPTS + 3) {
            now += MmsDownloadPolicy.IN_FLIGHT_MS + 1
            buildWorker { _, _, _, _ -> triggerCount++ }.doWork()
        }

        assertEquals(MmsDownloadPolicy.MAX_ATTEMPTS, triggerCount)
        assertEquals(MmsDownloadPolicy.MAX_ATTEMPTS, store.attempts(9L))
        assertEquals(1, events.size)
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, events[0].kind)
        assertEquals(9L, events[0].id)
        assertEquals(MmsDownloadPolicy.MAX_ATTEMPTS, events[0].attempts)
        assertEquals(false, events[0].imageArrived)
    }

    @Test
    fun `a trigger that keeps throwing stops asking for retries at the cap`() = runTest {
        registerFakeMmsProvider()
        rows[3L] = 0x82 to "http://mmsc.example.com/msg?id=3"
        var last: ListenableWorker.Result? = null
        repeat(MmsDownloadPolicy.MAX_ATTEMPTS + 1) {
            now += 1_000
            last = buildWorker { _, _, _, _ -> throw IllegalStateException("simulated") }.doWork()
        }
        // The last of the attempts-with-trigger already hit the cap: no more Retry, so WorkManager stops.
        assertTrue(last is ListenableWorker.Result.Success)
    }

    @Test
    fun `does not download a row older than the carrier link lives`() = runTest {
        registerFakeMmsProvider()
        rows[5L] = 0x82 to "http://mmsc.example.com/msg?id=5"
        val store = MmsDownloadStore(context)
        store.recordAttempt(5L, now) // first seen now
        store.recordResult(5L, -1)
        now += MmsDownloadPolicy.MAX_AGE_MS + 1
        var triggerCount = 0

        buildWorker { _, _, _, _ -> triggerCount++ }.doWork()

        assertEquals(0, triggerCount)
        assertEquals(MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, events.single().kind)
    }

    @Test
    fun `a row backing off after a failure is skipped by the all-rows pass but a scheduled retry for it runs`() = runTest {
        registerFakeMmsProvider()
        rows[11L] = 0x82 to "http://mmsc.example.com/msg?id=11"
        val store = MmsDownloadStore(context)
        store.recordAttempt(11L, now)
        store.recordResult(11L, android.telephony.SmsManager.MMS_ERROR_NO_DATA_NETWORK, notBefore = now + 60_000)
        var triggerCount = 0

        buildWorker { _, _, _, _ -> triggerCount++ }.doWork()
        assertEquals(0, triggerCount)

        now += 61_000
        buildWorker(inputId = 11L) { _, _, _, _ -> triggerCount++ }.doWork()
        assertEquals(1, triggerCount)
    }

    @Test
    fun `an id input downloads only that row`() = runTest {
        registerFakeMmsProvider()
        rows[1L] = 0x82 to "http://mmsc.example.com/msg?id=1"
        rows[2L] = 0x82 to "http://mmsc.example.com/msg?id=2"
        val attempted = mutableListOf<Long>()

        buildWorker(inputId = 2L) { _, _, msgUri, _ -> attempted += ContentUris.parseId(msgUri) }.doWork()

        assertEquals(listOf(2L), attempted)
    }

    @Test
    fun `does nothing when this app is not the default SMS app`() = runTest {
        registerFakeMmsProvider()
        rows[1L] = 0x82 to "http://mmsc.example.com/msg?id=1"
        isDefault = false
        var triggerCount = 0

        val result = buildWorker { _, _, _, _ -> triggerCount++ }.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, triggerCount)
    }

    @Test
    fun `a record for a row that is no longer pending is reported as resolved when it needed a retry, then forgotten`() = runTest {
        registerFakeMmsProvider() // empty: row 21 was turned into a real message by the platform
        val store = MmsDownloadStore(context)
        store.recordAttempt(21L, now)
        store.recordAttempt(21L, now + 1)

        buildWorker { _, _, _, _ -> }.doWork()

        val e = events.single()
        assertEquals(MmsDownloadReport.Kind.RESOLVED_AFTER_RETRY, e.kind)
        assertEquals(2, e.attempts)
        assertEquals(null, store.entry(21L))
    }
}
