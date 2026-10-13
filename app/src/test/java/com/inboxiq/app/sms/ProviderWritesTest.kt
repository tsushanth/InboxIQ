package com.inboxiq.app.sms

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.BlockedNumberEntity
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.data.ReadSync
import com.inboxiq.app.data.ThreadActions
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ProviderWritesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var fake: FakeTelephony
    private lateinit var queue: ProviderWriteQueue
    private lateinit var provider: ProviderSms
    private var retryScheduled = 0

    /** Mimics E.164 normalisation: idempotent, leaves short codes and alphanumeric senders alone. */
    private val normalize: (String) -> String = { raw ->
        val digits = raw.filter(Char::isDigit)
        when {
            raw.any(Char::isLetter) || digits.length < 7 -> raw
            raw.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            else -> "+$digits"
        }
    }

    private fun store(normalizer: (String) -> String = normalize) =
        IncomingSmsStore(db, provider, queue, normalizer, { retryScheduled++ })

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        fake = FakeTelephony().also { it.register() }
        context.getSharedPreferences("test_pw_queue", Context.MODE_PRIVATE).edit().clear().commit()
        queue = ProviderWriteQueue(context.getSharedPreferences("test_pw_queue", Context.MODE_PRIVATE))
        provider = ProviderSms(context.contentResolver)
        retryScheduled = 0
        ImportStatusHolder.reset()
    }

    @After
    fun tearDown() = db.close()

    private fun importAll(into: AppDatabase = db, normalizer: (String) -> String = normalize) = kotlinx.coroutines.runBlocking {
        val prefs = context.getSharedPreferences("test_pw_import_${System.nanoTime()}", Context.MODE_PRIVATE)
        val tomb = ImportTombstones(context.getSharedPreferences("test_pw_tomb_${System.nanoTime()}", Context.MODE_PRIVATE))
        HistoryImport.run(
            ImportEnv(context, context.contentResolver, into, tomb, normalizer = normalizer),
            ImportState(prefs), now = { 1L }, freeBytes = { Long.MAX_VALUE }, isDefault = { true },
        )
    }

    // ---- receive ----

    @Test
    fun `received sms lands in the provider inbox with PDU times, unread, and in our db keyed on the provider row`() = runTest {
        val s = store().store("5550100123", "hello there", 1_800_000_000_000L, "+15550100999", subscriptionId = 2)!!

        val row = fake.sms.single()
        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, row.type)
        assertEquals("5550100123", row.address) // the provider keeps what the network sent
        assertEquals("hello there", row.body)
        assertEquals(1_800_000_000_000L, row.date)
        assertEquals(1_800_000_000_000L, row.dateSent)
        assertEquals(0, row.read)
        assertEquals(0, row.seen)
        assertEquals("+15550100999", row.serviceCenter)
        assertEquals(2, row.subId)

        val mine = db.messageDao().getById(s.id)!!
        assertEquals("+15550100123", mine.address)
        assertEquals(1_800_000_000_000L, mine.timestamp)
        assertEquals(row.thread, mine.threadId)
        assertFalse(mine.isRead)
        assertTrue(mine.isIncoming)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a full history import after a receive adds nothing and changes nothing`() = runTest {
        val s = store().store("5550100123", "your code is 123456", 1_800_000_000_000L, null, -1)!!
        store().store("AMAZON", "parcel", 1_800_000_001_000L, null, -1)
        store().store("+1 (555) 010-0456", "hi", 1_800_000_002_000L, null, -1)
        val before = db.messageDao().pageAfter(0, 100)

        val outcome = importAll() as HistoryImport.Outcome.Done
        assertEquals(0, outcome.inserted)
        assertEquals(before, db.messageDao().pageAfter(0, 100))
        assertFalse(db.messageDao().getById(s.id)!!.isRead) // import never touches existing rows
        // and again later, incrementally and through the repair window
        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
    }

    @Test
    fun `key equality - what the receive path stores is exactly what a fresh import computes from the provider`() = runTest {
        val raws = listOf("5550100123", "+15550100123", "(555) 010-0123", "12345", "AMAZON", "+44 20 7946 0958")
        raws.forEachIndexed { i, raw -> store().store(raw, "body $i", 1_800_000_000_000L + i * 1000L, null, -1) }

        val other = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            importAll(into = other)
            fun key(m: MessageEntity) = Triple(m.address, m.timestamp, m.body)
            assertEquals(
                db.messageDao().pageAfter(0, 100).map(::key).toSet(),
                other.messageDao().pageAfter(0, 100).map(::key).toSet(),
            )
            assertEquals(raws.size, other.messageDao().count())
        } finally { other.close() }
    }

    @Test
    fun `key equality holds with the real normaliser too`() = runTest {
        Shadows.shadowOf(context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).setNetworkCountryIso("us")
        val real: (String) -> String = { PhoneNumberNormalizer.normalize(context, it) }
        listOf("8136000258", "+18136000258", "12345", "AMAZON", "(813) 600-0258").forEachIndexed { i, raw ->
            store(real).store(raw, "msg $i", 1_800_000_000_000L + i, null, -1)
        }
        val before = db.messageDao().pageAfter(0, 100)
        assertEquals(0, (importAll(normalizer = real) as HistoryImport.Outcome.Done).inserted)
        assertEquals(before, db.messageDao().pageAfter(0, 100))
    }

    @Test
    fun `an invalid PDU timestamp falls back to now and the same value is used on both sides`() = runTest {
        val s = store().store("5550100123", "x", 0L, null, -1, now = 1_900_000_000_000L)!!
        assertEquals(1_900_000_000_000L, s.timestamp)
        assertEquals(1_900_000_000_000L, fake.sms.single().date)
        assertEquals(0L, fake.sms.single().dateSent)
        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
    }

    @Test
    fun `import running between the provider write and our insert does not duplicate and the message stays unread`() = runTest {
        // Simulate the import winning the race: it already stored the provider row as a read incoming row.
        fake.sms += FakeSms(1, 7, "5550100123", "raced", 1_800_000_000_000L, 1)
        db.messageDao().insert(MessageEntity(threadId = 7, address = "+15550100123", body = "raced", timestamp = 1_800_000_000_000L, isIncoming = true))
        // (our store inserts a second provider row here; the point is the DB side)
        val s = store().store("5550100123", "raced", 1_800_000_000_000L, null, -1)!!
        assertEquals(1, db.messageDao().count())
        assertFalse(db.messageDao().getById(s.id)!!.isRead)
    }

    @Test
    fun `blocked sender is dropped as before and nothing is written to the provider`() = runTest {
        db.blockedNumberDao().block(BlockedNumberEntity("+15550100123", 1L))
        assertNull(store().store("5550100123", "spam", 1_800_000_000_000L, null, -1))
        assertEquals(0, fake.sms.size)
        assertEquals(0, db.messageDao().count())
    }

    // ---- multipart ----

    private fun ucs2Part(ref: Int, total: Int, seq: Int, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // no SMSC
        out.write(0x44) // SMS-DELIVER, UDHI
        out.write(0x0B); out.write(0x91) // OA: 11 digits, international
        "15550100123F".chunked(2).forEach { out.write(it.reversed().toInt(16)) }
        out.write(0x00); out.write(0x08) // PID, DCS = UCS2
        // SCTS 2026-10-13 12:00:00 UTC, tz +0
        listOf("26", "10", "13", "12", "00", "00", "00").forEach { out.write(it.reversed().toInt(16)) }
        val text16 = text.toByteArray(Charsets.UTF_16BE)
        out.write(6 + text16.size)
        listOf(0x05, 0x00, 0x03, ref, total, seq).forEach { out.write(it) }
        out.write(text16)
        return out.toByteArray()
    }

    @Test
    fun `a multipart message is one provider row and one db row with the joined body`() = runTest {
        val parts = arrayOf(
            SmsMessage.createFromPdu(ucs2Part(9, 2, 1, "Hello, this is "), "3gpp"),
            SmsMessage.createFromPdu(ucs2Part(9, 2, 2, "one long text."), "3gpp"),
        )
        val inc = SmsDeliverReceiver.combine(parts)
        assertEquals("Hello, this is one long text.", inc.body)
        assertEquals("+15550100123", inc.rawAddress)
        val expected = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.OCTOBER, 13, 12, 0, 0) }.timeInMillis
        assertEquals(expected, inc.pduTimestamp)

        store().store(inc.rawAddress, inc.body, inc.pduTimestamp, inc.serviceCenter, -1)
        assertEquals(1, fake.sms.size)
        assertEquals("Hello, this is one long text.", fake.sms.single().body)
        assertEquals(1, db.messageDao().count())
        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
        assertEquals(1, db.messageDao().count())
    }

    // ---- failures and retry ----

    @Test
    fun `provider insert failure keeps the message, queues it and schedules a retry`() = runTest {
        fake.insertFailure = IllegalStateException("storage full")
        val s = store().store("5550100123", "important code 998877", 1_800_000_000_000L, null, -1)!!
        assertEquals(0, fake.sms.size)
        val mine = db.messageDao().getById(s.id)!!
        assertEquals("important code 998877", mine.body)
        assertEquals("+15550100123", mine.address)
        assertEquals(listOf(s.id), queue.ids())
        assertEquals(1, retryScheduled)
    }

    @Test
    fun `not the default app - SecurityException never escapes, message kept and queued`() = runTest {
        fake.writable = false
        val s = store().store("5550100123", "hi", 1_800_000_000_000L, null, -1)!!
        assertEquals(1, db.messageDao().count())
        assertEquals(listOf(s.id), queue.ids())
    }

    private fun retry(default: Boolean = true) =
        ProviderWriteRetry(db, provider, queue, normalize) { default }

    @Test
    fun `retry writes the row once the provider works, is idempotent and creates no duplicate on import`() = runTest {
        fake.insertFailure = RuntimeException("down")
        val s = store().store("5550100123", "later", 1_800_000_000_000L, null, -1)!!
        fake.insertFailure = null

        assertEquals(ProviderWriteRetry.Outcome.DONE, retry().run())
        assertTrue(queue.isEmpty())
        assertEquals(1, fake.sms.size)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_INBOX, fake.sms.single().type)
        assertEquals(1_800_000_000_000L, fake.sms.single().date)

        // running again (or a stale queue entry) must not write a second provider row
        queue.add(s.id)
        assertEquals(ProviderWriteRetry.Outcome.DONE, retry().run())
        assertEquals(1, fake.sms.size)

        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
        assertEquals(1, db.messageDao().count())
    }

    @Test
    fun `retry waits while not default without burning attempts`() = runTest {
        fake.insertFailure = RuntimeException("down")
        val s = store().store("5550100123", "wait", 1_800_000_000_000L, null, -1)!!
        repeat(10) { assertEquals(ProviderWriteRetry.Outcome.NOT_DEFAULT, retry(default = false).run()) }
        assertEquals(listOf(s.id), queue.ids())
        fake.insertFailure = null
        assertEquals(ProviderWriteRetry.Outcome.DONE, retry().run())
        assertEquals(1, fake.sms.size)
    }

    @Test
    fun `retry is bounded - a permanently failing write is dropped after MAX_ATTEMPTS but the message stays`() = runTest {
        fake.insertFailure = RuntimeException("down")
        val s = store().store("5550100123", "stuck", 1_800_000_000_000L, null, -1)!!
        repeat(ProviderWriteQueue.MAX_ATTEMPTS) { assertEquals(ProviderWriteRetry.Outcome.RETRY_LATER, retry().run()) }
        assertTrue(queue.isEmpty())
        assertNotNull(db.messageDao().getById(s.id))
        assertEquals(ProviderWriteRetry.Outcome.DONE, retry().run())
    }

    @Test
    fun `retry drops entries whose row was deleted by the user instead of resurrecting them`() = runTest {
        fake.insertFailure = RuntimeException("down")
        val s = store().store("5550100123", "gone", 1_800_000_000_000L, null, -1)!!
        fake.insertFailure = null
        db.messageDao().deleteMessage(s.id)
        retry().run()
        assertTrue(queue.isEmpty())
        assertEquals(0, fake.sms.size)
    }

    @Test
    fun `queue is bounded`() {
        for (i in 1..ProviderWriteQueue.MAX_ENTRIES + 20L) queue.add(i)
        assertEquals(ProviderWriteQueue.MAX_ENTRIES, queue.ids().size)
    }

    @Test
    fun `retry work is unique - many failures keep one work request`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        repeat(5) { ProviderWriteScheduler.enqueue(context) }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProviderWriteScheduler.WORK_NAME).get()
        assertEquals(1, infos.count { it.state != WorkInfo.State.CANCELLED })
    }

    @Test
    fun `enqueueIfPending only schedules when something is pending`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        context.getSharedPreferences(ProviderWriteQueue.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        ProviderWriteScheduler.enqueueIfPending(context)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProviderWriteScheduler.WORK_NAME).get().isEmpty())
        ProviderWriteQueue(context).add(5)
        ProviderWriteScheduler.enqueueIfPending(context)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ProviderWriteScheduler.WORK_NAME).get().size)
        context.getSharedPreferences(ProviderWriteQueue.FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---- send ----

    private fun outgoing() = OutgoingSmsSync(provider, queue, { retryScheduled++ }, isDefault = { true })

    @Test
    fun `send writes outbox then sent, and a re-import does not duplicate our row`() = runTest {
        val ts = 1_800_000_000_000L
        val id = db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100123", body = "on my way", timestamp = ts, isIncoming = false, sendStatus = SendStatus.PENDING))
        val uri = outgoing().begin(id, "+15550100123", "on my way", ts)!!
        assertEquals(Telephony.Sms.MESSAGE_TYPE_OUTBOX, fake.sms.single().type)
        assertEquals(1, fake.sms.single().read)

        outgoing().finish(id, uri, ok = true, errorCode = -1)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, fake.sms.single().type)

        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
        assertEquals(1, db.messageDao().count())
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `failed send marks the provider row failed with the error code`() = runTest {
        val ts = 1_800_000_000_000L
        val id = db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100123", body = "x", timestamp = ts, isIncoming = false, sendStatus = SendStatus.PENDING))
        val uri = outgoing().begin(id, "+15550100123", "x", ts)!!
        outgoing().finish(id, uri, ok = false, errorCode = 4)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, fake.sms.single().type)
        assertEquals(4, fake.sms.single().errorCode)
        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
    }

    @Test
    fun `outbox write failure is queued and the retry writes the final state from our row`() = runTest {
        val ts = 1_800_000_000_000L
        fake.insertFailure = RuntimeException("down")
        val id = db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100123", body = "y", timestamp = ts, isIncoming = false, sendStatus = SendStatus.PENDING))
        assertNull(outgoing().begin(id, "+15550100123", "y", ts))
        assertEquals(listOf(id), queue.ids())
        assertEquals(1, retryScheduled)

        fake.insertFailure = null
        db.messageDao().updateSendStatus(id, SendStatus.SENT)
        outgoing().finish(id, null, ok = true, errorCode = -1)
        assertEquals(ProviderWriteRetry.Outcome.DONE, retry().run())
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, fake.sms.single().type)
        assertEquals(0, (importAll() as HistoryImport.Outcome.Done).inserted)
        assertEquals(1, db.messageDao().count())
    }

    @Test
    fun `retry corrects an outbox row that never got its final state`() = runTest {
        val ts = 1_800_000_000_000L
        val id = db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100123", body = "z", timestamp = ts, isIncoming = false, sendStatus = SendStatus.SENT))
        outgoing().begin(id, "+15550100123", "z", ts)
        queue.add(id) // e.g. the update after the radio result failed
        retry().run()
        assertEquals(1, fake.sms.size)
        assertEquals(Telephony.Sms.MESSAGE_TYPE_SENT, fake.sms.single().type)
    }

    @Test
    fun `losing the default role mid-send is not queued - the platform records those sends`() = runTest {
        fake.writable = false
        val sync = OutgoingSmsSync(provider, queue, { retryScheduled++ }, isDefault = { false })
        assertNull(sync.begin(1, "+15550100123", "q", 1L))
        assertTrue(queue.isEmpty())
        assertEquals(0, retryScheduled)
    }

    @Test
    fun `SmsSender stores the same normalised address the import will compute, and writes the provider row`() = runTest {
        Shadows.shadowOf(context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).apply {
            setNetworkCountryIso("us")
            setIsSmsCapable(true)
        }
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        try {
            // Robolectric has no SmsManager service: the hand-off throws AFTER both rows were written, which
            // also exercises the "radio hand-off failed" path (row settles as failed in both stores).
            assertTrue(runCatching { SmsSender.send(context, "(813) 600-0258", "from respond-via-message") }.isFailure)
            val mine = db.messageDao().pageAfter(0, 10).single()
            assertEquals("+18136000258", mine.address)
            assertEquals(SendStatus.FAILED, mine.sendStatus)
            val row = fake.sms.single()
            assertEquals(Telephony.Sms.MESSAGE_TYPE_FAILED, row.type)
            assertEquals(mine.timestamp, row.date)
            val real: (String) -> String = { PhoneNumberNormalizer.normalize(context, it) }
            assertEquals(0, (importAll(normalizer = real) as HistoryImport.Outcome.Done).inserted)
            assertEquals(1, db.messageDao().count())
        } finally {
            AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
    }

    // ---- read state ----

    private class Rec(val provider: ProviderSms) : ReadSync {
        override fun markThreadsRead(threadIds: Collection<Long>) = provider.markThreadsRead(threadIds)
        override fun markAllRead() = provider.markAllRead()
    }

    @Test
    fun `marking a conversation read mirrors into the provider for that conversation only`() = runTest {
        store().store("5550100123", "a", 1_800_000_000_000L, null, -1)
        store().store("5550100456", "b", 1_800_000_001_000L, null, -1)
        val actions = ThreadActions(db, readSync = Rec(provider))

        actions.markRead("+15550100123")

        val byBody = fake.sms.associateBy { it.body }
        assertEquals(1, byBody["a"]!!.read); assertEquals(1, byBody["a"]!!.seen)
        assertEquals(0, byBody["b"]!!.read)
        assertTrue(db.messageDao().messagesForThread("+15550100123").all { it.isRead })
        assertFalse(db.messageDao().messagesForThread("+15550100456").single().isRead)
    }

    @Test
    fun `mark all read mirrors, and a provider failure never breaks the in-app action`() = runTest {
        store().store("5550100123", "a", 1_800_000_000_000L, null, -1)
        ThreadActions(db, readSync = ProviderReadSync(provider)).markAllRead()
        assertEquals(1, fake.sms.single().read)

        store().store("5550100456", "c", 1_800_000_002_000L, null, -1)
        fake.writable = false // role lost: provider throws SecurityException
        ThreadActions(db, readSync = ProviderReadSync(provider)).markRead("+15550100456")
        assertTrue(db.messageDao().messagesForThread("+15550100456").single().isRead)
    }
}
