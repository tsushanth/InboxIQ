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

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AgentDraftClaimTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: AgentDraftDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.agentDraftDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun draft(id: String) =
        dao.insert(AgentDraftEntity(id, "+14155551234", null, "hi", 1L, AgentDraftStatus.PENDING))

    @Test
    fun `only the first claim wins so a retry cannot send twice`() = runTest {
        draft("a")
        assertEquals(1, dao.claimForSending("a"))
        assertEquals(0, dao.claimForSending("a"))
        assertEquals(AgentDraftStatus.SENDING, dao.findById("a")!!.status)
    }

    @Test
    fun `sent and discarded drafts cannot be claimed`() = runTest {
        draft("s"); dao.setStatus("s", AgentDraftStatus.SENT)
        draft("d"); dao.setStatus("d", AgentDraftStatus.DISCARDED)
        assertEquals(0, dao.claimForSending("s"))
        assertEquals(0, dao.claimForSending("d"))
    }

    @Test
    fun `a stuck SENDING draft stays visible to the user`() = runTest {
        draft("x"); dao.claimForSending("x")
        assertEquals(listOf("x"), dao.observePending().first().map { it.id })
    }
}
