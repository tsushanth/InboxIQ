package com.inboxiq.app.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadLabelEntity
import com.inboxiq.app.data.ThreadStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real worker against an in-memory database. The ONNX runtime has no native library under
 * Robolectric, so these runs exercise the rules-only path (the same one users get if the model cannot
 * load); the model blend itself is covered by the JVM evaluation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ClassifyMessagesWorkerTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var clock = 1_700_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        db.close()
    }

    private suspend fun msg(address: String, body: String, incoming: Boolean = true) {
        db.messageDao().insert(MessageEntity(threadId = 0, address = address, body = body, timestamp = clock++, isIncoming = incoming))
    }

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<ClassifyMessagesWorker>(context).build().doWork()
    }

    private suspend fun threadLabel(address: String) = db.threadLabelDao().get(address)?.label

    @Test fun `a 10,000-message backlog is classified in bounded pages, every incoming message gets a label`() = runBlocking {
        val addresses = mutableListOf<String>()
        // 250 friends: long two-way conversations
        repeat(250) { i ->
            val a = "+1415555%04d".format(i); addresses += a
            repeat(14) { k -> msg(a, "ok see you at $k, bring the thing lol", incoming = true); msg(a, "sounds good", incoming = false) }
        }
        // 150 code senders, 150 promo senders, 100 scammers, 50 spam pitches
        repeat(150) { i -> val a = "%05d".format(20000 + i); addresses += a; repeat(30) { k -> msg(a, "Your Acme code is ${100000 + k * 7}. Do not share it.") } }
        repeat(150) { i -> val a = "%06d".format(300000 + i); addresses += a; repeat(30) { k -> msg(a, "Acme: ${10 + k}% off everything today! Shop example.com/s. Reply STOP to opt out.") } }
        repeat(100) { i -> val a = "+1650555%04d".format(i); addresses += a; repeat(20) { msg(a, "FINAL NOTICE: unpaid toll balance of \$8.75. Pay now to avoid a late fee: https://tollpay-ny.cc/$i") } }
        repeat(50) { i -> val a = "+1737555%04d".format(i); addresses += a; repeat(20) { msg(a, "Are you interested in selling your home? We pay cash. Call 555-0188 for a free quote") } }

        val total = addresses.size
        val messages = db.messageDao().observeAll()
        val start = System.nanoTime()
        val result = run()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("classified $total threads in $ms ms")

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("took $ms ms", ms < 120_000)
        // every address has a thread label, no incoming message is left UNLABELED
        assertEquals(total, addresses.count { threadLabel(it) != null })
        val unlabeledIncoming = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM messages WHERE isIncoming = 1 AND label = 'UNLABELED'").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, unlabeledIncoming)
        assertEquals(MessageLabel.PERSONAL, threadLabel("+14155550003"))
        assertEquals(MessageLabel.OTP_2FA, threadLabel("20003"))
        assertEquals(MessageLabel.PROMO, threadLabel("300003"))
        assertEquals(MessageLabel.SCAM, threadLabel("+16505550003"))
        assertEquals(MessageLabel.SPAM, threadLabel("+17375550003"))
        // none of the 250 friends ended up in junk
        assertEquals(0, addresses.take(250).count { threadLabel(it) in setOf(MessageLabel.SPAM, MessageLabel.SCAM) })
        Unit
    }

    @Test fun `second run does nothing`() = runBlocking {
        msg("+14155550001", "hey")
        run()
        val first = db.threadLabelDao().get("+14155550001")!!
        run()
        assertEquals(first, db.threadLabelDao().get("+14155550001"))
    }

    @Test fun `bare STOP replies do not make a short code a contact, real replies make a number one`() = runBlocking {
        msg("22000", "Acme: Large pizza \$9.99 tonight only! Order: example.com/p. Reply STOP to opt out"); msg("22000", "STOP", incoming = false)
        val pitch = "Are you interested in selling your home? We pay cash. Call 555-0188 for a free quote"
        msg("+14155550100", pitch)
        msg("+14155550101", pitch); repeat(3) { msg("+14155550101", "who is this? how did you get my number", incoming = false) }
        run()
        assertEquals(0, db.threadLabelDao().substantiveReplies("22000"))
        assertEquals(MessageLabel.PROMO, threadLabel("22000"))
        assertEquals(MessageLabel.SPAM, threadLabel("+14155550100"))
        assertEquals("three real replies make it a known contact, never spam", MessageLabel.PERSONAL, threadLabel("+14155550101"))
    }

    @Test fun `upgrade re-sorts labels from the old pipeline once, but never a thread the user filed`() = runBlocking {
        val old = clock - 1_000_000
        msg("+14155550200", "lol see you at dinner"); msg("+14155550201", "lol see you at dinner"); msg("22001", "Your code is 482913")
        // what the old build did: friend filed as spam, code sender as personal, and one thread the user corrected
        db.threadLabelDao().upsert(ThreadLabelEntity("+14155550200", MessageLabel.SPAM, 0.8f, old))
        db.threadLabelDao().upsert(ThreadLabelEntity("+14155550201", MessageLabel.SPAM, 1f, old))
        db.threadStateDao().ensure(ThreadStateEntity("+14155550201", labelCorrected = true))
        db.threadLabelDao().upsert(ThreadLabelEntity("22001", MessageLabel.PERSONAL, 0.9f, old))

        run()
        assertEquals(MessageLabel.PERSONAL, threadLabel("+14155550200"))
        assertEquals("hand-filed thread untouched", MessageLabel.SPAM, threadLabel("+14155550201"))
        assertEquals(MessageLabel.OTP_2FA, threadLabel("22001"))
        assertEquals(old, db.threadLabelDao().get("+14155550201")!!.classifiedAt)
        assertEquals(2, context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE).getInt("classifier_pipeline_version", 0))

        // flipping the stored label by hand afterwards is not undone by a second run (refresh happens once)
        db.threadLabelDao().upsert(ThreadLabelEntity("+14155550200", MessageLabel.WORK, 1f, old))
        run()
        assertEquals(MessageLabel.WORK, threadLabel("+14155550200"))
    }

    @Test fun `a thread with no incoming messages left is touched, not retried forever`() = runBlocking {
        val old = clock - 1000
        msg("+14155550300", "bye", incoming = false)
        db.threadLabelDao().upsert(ThreadLabelEntity("+14155550300", MessageLabel.PERSONAL, 0.7f, old))
        run()
        assertTrue(db.threadLabelDao().get("+14155550300")!!.classifiedAt > old)
        assertEquals(2, context.getSharedPreferences("inboxiq_prefs", Context.MODE_PRIVATE).getInt("classifier_pipeline_version", 0))
    }
}
