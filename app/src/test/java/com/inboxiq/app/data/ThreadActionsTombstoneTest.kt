package com.inboxiq.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.sms.ImportTombstones
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThreadActionsTombstoneTest {
    private lateinit var db: AppDatabase
    private lateinit var tombstones: ImportTombstones
    private lateinit var actions: ThreadActions
    private val a = "+15550100"

    @Before fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        tombstones = ImportTombstones(ctx.getSharedPreferences("tomb_test", Context.MODE_PRIVATE))
        actions = ThreadActions(db, tombstones = tombstones)
    }

    @After fun tearDown() = db.close()

    private suspend fun seed() {
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "one", timestamp = 10, isIncoming = true))
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "two", timestamp = 20, isIncoming = true))
    }

    @Test fun `deleting a conversation records a tombstone up to its newest message, undo clears it`() = runTest {
        seed()
        val snap = actions.delete(a)
        assertTrue(tombstones.isSuppressed(a, 10, "one"))
        assertTrue(tombstones.isSuppressed(a, 20, "two"))
        assertFalse(tombstones.isSuppressed(a, 21, "later"))
        actions.restore(snap)
        assertFalse(tombstones.isSuppressed(a, 10, "one"))
    }

    @Test fun `deleting one message records it, undo clears it`() = runTest {
        seed()
        val id = db.messageDao().messagesForThread(a).first().id
        val m = actions.deleteMessage(id)!!
        assertTrue(tombstones.isSuppressed(a, 10, "one"))
        assertFalse(tombstones.isSuppressed(a, 20, "two"))
        actions.restoreMessage(m)
        assertFalse(tombstones.isSuppressed(a, 10, "one"))
    }
}
