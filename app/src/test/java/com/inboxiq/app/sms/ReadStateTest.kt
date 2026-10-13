package com.inboxiq.app.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.data.ThreadActions
import com.inboxiq.app.inbox.InboxLogic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Died : Error("process died")

/** Read flag, unread counts, the one-time reconcile and the sent-MMS duplicate. Synthetic 555-01xx data only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ReadStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var state: ImportState
    private val dao get() = db.messageDao()
    private val me = "+15550100"
    private val t0 = 1_800_000_000_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        state = ImportState(context.getSharedPreferences("test_read_state", Context.MODE_PRIVATE))
        context.getSharedPreferences("test_read_state", Context.MODE_PRIVATE).edit().clear().commit()
        ImportStatusHolder.reset()
    }

    @After
    fun tearDown() = db.close()

    private fun env() = ImportEnv(
        context, context.contentResolver, db,
        ImportTombstones(context.getSharedPreferences("test_read_tombstones", Context.MODE_PRIVATE)),
        normalizer = { it },
    )

    private suspend fun run(now: Long = 1_000_000_000L) =
        HistoryImport.run(env(), state, now = { now }, freeBytes = { Long.MAX_VALUE }, isDefault = { true })

    /** What an existing install looks like: history already imported once, repair pass not due. */
    private fun asExistingInstall(fake: FakeTelephony, now: Long = 1_000_000_000L) {
        state.save(ImportState.SMS, SourceState(started = true, highWater = fake.sms.maxOfOrNull { it.id } ?: 0, low = 1, backfillDone = true))
        state.save(ImportState.MMS, SourceState(started = true, highWater = fake.mms.maxOfOrNull { it.id } ?: 0, low = 1, backfillDone = true))
        state.quietFirstPass = true
        state.lastRepairAt = now
    }

    private suspend fun unreadInThreadList(): Int = dao.observeThreadList().first().sumOf { it.unreadCount }
    private suspend fun unreadRows(): Int = dao.observeAll().first().count { it.isIncoming && !it.isRead }

    private fun incomingSms(id: Long, address: String, read: Int, date: Long = t0 + id * 1000) =
        FakeSms(id, id, address, "sms $id", date, type = 1, read = read)

    // ---- import carries the provider's read flag -----------------------------------------------------------------

    @Test
    fun `fresh import yields exactly the provider's unread count, sms and mms, and the header and rows agree`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        var unread = 0
        for (i in 1..40) { val r = if (i % 4 == 0) 0 else 1; if (r == 0) unread++; fake.sms += incomingSms(i.toLong(), FakeTelephony.number(i), r) }
        for (i in 41..50) fake.sms += FakeSms(i.toLong(), 1, FakeTelephony.number(i), "sent $i", t0 + i * 1000L, type = 2, read = 0) // sent rows are never unread
        for (i in 1..30) {
            val r = if (i % 3 == 0) 0 else 1; if (r == 0) unread++
            fake.mms += FakeMms(i.toLong(), t0 / 1000 + i * 5, 1, FakeTelephony.number(i + 100), listOf(FakeMmsPart(i * 10L, "text/plain", "mms $i")), read = r)
        }
        fake.mms += FakeMms(31, t0 / 1000 + 500, 2, "insert-address-token", listOf(FakeMmsPart(310, "text/plain", "out")), to = listOf(FakeTelephony.number(5)), read = 0)

        run()

        assertEquals(unread, unreadRows())
        assertEquals(unread, unreadInThreadList())
        val rows = InboxLogic.join(dao.observeThreadList().first(), emptyList())
        assertEquals(unread, InboxLogic.totalUnreadMessages(rows))
        assertTrue(dao.observeAll().first().filter { !it.isIncoming }.all { it.isRead })
    }

    @Test
    fun `ten thousand sms and five thousand mms with mixed read flags import to the exact unread count within budget`() = runTest {
        val fake = FakeTelephony.seed(10_000, 5_000).also { it.register() }
        var expected = 0
        for (i in fake.sms.indices) { val r = fake.sms[i]; if (r.type == 1 && i % 5 == 0) { fake.sms[i] = r.copy(read = 0); expected++ } }
        for (i in fake.mms.indices) { val r = fake.mms[i]; if (r.box == 1 && i % 7 == 0) { fake.mms[i] = r.copy(read = 0); expected++ } else fake.mms[i] = r.copy(read = 1) }
        val t = System.nanoTime()
        run()
        val ms = (System.nanoTime() - t) / 1_000_000
        println("READ_STATE_10K ms=$ms expectedUnread=$expected")
        assertEquals(expected, unreadRows())
        assertEquals(expected, unreadInThreadList())
        assertEquals(15_000, dao.count())
        assertTrue("time budget", ms < 60_000)
    }

    @Test
    fun `a killed import resumes and still ends with exactly the provider's unread count and no duplicates`() = runTest {
        val fake = FakeTelephony.seed(2_000, 300).also { it.register() }
        var expected = 0
        for (i in fake.sms.indices) { val r = fake.sms[i]; if (r.type == 1 && i % 3 == 0) { fake.sms[i] = r.copy(read = 0); expected++ } }
        for (i in fake.mms.indices) { val r = fake.mms[i]; if (r.box == 1 && i % 2 == 0) { fake.mms[i] = r.copy(read = 0); expected++ } }
        var n = 0
        fake.onQuery = { if (++n == 9) throw Died() }
        try { run(); throw AssertionError("expected the simulated death") } catch (_: Died) {}
        assertTrue(dao.count() in 1 until 2_300)
        fake.onQuery = {}
        run()
        assertEquals(2_300, dao.count())
        assertEquals(expected, unreadRows())
        assertEquals(expected, unreadInThreadList())
        // and a second full run changes nothing
        val before = dao.observeAll().first().map { it.id to it.isRead }
        run(now = 1_000_000_000L + HistoryImport.REPAIR_EVERY_MS + 1)
        assertEquals(before, dao.observeAll().first().map { it.id to it.isRead })
    }

    @Test
    fun `incoming mms is no longer unread just because it is incoming`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        fake.mms += FakeMms(1, t0 / 1000, 1, me, listOf(FakeMmsPart(1, "text/plain", "old")), read = 1, thread = 7)
        fake.mms += FakeMms(2, t0 / 1000 + 1, 1, me, listOf(FakeMmsPart(2, "text/plain", "new")), read = 0, thread = 7)
        run()
        val rows = dao.messagesForThread(me).associateBy { it.body }
        assertTrue(rows.getValue("old").isRead)
        assertFalse(rows.getValue("new").isRead)
        assertEquals(7L, rows.getValue("new").threadId)
    }

    // ---- mark-read stays consistent with the provider ------------------------------------------------------------

    @Test
    fun `marking an imported conversation read mirrors into sms and mms provider rows and leaves other conversations unread`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        fake.sms += FakeSms(1, 21, me, "s", t0, type = 1, read = 0)
        fake.mms += FakeMms(1, t0 / 1000 + 5, 1, me, listOf(FakeMmsPart(1, "text/plain", "m")), read = 0, thread = 21)
        fake.sms += FakeSms(2, 22, "+15550101", "other", t0 + 9000, type = 1, read = 0)
        run()
        assertEquals(3, unreadRows())

        ThreadActions(db, readSync = ProviderReadSync(ProviderSms(context.contentResolver))).markRead(me)

        assertEquals(1, unreadRows())
        assertEquals(1, fake.sms.single { it.id == 1L }.read)
        assertEquals(1, fake.mms.single().read)
        assertEquals(0, fake.sms.single { it.id == 2L }.read)
        assertEquals(1, unreadInThreadList())
    }

    // ---- one-time reconcile --------------------------------------------------------------------------------------

    @Test
    fun `reconcile marks imported rows read where the provider says read, and nothing else, exactly once`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        // provider truth
        fake.sms += incomingSms(1, me, read = 1)                       // wrongly unread in our DB -> becomes read
        fake.sms += incomingSms(2, me, read = 0)                       // genuinely unread -> stays unread
        fake.sms += incomingSms(3, me, read = 0)                       // user read it in InboxIQ (our DB says read) -> stays read
        fake.sms += incomingSms(4, me, read = 1)                       // received by us, still unread in our DB, provider flipped -> read
        fake.mms += FakeMms(1, t0 / 1000 + 100, 1, me, listOf(FakeMmsPart(1, "text/plain", "mm1")), read = 1)  // wrongly unread -> read
        fake.mms += FakeMms(2, t0 / 1000 + 101, 1, me, listOf(FakeMmsPart(2, "text/plain", "mm2")), read = 0)  // stays unread
        // our DB, as the old build left it
        fun row(body: String, ts: Long, read: Boolean, label: MessageLabel = MessageLabel.UNLABELED) =
            MessageEntity(threadId = 0, address = me, body = body, timestamp = ts, isIncoming = true, isRead = read, label = label, labelConfidence = if (label == MessageLabel.UNLABELED) 0f else 0.9f)
        dao.insertAll(
            listOf(
                row("sms 1", t0 + 1000, false, MessageLabel.WORK),
                row("sms 2", t0 + 2000, false),
                row("sms 3", t0 + 3000, true),
                row("sms 4", t0 + 4000, false),
                row("mm1", t0 + 100_000, false),
                row("mm2", t0 + 101_000, false),
                row("orphan, no provider row", t0 + 555_000, false),
            ),
        )
        asExistingInstall(fake)

        run()

        val rows = dao.messagesForThread(me).associateBy { it.body }
        assertTrue(rows.getValue("sms 1").isRead)
        assertEquals(MessageLabel.WORK, rows.getValue("sms 1").label)
        assertFalse(rows.getValue("sms 2").isRead)
        assertTrue(rows.getValue("sms 3").isRead)
        assertTrue(rows.getValue("sms 4").isRead)
        assertTrue(rows.getValue("mm1").isRead)
        assertFalse(rows.getValue("mm2").isRead)
        assertFalse("no provider row: left alone", rows.getValue("orphan, no provider row").isRead)
        assertEquals(7, dao.count())
        assertEquals(ReadReconcile.VERSION, state.readReconcileVersion)

        // Runs once: later provider changes are not pulled in, and nothing is ever marked unread again.
        fake.sms[1] = fake.sms[1].copy(read = 1)
        dao.markUnread(rows.getValue("sms 3").id) // the user (or a new text) made something unread meanwhile
        run()
        val again = dao.messagesForThread(me).associateBy { it.body }
        assertFalse(again.getValue("sms 2").isRead)
        assertFalse(again.getValue("sms 3").isRead)
    }

    @Test
    fun `reconcile that fails midway is retried and never leaves the flag set`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        fake.sms += incomingSms(1, me, read = 1)
        dao.insert(MessageEntity(threadId = 0, address = me, body = "sms 1", timestamp = t0 + 1000, isIncoming = true, isRead = false))
        asExistingInstall(fake)
        fake.securityDenied = true
        val out = run() as HistoryImport.Outcome.Failed
        assertEquals(ImportError.PERMISSION_DENIED, out.error)
        assertEquals(0, state.readReconcileVersion)
        fake.securityDenied = false
        run()
        assertTrue(dao.messagesForThread(me).single().isRead)
        assertEquals(ReadReconcile.VERSION, state.readReconcileVersion)
    }

    @Test
    fun `reconcile at ten thousand rows is bounded`() = runTest {
        val fake = FakeTelephony.seed(10_000, 0).also { it.register() }
        for (i in fake.sms.indices) fake.sms[i] = fake.sms[i].copy(read = 1)
        // our DB wrongly holds every incoming sms as unread
        val rows = fake.sms.filter { it.type == 1 }.map {
            MessageEntity(threadId = 0, address = it.address!!, body = it.body!!, timestamp = it.date, isIncoming = true, isRead = false)
        }
        dao.insertAll(rows)
        asExistingInstall(fake)
        val t = System.nanoTime()
        run()
        println("RECONCILE_10K ms=${(System.nanoTime() - t) / 1_000_000} rows=${rows.size}")
        assertEquals(0, unreadRows())
        assertTrue((System.nanoTime() - t) / 1_000_000 < 60_000)
    }

    // ---- sent MMS ------------------------------------------------------------------------------------------------

    private fun sentLocal(ts: Long, body: String = "look", address: String = FakeTelephony.number(5), label: MessageLabel = MessageLabel.PERSONAL) = MessageEntity(
        threadId = 0, address = address, body = body, timestamp = ts, isIncoming = false, label = label,
        sendStatus = SendStatus.SENT, imagePartUri = "content://com.inboxiq.app.fileprovider/mms_images/x.jpg",
    )

    private fun sentProvider(id: Long, dateSeconds: Long, text: String = "look", to: String = FakeTelephony.number(5), partId: Long = id * 10) =
        FakeMms(id, dateSeconds, 2, "insert-address-token", listOf(FakeMmsPart(partId, "text/plain", text), FakeMmsPart(partId + 1, "image/jpeg")), to = listOf(to), read = 1)

    @Test
    fun `sent mms stamped in the same second by the sender is not imported a second time`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        val ts = MmsSender.sentTimestamp(t0 + 123)
        assertEquals(0L, ts % 1000)
        dao.insert(sentLocal(ts))
        fake.mms += sentProvider(1, ts / 1000)
        run()
        assertEquals(1, dao.count())
        assertTrue(dao.messagesForThread(FakeTelephony.number(5)).single().imagePartUri!!.contains("fileprovider"))
    }

    @Test
    fun `even a millisecond stamped local row matches the provider row of the same second`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        dao.insert(sentLocal(t0 + 123))
        fake.mms += sentProvider(1, (t0 + 123) / 1000)
        run()
        assertEquals(1, dao.count())
    }

    @Test
    fun `a sent mms from another app imports under the recipient, not under this phone`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        fake.mms += sentProvider(1, t0 / 1000)
        run()
        val row = dao.observeAll().first().single()
        assertEquals(FakeTelephony.number(5), row.address)
        assertFalse(row.isIncoming)
        assertTrue(row.isRead)
    }

    @Test
    fun `a plain sms sent in the same second does not swallow the sent mms`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        dao.insert(MessageEntity(threadId = 0, address = FakeTelephony.number(5), body = "hello", timestamp = t0 + 10, isIncoming = false))
        fake.mms += sentProvider(1, t0 / 1000, text = "look")
        run()
        assertEquals(2, dao.count())
    }

    @Test
    fun `old database with a stale copy under the placeholder address is repaired without losing or duplicating anything`() = runTest {
        val fake = FakeTelephony().also { it.register() }
        val a = FakeTelephony.number(5)
        val b = FakeTelephony.number(6)
        val c = FakeTelephony.number(7)
        val bogus = "insert-address-token"
        // 1: sent from InboxIQ (local row, ms stamp) + the imported stale copy -> one row, the user's
        fake.mms += sentProvider(1, t0 / 1000 + 10, to = a)
        // 2: sent from another app, only the stale copy exists -> re-filed under the recipient, same id and label
        fake.mms += sentProvider(2, t0 / 1000 + 20, text = "second", to = b)
        // 3: stale copy whose provider row is gone -> untouched
        // 4: a received text from the recipient with the same key as a repaired one -> never touched, ghost kept
        fake.mms += sentProvider(4, t0 / 1000 + 40, text = "fourth", to = c)
        val local1 = sentLocal(t0 + 10_000 + 321, address = a, label = MessageLabel.PERSONAL)
        val local1Id = dao.insert(local1)
        fun ghost(ts: Long, body: String, part: Long, label: MessageLabel = MessageLabel.UNLABELED) = MessageEntity(
            threadId = 0, address = bogus, body = body, timestamp = ts, isIncoming = false, isRead = true, label = label,
            labelConfidence = if (label == MessageLabel.UNLABELED) 0f else 1f, imagePartUri = "content://mms/part/$part",
        )
        dao.insert(ghost(t0 + 10_000, "look", 11))
        val ghost2 = dao.insert(ghost(t0 + 20_000, "second", 21, MessageLabel.WORK))
        dao.insert(ghost(t0 + 30_000, "third (provider row deleted)", 31))
        dao.insert(ghost(t0 + 40_000, "fourth", 41))
        dao.insert(MessageEntity(threadId = 0, address = c, body = "fourth", timestamp = t0 + 40_000, isIncoming = true, isRead = false))
        asExistingInstall(fake)

        run()

        assertEquals("a: only the user's own row", listOf(local1Id), dao.messagesForThread(a).map { it.id })
        assertEquals(MessageLabel.PERSONAL, dao.messagesForThread(a).single().label)
        val moved = dao.messagesForThread(b).single()
        assertEquals(ghost2, moved.id); assertEquals(MessageLabel.WORK, moved.label); assertEquals("content://mms/part/21", moved.imagePartUri)
        assertEquals(listOf("third (provider row deleted)"), dao.messagesForThread(bogus).filter { it.body.startsWith("third") }.map { it.body })
        assertEquals("c: the received text is untouched and the stale copy is not merged into it", 1, dao.messagesForThread(c).size)
        assertTrue(dao.messagesForThread(c).single().isIncoming)
        assertEquals(1, dao.messagesForThread(bogus).count { it.body == "fourth" })
        assertEquals(5, dao.count()) // local1, moved, third, fourth ghost, received
        assertEquals(SentMmsRepair.VERSION, state.sentMmsRepairVersion)

        // idempotent, and the later repair pass does not bring any copy back
        val snapshot = dao.observeAll().first().map { Triple(it.id, it.address, it.timestamp) }.toSet()
        run(now = 1_000_000_000L + HistoryImport.REPAIR_EVERY_MS + 1)
        assertEquals(snapshot, dao.observeAll().first().map { Triple(it.id, it.address, it.timestamp) }.toSet())
    }

    @Test
    fun `dedupe removes only an imported twin of a local sent mms in the same conversation and second, keeping the local row's state`() = runTest {
        val a = FakeTelephony.number(5)
        val keep = dao.insert(sentLocal(t0 + 5_321, label = MessageLabel.UNLABELED).copy(sendStatus = SendStatus.SENT))
        dao.insert(sentLocal(t0 + 5_000).copy(imagePartUri = "content://mms/part/9", label = MessageLabel.WORK, sendStatus = SendStatus.NONE))
        // not twins: different second, different text, two local rows, two imported rows
        dao.insert(sentLocal(t0 + 9_000).copy(imagePartUri = "content://mms/part/10"))
        dao.insert(sentLocal(t0 + 5_000, body = "other").copy(imagePartUri = "content://mms/part/11"))
        dao.insert(sentLocal(t0 + 20_100, body = "x"))
        dao.insert(sentLocal(t0 + 20_200, body = "x"))
        dao.insert(sentLocal(t0 + 30_000, body = "y").copy(imagePartUri = "content://mms/part/12"))
        dao.insert(sentLocal(t0 + 30_500, body = "y").copy(imagePartUri = "content://mms/part/13"))

        val removed = SentMmsRepair.dedupe(env())

        assertEquals(1, removed)
        val kept = dao.getById(keep)!!
        assertEquals(MessageLabel.WORK, kept.label) // borrowed because the keeper had none
        assertEquals(SendStatus.SENT, kept.sendStatus)
        assertEquals(7, dao.messagesForThread(a).size)
    }

    @Test
    fun `sender stamps whole seconds and moves a colliding identical send to the next free second`() = runTest {
        assertEquals(1_800_000_001_000L, MmsSender.sentTimestamp(1_800_000_001_999L))
        val first = MmsSender.insertAligned(dao, sentLocal(t0))
        val second = MmsSender.insertAligned(dao, sentLocal(t0))
        assertTrue(first > 0 && second > 0 && first != second)
        assertEquals(listOf(t0, t0 + 1000), dao.messagesForThread(FakeTelephony.number(5)).map { it.timestamp })
    }

    @Test
    fun `fresh install with an empty database marks both one-time repairs done without reading history rows`() = runTest {
        FakeTelephony.seed(5, 5).also { it.register() }
        run()
        assertEquals(ReadReconcile.VERSION, state.readReconcileVersion)
        assertEquals(SentMmsRepair.VERSION, state.sentMmsRepairVersion)
        assertNotNull(dao.observeAll().first().firstOrNull())
        assertNull(dao.findByKey("nobody", 0, ""))
    }
}
