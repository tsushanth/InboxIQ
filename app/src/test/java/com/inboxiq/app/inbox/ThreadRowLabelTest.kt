package com.inboxiq.app.inbox

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadActions
import com.inboxiq.app.data.ThreadLabelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The inbox row shows the label of the conversation, not of whichever message happens to be newest. History import stores the
 * user's own sent texts as UNLABELED, so a conversation whose last text was a reply used to show "Not sorted yet" even after
 * it was classified or filed by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ThreadRowLabelTest {
    private lateinit var db: AppDatabase
    private val a = "+15550100"

    @Before fun setUp() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build() }
    @After fun tearDown() { db.close() }

    private suspend fun seed() {
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "claim your prize", timestamp = 1_000, isIncoming = true))
        // imported sent text: UNLABELED, newest
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = "who is this?", timestamp = 2_000, isIncoming = false))
    }

    private suspend fun rowLabel(): MessageLabel =
        InboxLogic.join(db.messageDao().observeThreadList().first(), emptyList()).single().label

    @Test fun `a hand-filed label shows on the row even when the newest message is the user's own`() = runBlocking {
        seed()
        ThreadActions(db).correctLabel(a, MessageLabel.SCAM)
        assertEquals(MessageLabel.SCAM, rowLabel())
    }

    @Test fun `a classified conversation shows its label when the newest message is the user's own`() = runBlocking {
        seed()
        db.threadLabelDao().upsert(ThreadLabelEntity(a, MessageLabel.PROMO, 0.8f, 3_000))
        db.messageDao().applyThreadLabel(a, MessageLabel.PROMO, 0.8f)
        assertEquals(MessageLabel.PROMO, rowLabel())
    }
}
