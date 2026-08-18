package com.inboxiq.app.sms

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * Regression coverage for the class of bug behind the real "[MMS message]" placeholder that
 * never healed: MmsSync.syncRecent() only ever called dao.insert() with
 * OnConflictStrategy.IGNORE against a unique index on (address, timestamp, body). That index
 * bakes payload (body) into the conflict key, not just identity (address+timestamp) — so a
 * second sync of the *same* platform MMS row, now with its image part downloaded (different
 * body/imagePartUri than the first, content-less sync), was never recognized as the same
 * message. It silently either inserted a permanent duplicate or — since MmsSync now looks the
 * row up first — must UPDATE the existing row in place. This is the general "eventually
 * consistent ingestion" bug class: any source that can be observed twice with different
 * payloads for the same real-world entity (MMS notification-then-download, webhook
 * stub-then-payload, upload-started-then-finished) needs an update path, not insert-only.
 *
 * Robolectric has no real Telephony content provider (robolectric/robolectric#3005), so this
 * registers a minimal fake content://mms backed by an in-memory row set — standing in for the
 * platform's own provider across two separate syncRecent() calls against the *same* underlying
 * MMS, first before its image part exists, then after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsSyncUpsertTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    private data class Msg(val date: Long, val msgBox: Int, val fromAddress: String)
    private data class Part(val mmsId: Long, val contentType: String, val text: String?)

    private val messages = mutableMapOf<Long, Msg>()
    private val parts = mutableListOf<Part>()

    private fun registerFakeMmsProvider() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true

            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                val segments = uri.pathSegments
                return when {
                    segments.size >= 2 && segments[1] == "addr" -> {
                        val mmsId = segments[0].toLong()
                        val cols = arrayOf("address", "type")
                        val cursor = MatrixCursor(cols)
                        messages[mmsId]?.let { cursor.addRow(arrayOf<Any?>(it.fromAddress, 137)) }
                        cursor
                    }
                    segments.size >= 2 && segments[1] == "part" -> {
                        val mmsId = segments[0].toLong()
                        val cols = arrayOf("_id", "ct", "text")
                        val cursor = MatrixCursor(cols)
                        parts.filter { it.mmsId == mmsId }.forEachIndexed { idx, part ->
                            cursor.addRow(arrayOf<Any?>(idx.toLong(), part.contentType, part.text))
                        }
                        cursor
                    }
                    else -> {
                        val cols = projection ?: arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
                        val cursor = MatrixCursor(cols)
                        for ((id, msg) in messages) {
                            cursor.addRow(cols.map { col ->
                                when (col) {
                                    Telephony.Mms._ID -> id
                                    Telephony.Mms.DATE -> msg.date
                                    Telephony.Mms.MESSAGE_BOX -> msg.msgBox
                                    else -> null
                                }
                            })
                        }
                        cursor
                    }
                }
            }

            override fun insert(uri: Uri, values: ContentValues?) = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun getType(uri: Uri): String? = null
        }
        ShadowContentResolver.registerProviderInternal("mms", provider)
    }

    @Before
    fun setUp() {
        registerFakeMmsProvider()
        db = AppDatabase.get(context)
        clearOffMainThread()
    }

    @After
    fun tearDown() {
        clearOffMainThread()
    }

    // AppDatabase.get() returns the same production builder the app uses (no
    // allowMainThreadQueries override, deliberately, so a real regression on the main thread
    // would still fail in production) — clearAllTables() must run off Robolectric's main thread
    // the same way the app's own coroutine dispatchers would in practice.
    private fun clearOffMainThread() {
        Thread { db.clearAllTables() }.apply { start(); join() }
    }

    @Test
    fun `re-syncing the same MMS after its image part downloads updates the placeholder row instead of duplicating it`() = runTest {
        val dateSeconds = 1786927353L
        messages[5515L] = Msg(date = dateSeconds, msgBox = Telephony.Mms.MESSAGE_BOX_INBOX, fromAddress = "+18136000258")
        // First sync: platform has the notification-indication row but no parts yet — exactly
        // what a real device shows before SmsManager.downloadMultimediaMessage() completes.

        MmsSync.syncRecent(context, 0L)

        val firstRows = db.messageDao().observeThread("+18136000258").first()
        assertEquals("expected exactly one placeholder row after the first, content-less sync", 1, firstRows.size)
        assertEquals("[MMS message]", firstRows.single().body)
        assertEquals(null, firstRows.single().imagePartUri)

        // Second sync: the same platform row (same address+timestamp), now with its image part
        // present — simulating the download finishing between syncs.
        parts.add(Part(mmsId = 5515L, contentType = "image/jpeg", text = null))

        MmsSync.syncRecent(context, 0L)

        val afterSecondSync = db.messageDao().observeThread("+18136000258").first()
        assertEquals(
            "the placeholder row must be updated in place, not duplicated, once the image part becomes available",
            1,
            afterSecondSync.size,
        )
        val healed = afterSecondSync.single()
        assertEquals("content://mms/part/0", healed.imagePartUri)
        assertEquals("", healed.body)
    }

    @Test
    fun `syncing a message that already has its image part on the first pass never creates a placeholder`() = runTest {
        val dateSeconds = 1786927400L
        messages[5516L] = Msg(date = dateSeconds, msgBox = Telephony.Mms.MESSAGE_BOX_INBOX, fromAddress = "+18136000258")
        parts.add(Part(mmsId = 5516L, contentType = "image/jpeg", text = null))

        MmsSync.syncRecent(context, 0L)

        val rows = db.messageDao().observeThread("+18136000258").first()
        assertEquals(1, rows.size)
        assertEquals("content://mms/part/0", rows.single().imagePartUri)
    }

    @Test
    fun `re-syncing an already-complete message does not create a second row`() = runTest {
        val dateSeconds = 1786927500L
        messages[5517L] = Msg(date = dateSeconds, msgBox = Telephony.Mms.MESSAGE_BOX_INBOX, fromAddress = "+18136000258")
        parts.add(Part(mmsId = 5517L, contentType = "image/jpeg", text = null))

        MmsSync.syncRecent(context, 0L)
        MmsSync.syncRecent(context, 0L) // a second full backfill pass, same as every app launch

        val rows = db.messageDao().observeThread("+18136000258").first()
        assertEquals(1, rows.size)
    }
}
