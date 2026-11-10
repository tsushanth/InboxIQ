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

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class LegacyAutoHealTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() = db.close()

    private suspend fun queued(ts: Long) = db.messageDao().insert(
        MessageEntity(threadId = 0, address = "+15550100", body = "pic", timestamp = ts, isIncoming = false, sendStatus = SendStatus.FAILED, awaitingAutoHeal = true),
    )

    @Test fun `queued retries from earlier builds are dropped once, the rows stay failed`() = runTest {
        val id = queued(1000)
        assertEquals(1, LegacyAutoHeal.clearOnce(context, db.messageDao()))
        val m = db.messageDao().getById(id)!!
        assertFalse(m.awaitingAutoHeal)
        assertEquals(SendStatus.FAILED, m.sendStatus)
    }

    @Test fun `a retry queued after the clean-up is left alone`() = runTest {
        LegacyAutoHeal.clearOnce(context, db.messageDao())
        val id = queued(2000)
        assertEquals(0, LegacyAutoHeal.clearOnce(context, db.messageDao()))
        assertTrue(db.messageDao().getById(id)!!.awaitingAutoHeal)
    }

    @Test fun `numbers match across a missing country prefix but not across different subscribers`() {
        assertTrue(SentMmsLookup.sameNumber("14085550123", "4085550123"))
        assertTrue(SentMmsLookup.sameNumber("14085550123", "14085550123"))
        assertFalse(SentMmsLookup.sameNumber("14085550123", "14085550124"))
        assertFalse(SentMmsLookup.sameNumber("", "14085550123"))
        assertFalse(SentMmsLookup.sameNumber("12345", "99912345"))
    }
}
