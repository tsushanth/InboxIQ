package com.inboxiq.app.autoreply

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.BlockedNumberEntity
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** End to end through the real worker, database and store; only the radio (SmsSender) is faked. 555-01xx numbers only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AutoResponderTest {
    private lateinit var db: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<Pair<String, String>>()
    private var now = 1_800_000_000_000L
    private var nextTs = 1_000L
    private val friend = "+14155550101"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, db)
        context.getSharedPreferences(AutoReplyStore.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        AutoResponder.resetForTests()
        AutoResponder.clock = { now }
        AutoResponder.zone = { ZoneId.of("UTC") }
        AutoResponder.isDefaultSms = { true }
        AutoResponder.importRunning = { false }
        AutoResponder.savedContact = { _, _ -> false }
        AutoResponder.sender = AutoReplySender { c, a, b ->
            sent += a to b
            db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = b, timestamp = now, isIncoming = false, label = MessageLabel.PERSONAL))
        }
    }

    @After fun tearDown() {
        AutoResponder.resetForTests()
        AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        db.close()
    }

    private val store get() = AutoReplyStore(context)

    private fun turnOn(message: String = AutoReplyConfig.TEMPLATES[0], tweak: (AutoReplyConfig) -> AutoReplyConfig = { it }) {
        store.save(tweak(AutoReplyConfig(message = message)))
        assertTrue(store.enable(AutoReplyConfirmation.text(store.config())))
    }

    private fun incoming(address: String = friend, body: String = "are you around later?", label: MessageLabel = MessageLabel.PERSONAL, age: Long = 5_000): Long = runBlocking {
        db.messageDao().insert(MessageEntity(threadId = 0, address = address, body = body, timestamp = now - age + (nextTs++), isIncoming = true, label = label))
    }

    private fun work(address: String, id: Long): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<AutoReplyWorker>(context)
            .setInputData(Data.Builder().putString(AutoResponder.KEY_ADDRESS, address).putLong(AutoResponder.KEY_MESSAGE_ID, id).build())
            .build().doWork()
    }

    private fun haveTexted(address: String = friend) = runBlocking {
        db.messageDao().insert(MessageEntity(threadId = 0, address = address, body = "see you soon", timestamp = 10, isIncoming = false))
    }

    @Test fun `incoming, eligible, exactly one auto reply, a second within 24h gets none`() {
        haveTexted()
        turnOn()
        val first = incoming(); work(friend, first)
        assertEquals(listOf(friend to AutoReplyConfig.TEMPLATES[0]), sent)
        val log = store.log(); assertEquals(1, log.size)
        assertTrue("marked as sent automatically", store.sentIds().contains(log[0].messageId))

        now += 3_600_000 // an hour later
        val second = incoming(body = "hello??"); work(friend, second)
        assertEquals("still one", 1, sent.size)

        now += 25 * 3_600_000L // next day
        val third = incoming(body = "you there?"); work(friend, third)
        assertEquals(2, sent.size)
    }

    @Test fun `off by default means nothing is sent, and the feature does not even enqueue`() {
        haveTexted()
        val id = incoming(); work(friend, id)
        assertTrue(sent.isEmpty())
        AutoResponder.onIncoming(context, friend, id) // must not throw or enqueue when off
    }

    @Test fun `a first time sender, a business, a group and a blocked number get nothing`() {
        turnOn()
        work("+14155550150", incoming("+14155550150"))
        haveTexted("55123"); work("55123", incoming("55123"))
        haveTexted("+14155550101,+14155550102"); work("+14155550101,+14155550102", incoming("+14155550101,+14155550102"))
        haveTexted("+14155550151"); runBlocking { db.blockedNumberDao().block(BlockedNumberEntity("+14155550151", 1)) }; work("+14155550151", incoming("+14155550151"))
        haveTexted("+14155550152"); work("+14155550152", incoming("+14155550152", label = MessageLabel.PROMO))
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test fun `a saved contact is answered even if never texted`() {
        AutoResponder.savedContact = { _, a -> a == friend }
        turnOn(); work(friend, incoming())
        assertEquals(1, sent.size)
    }

    @Test fun `our own automatic text does not make a stranger a known contact`() {
        AutoResponder.savedContact = { _, _ -> false }
        turnOn()
        val a = "+14155550160"
        // pretend an old automatic message to a stranger exists in the thread
        val autoId = runBlocking { db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "auto", timestamp = 5, isIncoming = false)) }
        store.record(AutoReplyLogEntry(1, a, autoId))
        work(a, incoming(a))
        assertTrue(sent.isEmpty())
    }

    @Test fun `old texts, imported history and a running importer are ignored`() {
        haveTexted(); turnOn()
        work(friend, incoming(age = 10 * 60_000))
        assertTrue(sent.isEmpty())
        AutoResponder.importRunning = { true }
        work(friend, incoming()); assertTrue(sent.isEmpty())
    }

    @Test fun `not the default sms app means nothing is sent`() {
        haveTexted(); turnOn(); AutoResponder.isDefaultSms = { false }
        work(friend, incoming()); assertTrue(sent.isEmpty())
    }

    @Test fun `daily cap stops a flood from many senders`() {
        turnOn { it.copy(dailyCap = 3) }
        for (i in 0 until 6) { val a = "+141555502%02d".format(i); haveTexted(a); work(a, incoming(a)) }
        assertEquals(3, sent.size)
    }

    @Test fun `texts that look automatic are not answered, so two auto responders cannot ping-pong`() {
        haveTexted(); turnOn()
        work(friend, incoming(body = AutoReplyConfig.TEMPLATES[0]))
        work(friend, incoming(body = "Automatic reply: I am busy right now and will reply later."))
        assertTrue(sent.isEmpty())
    }

    @Test fun `the on-switch only turns on for the exact confirmed text and editing switches it off`() {
        store.save(AutoReplyConfig(message = "I am busy right now and will reply later."))
        assertFalse(store.enable("some other text"))
        assertFalse(store.config().enabled)
        assertTrue(store.enable(AutoReplyConfirmation.text(store.config())))
        store.save(store.config().copy(message = "Be back soon."))
        assertFalse("editing the text requires confirming again", store.config().enabled)
        assertFalse(store.enable(AutoReplyConfirmation.text(AutoReplyConfig(message = "something else"))))
        store.save(AutoReplyConfig(message = "link http://example.com"))
        assertFalse("invalid message cannot be enabled", store.enable(AutoReplyConfirmation.text(store.config())))
    }

    @Test fun `an until time that has passed turns it off`() {
        haveTexted(); turnOn { it.copy(untilMillis = now + 1_000) }
        now += 2_000
        work(friend, incoming()); assertTrue(sent.isEmpty()); assertFalse(store.config().enabled)
    }

    @Test fun `one tap turn off from the log stops further sends`() {
        haveTexted(); turnOn()
        work(friend, incoming()); assertEquals(1, sent.size)
        store.disable()
        now += 30 * 3_600_000L
        work(friend, incoming()); assertEquals(1, sent.size)
    }

    @Test fun `release build with the flag off is inert`() {
        haveTexted(); turnOn(); AutoResponder.buildEnabledOverride = false
        work(friend, incoming()); assertTrue(sent.isEmpty())
    }

    @Test fun `a failed send does not use up the sender's slot`() {
        haveTexted(); turnOn()
        AutoResponder.sender = AutoReplySender { _, _, _ -> throw IllegalStateException("radio off") }
        work(friend, incoming()); assertEquals(0, store.log().size)
        AutoResponder.sender = AutoReplySender { _, a, b -> sent += a to b; 7L }
        now += 120_000
        work(friend, incoming(body = "hello?")); assertEquals(1, sent.size)
    }
}
