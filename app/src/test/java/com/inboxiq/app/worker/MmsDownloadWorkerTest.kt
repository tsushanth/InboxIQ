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
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
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

    private fun buildWorker(trigger: (Context, String, Uri, PendingIntent) -> Unit): MmsDownloadWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                return MmsDownloadWorker(appContext, workerParameters, trigger)
            }
        }
        return TestListenableWorkerBuilder<MmsDownloadWorker>(context)
            .setWorkerFactory(factory)
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

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(1L, 2L), attempted.sorted())
    }
}
