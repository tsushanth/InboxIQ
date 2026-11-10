package com.inboxiq.app.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two identical texts stamped in the same millisecond must both be stored (the unique index would drop the second). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SmsSenderUniqueTest {
    private lateinit var db: AppDatabase

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { db.close() }

    private fun out(ts: Long, body: String = "ok") =
        MessageEntity(threadId = 0, address = "+15550100", body = body, timestamp = ts, isIncoming = false, sendStatus = SendStatus.PENDING)

    @Test fun `identical sends in the same millisecond are both kept, on distinct timestamps`() = runTest {
        val dao = db.messageDao()
        val (id1, ts1) = SmsSender.insertUnique(dao, out(5_000))
        val (id2, ts2) = SmsSender.insertUnique(dao, out(5_000))
        val (id3, ts3) = SmsSender.insertUnique(dao, out(5_000))
        assertEquals(listOf(5_000L, 5_001L, 5_002L), listOf(ts1, ts2, ts3))
        assertEquals(3, setOf(id1, id2, id3).size)
        assertEquals(3, dao.count())
        assertEquals(listOf(5_000L, 5_001L, 5_002L), dao.messagesForThread("+15550100").map { it.timestamp })
    }

    @Test fun `a different body at the same millisecond is not bumped`() = runTest {
        val dao = db.messageDao()
        SmsSender.insertUnique(dao, out(7_000, "a"))
        val (_, ts) = SmsSender.insertUnique(dao, out(7_000, "b"))
        assertEquals(7_000L, ts)
        assertNotEquals(0, dao.count())
    }
}
