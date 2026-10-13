package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class BackupTest {
    private lateinit var src: AppDatabase
    private lateinit var dst: AppDatabase

    private fun mem() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()

    @Before fun setUp() { src = mem(); dst = mem() }
    @After fun tearDown() { src.close(); dst.close() }

    private suspend fun seed() {
        val d = src.messageDao()
        d.insert(MessageEntity(threadId = 9, address = "+15550100", body = "Lunch \"today\"? é中\n2nd line", timestamp = 10, isIncoming = true, label = MessageLabel.PERSONAL, isRead = false))
        d.insert(MessageEntity(threadId = 9, address = "+15550100", body = "Sure", timestamp = 20, isIncoming = false, sendStatus = SendStatus.SENT))
        d.insert(MessageEntity(threadId = 9, address = "+15550100", body = "still sending", timestamp = 30, isIncoming = false, sendStatus = SendStatus.PENDING))
        d.insert(MessageEntity(threadId = 0, address = "+15550199", body = "Code 123456", timestamp = 40, isIncoming = true, label = MessageLabel.OTP_2FA))
        src.threadStateDao().setPinned("+15550100", true, 77)
        src.threadStateDao().setArchived("+15550199", true)
        src.threadStateDao().setDraft("+15550100", "private draft")
        src.blockedNumberDao().block(BlockedNumberEntity("+15550123", 5))
    }

    @Test fun `round trip keeps text, labels, state and blocks but not drafts`() = runTest {
        seed()
        val out = ByteArrayOutputStream()
        assertEquals(4, BackupManager(src).export(out))

        val result = BackupManager(dst).import(ByteArrayInputStream(out.toByteArray()))
        assertEquals(ImportResult(added = 4, alreadyPresent = 0, blocked = 1), result)

        val msgs = dst.messageDao().messagesForThread("+15550100")
        assertEquals(listOf("Lunch \"today\"? é中\n2nd line", "Sure", "still sending"), msgs.map { it.body })
        assertEquals(MessageLabel.PERSONAL, msgs[0].label)
        assertEquals(false, msgs[0].isRead)
        assertEquals(SendStatus.NONE, msgs[2].sendStatus) // a PENDING send is not resurrected as in-flight
        assertEquals(77L, dst.threadStateDao().get("+15550100")!!.pinnedAt)
        assertTrue(dst.threadStateDao().get("+15550199")!!.archived)
        assertEquals(null, dst.threadStateDao().get("+15550100")!!.draft)
        assertTrue(dst.blockedNumberDao().isBlocked("+15550123"))
    }

    @Test fun `importing twice does not duplicate`() = runTest {
        seed()
        val out = ByteArrayOutputStream(); BackupManager(src).export(out)
        BackupManager(dst).import(ByteArrayInputStream(out.toByteArray()))
        val second = BackupManager(dst).import(ByteArrayInputStream(out.toByteArray()))
        assertEquals(0, second.added); assertEquals(4, second.alreadyPresent)
        assertEquals(4, dst.messageDao().count())
    }

    @Test fun `import does not overwrite state already set on this phone`() = runTest {
        seed()
        val out = ByteArrayOutputStream(); BackupManager(src).export(out)
        dst.threadStateDao().setPinned("+15550100", false, 1)
        dst.threadStateDao().setArchived("+15550100", true)
        BackupManager(dst).import(ByteArrayInputStream(out.toByteArray()))
        val s = dst.threadStateDao().get("+15550100")!!
        assertEquals(null, s.pinnedAt); assertTrue(s.archived)
    }

    @Test fun `large export pages through everything`() = runTest {
        repeat(1_203) { i -> src.messageDao().insert(MessageEntity(threadId = 0, address = "+1555010${i % 3}", body = "m$i", timestamp = (i + 1).toLong(), isIncoming = true)) }
        val out = ByteArrayOutputStream()
        assertEquals(1_203, BackupManager(src).export(out))
        assertEquals(1_203, BackupManager(dst).import(ByteArrayInputStream(out.toByteArray())).added)
    }

    @Test fun `wrong or damaged files fail with a readable message`() = runTest {
        fun fails(text: String): String = try {
            kotlinx.coroutines.runBlocking { BackupManager(dst).import(ByteArrayInputStream(text.toByteArray())) }; fail("should fail"); ""
        } catch (e: BackupFormatException) { e.message!! }
        assertTrue(fails("not json at all").isNotBlank())
        assertTrue(fails("{\"format\":\"something-else\"}").contains("not an InboxIQ backup"))
        assertTrue(fails("{\"format\":\"inboxiq-backup\",\"version\":99}").contains("newer version"))
        assertTrue(fails("{\"format\":\"inboxiq-backup\",\"version\":1,\"messages\":[{\"address\":\"+1\",\"body\":\"x\"").contains("damaged"))
        assertTrue(fails("{\"format\":\"inboxiq-backup\",\"version\":1,\"messages\":[{\"body\":\"no sender\",\"timestamp\":5}]}").contains("no sender"))
    }
}
