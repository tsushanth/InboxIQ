package com.inboxiq.app.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** The self-healing pass: which undownloaded rows are worth another download right now. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsDownloadSchedulerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 9_000_000_000L

    /** id -> (message_type, content_location, date in seconds) */
    private val rows = mutableMapOf<Long, Triple<Int, String?, Long>>()

    private fun registerProvider() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val cols = projection ?: arrayOf(Telephony.Mms._ID)
                val cursor = MatrixCursor(cols)
                val wantType = if (selection == "${Telephony.Mms.MESSAGE_TYPE} = ?") selectionArgs?.get(0)?.toIntOrNull() else null
                for ((id, row) in rows) {
                    if (wantType != null && row.first != wantType) continue
                    cursor.addRow(cols.map { col ->
                        when (col) {
                            Telephony.Mms._ID -> id
                            Telephony.Mms.CONTENT_LOCATION -> row.second
                            Telephony.Mms.MESSAGE_TYPE -> row.first
                            Telephony.Mms.DATE -> row.third
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

    private fun heal(store: MmsDownloadStore): Pair<Int, Int> {
        var enqueued = 0
        val eligible = MmsDownloadScheduler.healPending(context, now, store) { enqueued++ }
        return eligible to enqueued
    }

    @Test
    fun `queues a pass when a notification row is still undownloaded`() {
        registerProvider()
        rows[1L] = Triple(0x82, "http://mmsc.example.com/1", now / 1000)
        assertEquals(1 to 1, heal(MmsDownloadStore(context)))
    }

    @Test
    fun `queues nothing when nothing is pending or the rows are real messages`() {
        registerProvider()
        rows[1L] = Triple(0x84, null, now / 1000) // retrieve-conf: already downloaded
        assertEquals(0 to 0, heal(MmsDownloadStore(context)))
    }

    @Test
    fun `skips rows that are running, backing off, or out of attempts`() {
        registerProvider()
        rows[1L] = Triple(0x82, "http://x/1", now / 1000)
        rows[2L] = Triple(0x82, "http://x/2", now / 1000)
        rows[3L] = Triple(0x82, "http://x/3", now / 1000)
        val store = MmsDownloadStore(context)
        store.recordAttempt(1L, now - 1_000) // still in flight
        store.recordAttempt(2L, now - 600_000); store.recordResult(2L, 5, notBefore = now + 60_000) // backing off
        repeat(MmsDownloadPolicy.MAX_ATTEMPTS) { store.recordAttempt(3L, now - 600_000) }; store.recordResult(3L, 5)

        assertEquals(0 to 0, heal(store))

        // The backoff passes: row 2 is eligible again, 1 and 3 still are not.
        val later = MmsDownloadScheduler.eligible(2L, now, store, now + 61_000)
        assertTrue(later)
        assertFalse(MmsDownloadScheduler.eligible(3L, now, store, now + 61_000))
    }

    @Test
    fun `a notification row older than the carrier link lives is not retried`() {
        registerProvider()
        rows[1L] = Triple(0x82, "http://x/1", (now - MmsDownloadPolicy.MAX_AGE_MS - 5_000) / 1000)
        assertEquals(0 to 0, heal(MmsDownloadStore(context)))
    }

    @Test
    fun `finds the pending row for a placeholder by its time`() {
        registerProvider()
        rows[8L] = Triple(0x82, "http://x/8", 1_700_000_123L)
        assertEquals(8L, MmsPending.idForTimestamp(context.contentResolver, 1_700_000_123_000L))
        assertNull(MmsPending.idForTimestamp(context.contentResolver, 1_700_000_999_000L))
    }
}
