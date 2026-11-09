package com.inboxiq.app.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** A multipart text calls back once per part; these pin how the per-part results combine. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SmsSentSettleTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private suspend fun pending(body: String = "hello"): Long =
        db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100", body = body, timestamp = 1000, isIncoming = false, sendStatus = SendStatus.PENDING))

    private suspend fun status(id: Long) = db.messageDao().getById(id)!!.sendStatus

    @Test fun `all parts ok means sent`() = runTest {
        val id = pending()
        assertTrue(SmsSentReceiver.settle(db.messageDao(), id, ok = true))
        assertTrue(SmsSentReceiver.settle(db.messageDao(), id, ok = true))
        assertEquals(SendStatus.SENT, status(id))
    }

    @Test fun `a failed first part is not hidden by a later ok part`() = runTest {
        val id = pending()
        assertFalse(SmsSentReceiver.settle(db.messageDao(), id, ok = false))
        assertFalse(SmsSentReceiver.settle(db.messageDao(), id, ok = true))
        assertEquals(SendStatus.FAILED, status(id))
    }

    @Test fun `a failure after an ok part still fails the message`() = runTest {
        val id = pending()
        assertTrue(SmsSentReceiver.settle(db.messageDao(), id, ok = true))
        assertFalse(SmsSentReceiver.settle(db.messageDao(), id, ok = false))
        assertEquals(SendStatus.FAILED, status(id))
    }

    @Test fun `a result for a message the user deleted meanwhile still reports the radio result`() = runTest {
        val id = pending()
        db.messageDao().deleteMessage(id)
        assertTrue(SmsSentReceiver.settle(db.messageDao(), id, ok = true))
        assertFalse(SmsSentReceiver.settle(db.messageDao(), id, ok = false))
    }

    @Test fun `any order of part results ends failed if and only if some part failed`() = runTest {
        val rnd = Random(7)
        repeat(200) {
            val id = pending("m$it")
            val results = List(rnd.nextInt(1, 6)) { rnd.nextInt(4) != 0 }
            results.forEach { SmsSentReceiver.settle(db.messageDao(), id, it) }
            assertEquals(results.toString(), if (results.all { r -> r }) SendStatus.SENT else SendStatus.FAILED, status(id))
        }
    }
}
