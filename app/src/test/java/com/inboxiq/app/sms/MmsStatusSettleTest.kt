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

/**
 * The platform's result code decides whether an MMS was sent. Regression: the receiver used to read the provider
 * row back and call a carrier-accepted MMS failed when that row was missing or still in the outbox, which then
 * triggered automatic re-sends (duplicates for the recipient).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsStatusSettleTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private suspend fun pending(): Long =
        db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550100", body = "hi", timestamp = 1000, isIncoming = false, sendStatus = SendStatus.PENDING))

    @Test fun `a result code of OK marks the message sent with no auto retry queued`() = runTest {
        val id = pending()
        MmsStatusReceiver.settle(db.messageDao(), id, sent = true)
        val m = db.messageDao().getById(id)!!
        assertEquals(SendStatus.SENT, m.sendStatus)
        assertFalse(m.awaitingAutoHeal)
    }

    @Test fun `an error result code marks the message failed and queues one auto retry`() = runTest {
        val id = pending()
        MmsStatusReceiver.settle(db.messageDao(), id, sent = false)
        val m = db.messageDao().getById(id)!!
        assertEquals(SendStatus.FAILED, m.sendStatus)
        assertTrue(m.awaitingAutoHeal)
    }

    @Test fun `a retry that the carrier accepts clears the failed state and the retry flag`() = runTest {
        val id = pending()
        MmsStatusReceiver.settle(db.messageDao(), id, sent = false)
        MmsStatusReceiver.settle(db.messageDao(), id, sent = true)
        val m = db.messageDao().getById(id)!!
        assertEquals(SendStatus.SENT, m.sendStatus)
        assertFalse(m.awaitingAutoHeal)
    }
}
