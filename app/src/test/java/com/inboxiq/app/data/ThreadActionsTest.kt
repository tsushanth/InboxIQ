package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThreadActionsTest {
    private lateinit var db: AppDatabase
    private lateinit var actions: ThreadActions
    private val deletedFiles = mutableListOf<String>()
    private val a = "+15550100"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        actions = ThreadActions(db, { deletedFiles += it }, { 1_000L })
    }

    @After fun tearDown() = db.close()

    private suspend fun seed() {
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "one", timestamp = 1, isIncoming = true, isRead = false))
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "pic", timestamp = 2, isIncoming = true, imagePartUri = "content://x/mms_images/1.jpg"))
        db.messageDao().insert(MessageEntity(threadId = 0, address = "+15550199", body = "other", timestamp = 3, isIncoming = true))
    }

    @Test fun `delete then undo restores messages with original ids and state`() = runTest {
        seed()
        actions.setPinned(a, true)
        actions.setMutedUntil(a, 9_999)
        val before = db.messageDao().messagesForThread(a)
        val snap = actions.delete(a)
        assertTrue(db.messageDao().messagesForThread(a).isEmpty())
        assertNull(db.threadStateDao().get(a))
        assertEquals(1, db.messageDao().messagesForThread("+15550199").size) // other thread untouched
        assertTrue("files stay until the undo window closes", deletedFiles.isEmpty())

        actions.restore(snap)
        assertEquals(before, db.messageDao().messagesForThread(a))
        assertNotNull(db.threadStateDao().get(a)!!.pinnedAt)
        assertEquals(9_999L, db.threadStateDao().get(a)!!.mutedUntil)
    }

    @Test fun `commit removes attachment files only for the deleted thread`() = runTest {
        seed()
        val snap = actions.delete(a)
        actions.commitDelete(snap)
        assertEquals(listOf("content://x/mms_images/1.jpg"), deletedFiles)
    }

    @Test fun `archive and unarchive round trip`() = runTest {
        actions.setArchived(a, true)
        assertTrue(db.threadStateDao().get(a)!!.archived)
        actions.setArchived(a, false)
        assertFalse(db.threadStateDao().get(a)!!.archived)
    }

    @Test fun `correcting a label relabels existing incoming messages and sticks`() = runTest {
        seed()
        actions.correctLabel(a, MessageLabel.WORK)
        assertTrue(db.messageDao().messagesForThread(a).all { it.label == MessageLabel.WORK })
        assertEquals(MessageLabel.WORK, db.threadLabelDao().get(a)!!.label)
        assertTrue(db.threadStateDao().get(a)!!.labelCorrected)
    }

    @Test fun `report spam can also block`() = runTest {
        seed()
        actions.reportSpam(a, alsoBlock = true)
        assertEquals(MessageLabel.SPAM, db.threadLabelDao().get(a)!!.label)
        assertTrue(db.blockedNumberDao().isBlocked(a))
        actions.setBlocked(a, false)
        assertFalse(db.blockedNumberDao().isBlocked(a))
    }

    @Test fun `mark all read clears every thread`() = runTest {
        seed()
        actions.markAllRead()
        assertTrue(db.messageDao().observeThreadList().first().all { it.unreadCount == 0 })
    }

    @Test fun `literal search treats percent as a character`() = runTest {
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "save 50% today", timestamp = 1, isIncoming = true))
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "lunch", timestamp = 2, isIncoming = true))
        val hits = db.messageDao().searchMessagesLiteral(com.inboxiq.app.inbox.SearchLogic.escapeLike("%")).first()
        assertEquals(listOf("save 50% today"), hits.map { it.body })
    }

    @Test fun `saving a blank draft creates no state row, a real draft does, and blank clears it`() = runTest {
        actions.saveDraft(a, "")
        assertNull(db.threadStateDao().get(a))
        actions.saveDraft(a, "on my way")
        assertEquals("on my way", db.threadStateDao().get(a)!!.draft)
        actions.saveDraft(a, "")
        assertNull(db.threadStateDao().get(a)!!.draft)
    }

    @Test fun `single message delete can be undone and only commit removes its file`() = runTest {
        seed()
        val pic = db.messageDao().messagesForThread(a).first { it.imagePartUri != null }
        val removed = actions.deleteMessage(pic.id)!!
        assertNull(db.messageDao().getById(pic.id))
        actions.restoreMessage(removed)
        assertEquals(pic, db.messageDao().getById(pic.id))
        actions.commitMessageDelete(removed)
        assertEquals(listOf(pic.imagePartUri), deletedFiles)
    }
}
