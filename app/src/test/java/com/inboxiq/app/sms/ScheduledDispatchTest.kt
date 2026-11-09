package com.inboxiq.app.sms

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
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

/** Scheduled and undo-window sends: claimed once, and recorded in the thread in the same step. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ScheduledDispatchTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        FakeTelephony().register()
        setInstance(db)
    }

    @After fun tearDown() { setInstance(null); ScheduledSend.isDefaultSms = { DefaultSmsRole.isDefault(it) }; db.close() }

    private fun setInstance(v: AppDatabase?) = AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, v)

    @Test fun `dispatch removes the queued row and records exactly one outgoing message`() = runTest {
        val id = ScheduledStore(db).create("+15550100", "see you at 6", 1L, isUndoWindow = false)

        // Robolectric has no SmsManager, so the radio hand-off throws AFTER the message was recorded.
        runCatching { ScheduledSend.dispatch(context, id) }

        assertTrue("queue row must be gone", db.scheduledMessageDao().all().isEmpty())
        val sent = db.messageDao().pageAfter(0, 10).single()
        assertEquals("see you at 6", sent.body)
        assertFalse(sent.isIncoming)
    }

    @Test fun `when InboxIQ is no longer the default app nothing is sent, the text is kept as not delivered`() = runTest {
        ScheduledSend.isDefaultSms = { false }
        val id = ScheduledStore(db).create("+15550100", "later text", 1L, isUndoWindow = false)

        assertTrue(ScheduledSend.dispatch(context, id)) // no exception: the hand-off to the radio never happened

        val row = db.messageDao().pageAfter(0, 10).single()
        assertEquals("later text", row.body)
        assertEquals(com.inboxiq.app.data.SendStatus.FAILED, row.sendStatus)
        assertTrue(db.scheduledMessageDao().all().isEmpty())
    }

    @Test fun `a second dispatch of the same row does nothing`() = runTest {
        val id = ScheduledStore(db).create("+15550100", "once", 1L, isUndoWindow = true)
        runCatching { ScheduledSend.dispatch(context, id) }
        assertFalse(ScheduledSend.dispatch(context, id))
        assertEquals(1, db.messageDao().count())
    }

    @Test fun `a cancelled row is never sent`() = runTest {
        val id = ScheduledStore(db).create("+15550100", "oops", 1L, isUndoWindow = true)
        assertTrue(ScheduledStore(db).cancel(id))
        assertFalse(ScheduledSend.dispatch(context, id))
        assertEquals(0, db.messageDao().count())
    }
}
