package com.inboxiq.app.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AutoHealRoleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    private fun setInstance(v: AppDatabase?) = AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, v)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        setInstance(db)
    }

    @After fun tearDown() { setInstance(null); AutoHealWorker.isDefaultSms = { com.inboxiq.app.sms.DefaultSmsRole.isDefault(it) }; AutoHealWorker.hasSentCopy = { c, a, t -> com.inboxiq.app.sms.SentMmsLookup.hasSentCopy(c, a, t) }; db.close() }

    private suspend fun failedMms(): Long = db.messageDao().insert(
        MessageEntity(threadId = 0, address = "+15550100", body = "pic", timestamp = 1000, isIncoming = false, sendStatus = SendStatus.FAILED,
            imagePartUri = "content://mms/part/1", awaitingAutoHeal = true),
    )

    @Test fun `a retry is never attempted while InboxIQ is not the default SMS app`() = runTest {
        AutoHealWorker.isDefaultSms = { false }
        val id = failedMms()
        val result = TestListenableWorkerBuilder<AutoHealWorker>(context).build().doWork()
        assertTrue(result is ListenableWorker.Result.Success)
        val row = db.messageDao().getById(id)!!
        assertTrue("still waiting for a retry once the role is back", row.awaitingAutoHeal)
        assertEquals(0, row.autoHealRetryCount)
    }

    @Test fun `as the default app the retry runs`() = runTest {
        AutoHealWorker.isDefaultSms = { true }
        val id = failedMms()
        TestListenableWorkerBuilder<AutoHealWorker>(context).build().doWork()
        // Nothing can be sent under test, so the attempt fails and is counted: that proves the retry path was entered.
        assertEquals(1, db.messageDao().getById(id)!!.autoHealRetryCount)
    }

    @Test fun `a message the provider already holds as sent is settled and never re-sent`() = runTest {
        AutoHealWorker.isDefaultSms = { true }
        AutoHealWorker.hasSentCopy = { _, _, _ -> true }
        val id = failedMms()
        TestListenableWorkerBuilder<AutoHealWorker>(context).build().doWork()
        val row = db.messageDao().getById(id)!!
        assertEquals(SendStatus.SENT, row.sendStatus)
        assertTrue(!row.awaitingAutoHeal)
        assertEquals("no send was attempted", 0, row.autoHealRetryCount)
    }

    @Test fun `a message already SENT is never re-sent even if it is still flagged`() = runTest {
        AutoHealWorker.isDefaultSms = { true }
        AutoHealWorker.hasSentCopy = { _, _, _ -> false }
        val id = failedMms()
        db.messageDao().updateSendStatus(id, SendStatus.SENT)
        TestListenableWorkerBuilder<AutoHealWorker>(context).build().doWork()
        assertEquals("no send was attempted", 0, db.messageDao().getById(id)!!.autoHealRetryCount)
    }
}
