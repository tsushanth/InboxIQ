package com.inboxiq.app.sms

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.inboxiq.app.classify.ClassificationResult
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadLabelEntity
import com.inboxiq.app.data.ThreadStateEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class IncomingLabelerTest {
    private lateinit var db: AppDatabase
    private val a = "+15550111"
    private var modelCalls = 0
    private var enqueued = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private suspend fun incoming(body: String): Long =
        db.messageDao().insert(MessageEntity(threadId = 0, address = a, body = body, timestamp = 1_000, isIncoming = true, isRead = false))

    private suspend fun resolve(body: String, id: Long, model: suspend (String) -> ClassificationResult?) =
        IncomingLabeler.resolve(db, a, body, 1_000, id, { modelCalls++; model(it) }, { enqueued++ })

    @Test fun `new sender with no rule match gets the model label before notifying`() = runTest {
        val id = incoming("see you at lunch")
        val label = resolve("see you at lunch", id) { ClassificationResult(MessageLabel.PERSONAL, 0.9f) }
        assertEquals(MessageLabel.PERSONAL, label)
        assertEquals(MessageLabel.PERSONAL, db.threadLabelDao().get(a)?.label)
        assertEquals(MessageLabel.PERSONAL, db.messageDao().getById(id)?.label)
        assertEquals(0, enqueued)
    }

    @Test fun `model timeout or absence falls back to unlabeled plus the background worker`() = runTest {
        val id = incoming("see you at lunch")
        val label = resolve("see you at lunch", id) { null }
        assertEquals(MessageLabel.UNLABELED, label)
        assertNull(db.threadLabelDao().get(a))
        assertEquals(1, enqueued)
    }

    @Test fun `a throwing model never breaks delivery`() = runTest {
        val id = incoming("hello")
        val label = resolve("hello", id) { error("model failed to load") }
        assertEquals(MessageLabel.UNLABELED, label)
        assertEquals(1, enqueued)
    }

    @Test fun `known sender does not call the model`() = runTest {
        db.threadLabelDao().upsert(ThreadLabelEntity(a, MessageLabel.WORK, 0.8f, 1))
        val id = incoming("standup moved")
        assertEquals(MessageLabel.WORK, resolve("standup moved", id) { ClassificationResult(MessageLabel.SPAM, 1f) })
        assertEquals(0, modelCalls)
    }

    @Test fun `heuristic wins without calling the model`() = runTest {
        val id = incoming("Your verification code is 483920")
        assertEquals(MessageLabel.OTP_2FA, resolve("Your verification code is 483920", id) { ClassificationResult(MessageLabel.SPAM, 1f) })
        assertEquals(0, modelCalls)
    }

    @Test fun `user-corrected sender keeps the cached label over a heuristic`() = runTest {
        db.threadLabelDao().upsert(ThreadLabelEntity(a, MessageLabel.PERSONAL, 1f, 1))
        db.threadStateDao().ensure(ThreadStateEntity(address = a, labelCorrected = true))
        val id = incoming("Your verification code is 483920")
        assertEquals(MessageLabel.PERSONAL, resolve("Your verification code is 483920", id) { null })
    }
}
