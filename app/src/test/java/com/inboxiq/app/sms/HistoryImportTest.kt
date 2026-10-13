package com.inboxiq.app.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Thrown as an Error so HistoryImport's `catch (Exception)` cannot swallow it: models the process dying mid-import. */
private class Killed : Error("killed")

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HistoryImportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var state: ImportState
    private lateinit var tombstones: ImportTombstones

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        state = ImportState(context.getSharedPreferences("test_import_state", Context.MODE_PRIVATE))
        tombstones = ImportTombstones(context.getSharedPreferences("test_import_tombstones", Context.MODE_PRIVATE))
        ImportStatusHolder.reset()
    }

    @After
    fun tearDown() = db.close()

    private fun env(batchParts: Boolean = true) =
        ImportEnv(context, context.contentResolver, db, tombstones, normalizer = { it }, batchParts = batchParts)

    private suspend fun run(
        e: ImportEnv = env(),
        s: ImportState = state,
        now: Long = 1_000_000_000L,
        free: Long = Long.MAX_VALUE,
        default: Boolean = true,
    ) = HistoryImport.run(e, s, now = { now }, freeBytes = { free }, isDefault = { default })

    @Test
    fun `imports 10000 sms and 5000 mms with correct counts inside the time budget`() = runTest {
        val fake = FakeTelephony.seed(10_000, 5_000).also { it.register() }
        val t0 = System.nanoTime()
        val outcome = run()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("IMPORT_FULL ms=$ms queries=${fake.queryCount}")

        assertTrue(outcome is HistoryImport.Outcome.Done)
        assertEquals(15_000, (outcome as HistoryImport.Outcome.Done).inserted)
        assertEquals(15_000, db.messageDao().count())
        // direction and content survive the paging
        val incomingSms = db.messageDao().messagesForThread(FakeTelephony.number(2))
        assertTrue(incomingSms.any { it.body == "sms body 2" && it.isIncoming })
        assertTrue("time budget", ms < 60_000)
        assertTrue(state.isBackfillComplete())
    }

    @Test
    fun `re-running imports nothing and reads almost nothing`() = runTest {
        val fake = FakeTelephony.seed(2_000, 500).also { it.register() }
        run()
        val q = fake.queryCount
        // Past the repair window so only the incremental path is measured.
        val outcome = run(now = 1_000_000_000L + 1000)
        assertEquals(0, (outcome as HistoryImport.Outcome.Done).inserted)
        assertEquals(2_500, db.messageDao().count())
        // incremental only: one query per source (+ none of the 500 per-message address queries)
        assertEquals(2, fake.queryCount - q)
    }

    @Test
    fun `new provider rows after completion import incrementally`() = runTest {
        val fake = FakeTelephony.seed(1_000, 100).also { it.register() }
        run()
        fake.sms += FakeSms(1_001, 1, FakeTelephony.number(1), "fresh one", 1_800_000_000_000L)
        fake.sms += FakeSms(1_002, 1, FakeTelephony.number(1), "fresh two", 1_800_000_001_000L)
        fake.mms += FakeMms(101, 1_800_000_002L, 1, FakeTelephony.number(3), listOf(FakeMmsPart(9001, "text/plain", "fresh mms")))
        val outcome = run(now = 1_000_000_000L + 1000)
        assertEquals(3, (outcome as HistoryImport.Outcome.Done).inserted)
        assertEquals(1_103, db.messageDao().count())
    }

    @Test
    fun `existing rows are never altered or duplicated`() = runTest {
        val fake = FakeTelephony.seed(50, 0).also { it.register() }
        val sms = fake.sms[10]
        db.messageDao().insert(
            MessageEntity(
                threadId = 99, address = sms.address!!, body = sms.body!!, timestamp = sms.date, isIncoming = true,
                label = MessageLabel.WORK, labelConfidence = 0.8f, isRead = false,
            ),
        )
        run()
        assertEquals(50, db.messageDao().count())
        val kept = db.messageDao().messagesForThread(sms.address!!).single { it.body == sms.body }
        assertEquals(MessageLabel.WORK, kept.label)
        assertEquals(false, kept.isRead)
        assertEquals(99L, kept.threadId)
        assertTrue("an upgrade (DB already had messages) imports quietly", state.quietFirstPass)
    }

    @Test
    fun `a fresh install shows progress, an upgrade does not`() = runTest {
        FakeTelephony.seed(30, 0).register()
        run()
        assertEquals(false, state.quietFirstPass)
    }

    @Test
    fun `kill mid-import then resume completes without duplicates and newest rows land first`() = runTest {
        val fake = FakeTelephony.seed(3_000, 600).also { it.register() }
        var n = 0
        fake.onQuery = { if (++n == 6) throw Killed() } // dies after a few pages
        try {
            run()
            error("expected the simulated kill")
        } catch (_: Killed) {
        }
        val partial = db.messageDao().count()
        assertTrue("some pages landed before the kill", partial in 1..3_599)
        // newest SMS first: the highest provider id is already in
        assertTrue(db.messageDao().messagesForThread(FakeTelephony.number(3_000)).any { it.body == "sms body 3000" })
        assertTrue(!state.isBackfillComplete())
        val saved = HistoryImport.statusFromState(state)
        assertTrue(saved.processed > 0 && saved.processed < saved.total)

        // "restart": new ImportState over the same prefs file, provider no longer failing
        fake.onQuery = {}
        val resumed = ImportState(context.getSharedPreferences("test_import_state", Context.MODE_PRIVATE))
        run(s = resumed)
        assertEquals(3_600, db.messageDao().count())
        assertTrue(resumed.isBackfillComplete())
    }

    @Test
    fun `restart after the kill does not re-read pages that were already committed`() = runTest {
        val fake = FakeTelephony.seed(3_000, 0).also { it.register() }
        var n = 0
        fake.onQuery = { if (++n == 8) throw Killed() }
        try { run() } catch (_: Killed) {}
        val low = state.load(ImportState.SMS).low
        assertTrue("resume point advanced past the first page", low < 3_000)
        val before = fake.queryCount
        fake.onQuery = {}
        run()
        // remaining pages only: bounded by what was left, not a full re-read
        val remainingPages = (low - 1 + 499) / 500
        assertTrue(fake.queryCount - before <= remainingPages + 6)
        assertEquals(3_000, db.messageDao().count())
    }

    @Test
    fun `not the default app is a no-op`() = runTest {
        val fake = FakeTelephony.seed(10, 10).also { it.register() }
        val outcome = run(default = false)
        assertEquals(HistoryImport.Outcome.NotDefault, outcome)
        assertEquals(0, fake.queryCount)
        assertEquals(0, db.messageDao().count())
    }

    @Test
    fun `provider refusing access surfaces a permission error`() = runTest {
        val fake = FakeTelephony.seed(10, 10).also { it.register() }
        fake.securityDenied = true
        val outcome = run() as HistoryImport.Outcome.Failed
        assertEquals(ImportError.PERMISSION_DENIED, outcome.error)
        assertEquals(false, outcome.retryable)
        assertEquals(ImportError.PERMISSION_DENIED, ImportStatusHolder.status.value.error)
        assertTrue(ImportStatusHolder.status.value.showStrip)
        // retry after the user fixes it works and clears the error
        fake.securityDenied = false
        run()
        assertEquals(null, ImportStatusHolder.status.value.error)
        assertEquals(20, db.messageDao().count())
    }

    @Test
    fun `null cursor is a retryable provider error`() = runTest {
        val fake = FakeTelephony.seed(10, 10).also { it.register() }
        fake.nullCursor = true
        val outcome = run() as HistoryImport.Outcome.Failed
        assertEquals(ImportError.PROVIDER_UNAVAILABLE, outcome.error)
        assertTrue(outcome.retryable)
    }

    @Test
    fun `low storage stops before touching anything`() = runTest {
        val fake = FakeTelephony.seed(10, 10).also { it.register() }
        val outcome = run(free = 1_000) as HistoryImport.Outcome.Failed
        assertEquals(ImportError.LOW_STORAGE, outcome.error)
        assertEquals(0, fake.queryCount)
    }

    @Test
    fun `mms placeholder is healed in place during a repair pass and never duplicated`() = runTest {
        val fake = FakeTelephony.seed(0, 0).also { it.register() }
        fake.mms += FakeMms(1, 1_800_000_000L, 1, "+15550100")
        run()
        assertEquals("[MMS message]", db.messageDao().messagesForThread("+15550100").single().body)
        fake.mms[0] = fake.mms[0].copy(parts = listOf(FakeMmsPart(77, "image/jpeg")))
        run(now = 1_000_000_000L + HistoryImport.REPAIR_EVERY_MS + 1)
        val row = db.messageDao().messagesForThread("+15550100").single()
        assertEquals("content://mms/part/77", row.imagePartUri)
    }

    @Test
    fun `two provider rows for the same mms moment collapse to one row`() = runTest {
        val fake = FakeTelephony.seed(0, 0).also { it.register() }
        fake.mms += FakeMms(1, 1_800_000_000L, 1, "+15550100", listOf(FakeMmsPart(5, "text/plain", "hi")))
        fake.mms += FakeMms(2, 1_800_000_000L, 1, "+15550100", listOf(FakeMmsPart(6, "image/png")))
        run()
        assertEquals(1, db.messageDao().messagesForThread("+15550100").size)
        assertEquals("content://mms/part/6", db.messageDao().messagesForThread("+15550100").single().imagePartUri)
    }

    @Test
    fun `batched part query rejected by the provider falls back to per-message reads`() = runTest {
        val fake = FakeTelephony.seed(0, 20).also { it.register() }
        fake.batchedPartQueries = false
        run()
        assertEquals(20, db.messageDao().count())
    }

    // ---- deleted conversations

    @Test
    fun `a deleted conversation does not come back on a full re-import, a newer text does`() = runTest {
        val fake = FakeTelephony.seed(0, 0).also { it.register() }
        val a = "+15550101"
        for (i in 1..5) fake.sms += FakeSms(i.toLong(), 1, a, "old $i", 1_000L * i)
        fake.sms += FakeSms(6, 2, "+15550102", "other", 7_000L)
        run()
        assertEquals(6, db.messageDao().count())

        // user deletes the conversation inside InboxIQ
        db.messageDao().deleteThread(a)
        tombstones.markThreadDeleted(a, 5_000L)

        // state lost (or repair pass): everything is offered again
        val fresh = ImportState(context.getSharedPreferences("fresh_state", Context.MODE_PRIVATE))
        fake.sms += FakeSms(7, 1, a, "brand new after delete", 9_000L)
        run(s = fresh)
        val rows = db.messageDao().messagesForThread(a)
        assertEquals(listOf("brand new after delete"), rows.map { it.body })
    }

    @Test
    fun `undoing a delete clears the tombstone`() = runTest {
        val fake = FakeTelephony.seed(0, 0).also { it.register() }
        val a = "+15550101"
        fake.sms += FakeSms(1, 1, a, "keep me", 1_000L)
        tombstones.markThreadDeleted(a, 1_000L)
        tombstones.clearThread(a)
        run()
        assertEquals(1, db.messageDao().messagesForThread(a).size)
    }

    @Test
    fun `a deleted single message stays gone while its neighbours import`() = runTest {
        val fake = FakeTelephony.seed(0, 0).also { it.register() }
        val a = "+15550101"
        fake.sms += FakeSms(1, 1, a, "one", 1_000L)
        fake.sms += FakeSms(2, 1, a, "two", 2_000L)
        tombstones.markMessageDeleted(a, 2_000L, "two")
        run()
        assertEquals(listOf("one"), db.messageDao().messagesForThread(a).map { it.body })
    }

    @Test
    fun `tombstones survive a new instance over the same prefs`() {
        tombstones.markThreadDeleted("+15550101", 5_000L)
        val again = ImportTombstones(context.getSharedPreferences("test_import_tombstones", Context.MODE_PRIVATE))
        assertTrue(again.isSuppressed("+15550101", 5_000L, "x"))
        assertTrue(!again.isSuppressed("+15550101", 5_001L, "x"))
        assertTrue(!again.isSuppressed("+15550199", 1L, "x"))
    }
}
