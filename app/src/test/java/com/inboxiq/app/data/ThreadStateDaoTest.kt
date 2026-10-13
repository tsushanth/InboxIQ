package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThreadStateDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ThreadStateDao
    private val a = "+15550100"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.threadStateDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `pin archive mute persist independently and survive each other`() = runTest {
        dao.setPinned(a, true, 100)
        dao.setArchived(a, true)
        dao.setMutedUntil(a, 5_000)
        val s = dao.get(a)!!
        assertEquals(100L, s.pinnedAt)
        assertTrue(s.archived)
        assertTrue(s.isMuted(4_999))
        assertFalse(s.isMuted(5_000))
        dao.setPinned(a, false, 200)
        assertNull(dao.get(a)!!.pinnedAt)
        assertTrue(dao.get(a)!!.archived)
    }

    @Test fun `mute forever uses MAX_VALUE`() = runTest {
        dao.setMutedUntil(a, Long.MAX_VALUE)
        assertTrue(dao.isMuted(a, System.currentTimeMillis()))
        dao.setMutedUntil(a, null)
        assertFalse(dao.isMuted(a, System.currentTimeMillis()))
    }

    @Test fun `blank draft clears the draft`() = runTest {
        dao.setDraft(a, "running late")
        assertEquals("running late", dao.get(a)!!.draft)
        dao.setDraft(a, "   ")
        assertNull(dao.get(a)!!.draft)
    }

    @Test fun `observeAll emits updates`() = runTest {
        dao.setArchived(a, true)
        assertEquals(listOf(a), dao.observeAll().first().map { it.address })
    }

    @Test fun `scheduled message delete reports whether it won the race`() = runTest {
        val sdao = db.scheduledMessageDao()
        val id = sdao.insert(ScheduledMessageEntity(address = a, body = "hi", sendAt = 10, createdAt = 1))
        assertEquals(1, sdao.delete(id))
        assertEquals(0, sdao.delete(id))
        assertNull(sdao.get(id))
    }
}
