package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for a crash confirmed live: observeThreadList() is meant to return one row
 * per address, but its INNER JOIN on (address, MAX(timestamp)) matches *every* row that shares
 * the max timestamp for that address — normally one row, but a real multi-part MMS (an image
 * part and a text part landing as two separate provider rows from the same conversation turn)
 * can genuinely produce two messages with the identical timestamp. That handed the thread-list
 * LazyColumn two items with the same key (the address), which Compose treats as a hard crash:
 * "Key ... was already used."
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MessageDaoThreadListTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `two messages sharing the exact same max timestamp still produce one thread-list row`() = runTest {
        val sharedTimestamp = 1786849693000L
        dao.insert(
            MessageEntity(
                threadId = 0, address = "+18136000258", body = "K Pinky, Pokey",
                timestamp = sharedTimestamp, isIncoming = true,
            ),
        )
        dao.insert(
            MessageEntity(
                threadId = 0, address = "+18136000258", body = "",
                timestamp = sharedTimestamp, isIncoming = true,
                imagePartUri = "content://mms/part/7886",
            ),
        )

        val threads = dao.observeThreadList().first()
        val forThisAddress = threads.filter { it.latestMessage.address == "+18136000258" }

        assertEquals(
            "Two messages with the same address and identical timestamp produced more than " +
                "one thread-list row — this is exactly what crashed the LazyColumn " +
                "(duplicate key) when Anna's multi-part MMS was imported live.",
            1,
            forThisAddress.size,
        )
    }

    @Test
    fun `distinct addresses still each get their own row`() = runTest {
        dao.insert(MessageEntity(threadId = 0, address = "+18136000258", body = "hi", timestamp = 1000L, isIncoming = true))
        dao.insert(MessageEntity(threadId = 0, address = "+14256284887", body = "hey", timestamp = 2000L, isIncoming = true))

        val threads = dao.observeThreadList().first()

        assertEquals(2, threads.size)
    }

    @Test
    fun `the surviving row for a timestamp tie still reflects the true unread count`() = runTest {
        val sharedTimestamp = 1786849693000L
        dao.insert(
            MessageEntity(
                threadId = 0, address = "+18136000258", body = "text part",
                timestamp = sharedTimestamp, isIncoming = true, isRead = false,
            ),
        )
        dao.insert(
            MessageEntity(
                threadId = 0, address = "+18136000258", body = "",
                timestamp = sharedTimestamp, isIncoming = true, isRead = false,
                imagePartUri = "content://mms/part/7886",
            ),
        )

        val threads = dao.observeThreadList().first()
        val thread = threads.single { it.latestMessage.address == "+18136000258" }

        // The unread subquery counts across ALL rows for the address, not just the one that
        // survived the tie-break — collapsing to one display row must not also silently
        // undercount what's actually unread.
        assertEquals(2, thread.unreadCount)
    }
}
