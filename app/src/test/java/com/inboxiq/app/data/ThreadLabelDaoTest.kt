package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThreadLabelDaoTest {
    private lateinit var db: AppDatabase
    private var ts = 1000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun msg(a: String, body: String, incoming: Boolean = true) {
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = body, timestamp = ts++, isIncoming = incoming))
    }

    @Test fun `recent incoming bodies are newest first, limited, and exclude the user's own messages`() = runTest {
        msg("a", "one"); msg("a", "mine", incoming = false); msg("a", "two"); msg("a", "three"); msg("b", "other")
        assertEquals(listOf("three", "two"), db.threadLabelDao().recentIncomingBodies("a", 2))
        assertEquals(listOf("three", "two", "one"), db.threadLabelDao().recentIncomingBodies("a", 10))
    }

    @Test fun `substantive replies ignore opt-outs and button presses`() = runTest {
        for (b in listOf("STOP", " stop ", "Y", "yes", "1", "C", "Unsubscribe")) msg("a", b, incoming = false)
        msg("a", "who is this?", incoming = false); msg("a", "ok thanks", incoming = false); msg("a", "incoming", incoming = true)
        assertEquals(2, db.threadLabelDao().substantiveReplies("a"))
        assertEquals(0, db.threadLabelDao().substantiveReplies("nobody"))
    }

    @Test fun `stale addresses exclude fresh labels and hand-filed threads`() = runTest {
        val dao = db.threadLabelDao()
        dao.upsert(ThreadLabelEntity("old", MessageLabel.SPAM, 0.8f, 10))
        dao.upsert(ThreadLabelEntity("fresh", MessageLabel.SPAM, 0.8f, 5000))
        dao.upsert(ThreadLabelEntity("filed", MessageLabel.SPAM, 1f, 10))
        db.threadStateDao().ensure(ThreadStateEntity("filed", labelCorrected = true))
        db.threadStateDao().ensure(ThreadStateEntity("old", pinnedAt = 3L)) // state row without a correction
        assertEquals(listOf("old"), dao.staleAddresses(1000, 10))
        dao.touch("old", 2000)
        assertEquals(emptyList<String>(), dao.staleAddresses(1000, 10))
    }

    @Test fun `upsertAll replaces and latest incoming id skips outgoing`() = runTest {
        val dao = db.threadLabelDao()
        dao.upsertAll(listOf(ThreadLabelEntity("a", MessageLabel.PERSONAL, 0.5f, 1), ThreadLabelEntity("a", MessageLabel.WORK, 0.9f, 2)))
        assertEquals(MessageLabel.WORK, dao.get("a")!!.label)
        msg("a", "in"); msg("a", "out", incoming = false)
        val id = dao.latestIncomingMessageId("a")!!
        assertEquals("in", db.messageDao().getById(id)!!.body)
    }
}
