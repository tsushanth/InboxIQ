package com.inboxiq.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ReactionDataTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: ReactionRepository
    private lateinit var actions: ThreadActions
    private var now = 5_000L
    private val a = "+15550100"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = ReactionRepository(db) { now }
        actions = ThreadActions(db, {}, { now })
    }

    @After fun tearDown() = db.close()

    private suspend fun msg(body: String, ts: Long, incoming: Boolean = true, address: String = a, image: String? = null): MessageEntity {
        val m = MessageEntity(threadId = 0, address = address, body = body, timestamp = ts, isIncoming = incoming, imagePartUri = image)
        val id = db.messageDao().insert(m)
        return m.copy(id = id)
    }

    @Test fun `set, replace and toggle off keep one reaction per message`() = runTest {
        val m = msg("Lunch at 1?", 100)
        assertEquals(ReactionRepository.Change.Set("👍", null), repo.toggle(m, "👍"))
        assertEquals("👍", repo.current(m)!!.emoji)
        assertEquals(ReactionRepository.Change.Set("😂", "👍"), repo.toggle(m, "😂"))
        assertEquals(1, db.reactionDao().forThread(a).size)
        assertEquals("😂", repo.current(m)!!.emoji)
        assertEquals(ReactionRepository.Change.Cleared("😂", false), repo.toggle(m, "😂"))
        assertNull(repo.current(m))
        assertTrue(db.reactionDao().forThread(a).isEmpty())
    }

    @Test fun `the key follows address, time, direction and text, not the row id`() = runTest {
        val incoming = msg("Same", 100, incoming = true)
        val outgoing = msg("Same", 100, incoming = false)
        val otherText = msg("Different", 100, incoming = true)
        repo.set(incoming, "❤️")
        assertEquals("❤️", repo.current(incoming)!!.emoji)
        assertNull(repo.current(outgoing))
        assertNull(repo.current(otherText))

        // a restored copy of the message gets a new id and still finds its reaction
        val restored = incoming.copy(id = 9_999)
        assertEquals("❤️", repo.current(restored)!!.emoji)
        val map = ReactionKey.byMessageId(listOf(incoming, outgoing, otherText, restored), db.reactionDao().forThread(a))
        assertEquals(setOf(incoming.id, 9_999L), map.keys)
    }

    @Test fun `a picture message keeps its reaction when its body is healed from the placeholder`() = runTest {
        val placeholder = msg(ReactionKey.MMS_PLACEHOLDER, 200, incoming = true)
        repo.set(placeholder, "😮")
        assertEquals("", db.reactionDao().forThread(a).single().bodyHash)
        val healed = placeholder.copy(body = "look at this", imagePartUri = "content://mms/part/7")
        assertEquals("😮", repo.current(healed)!!.emoji)
        // choosing another emoji afterwards replaces rather than adds
        repo.set(healed, "😂")
        assertEquals(listOf("😂"), db.reactionDao().forThread(a).map { it.emoji })
        // and a text-only MMS that was healed into plain text is still found by its placeholder-era reaction
        val textOnly = placeholder.copy(body = "hello there")
        assertEquals("😂", repo.current(textOnly)!!.emoji)
    }

    @Test fun `observeThread is one query for the whole thread`() = runTest {
        val m1 = msg("one", 1); val m2 = msg("two", 2, incoming = false); msg("three", 3, address = "+15550199")
        repo.set(m1, "👍"); repo.set(m2, "❤️")
        repo.set(msg("x", 4, address = "+15550199"), "😢")
        val rows = db.reactionDao().observeThread(a).first()
        assertEquals(2, rows.size)
        val byId = ReactionKey.byMessageId(listOf(m1, m2), rows)
        assertEquals("👍", byId[m1.id]!!.emoji); assertEquals("❤️", byId[m2.id]!!.emoji)
    }

    @Test fun `undoing a message delete keeps its reaction and committing it removes it`() = runTest {
        val m = msg("keep me", 100); val other = msg("other", 101)
        repo.set(m, "👍"); repo.set(other, "😂")
        val removed = actions.deleteMessage(m.id)!!
        assertEquals("a pending delete does not touch reactions", 2, db.reactionDao().forThread(a).size)
        actions.restoreMessage(removed)
        assertEquals("👍", repo.current(db.messageDao().getById(m.id)!!)!!.emoji)

        val removed2 = actions.deleteMessage(m.id)!!
        actions.commitMessageDelete(removed2)
        assertEquals(listOf("😂"), db.reactionDao().forThread(a).map { it.emoji })
    }

    @Test fun `undoing a thread delete keeps reactions and committing it removes them for that thread only`() = runTest {
        val m = msg("one", 1); val o = msg("two", 2, address = "+15550199")
        repo.set(m, "👍"); repo.set(o, "❤️")
        val snap = actions.delete(a)
        assertEquals(1, db.reactionDao().forThread(a).size)
        actions.restore(snap)
        assertEquals("👍", repo.current(db.messageDao().messagesForThread(a).single())!!.emoji)

        val snap2 = actions.delete(a)
        actions.commitDelete(snap2)
        assertTrue(db.reactionDao().forThread(a).isEmpty())
        assertEquals(1, db.reactionDao().forThread("+15550199").size)
    }

    @Test fun `deleting several threads and committing leaves no reactions behind`() = runTest {
        val m = msg("one", 1); val o = msg("two", 2, address = "+15550199")
        repo.set(m, "👍"); repo.set(o, "❤️")
        listOf(a, "+15550199").map { actions.delete(it) }.forEach { actions.commitDelete(it) }
        assertTrue(db.reactionDao().all().isEmpty())
    }

    @Test fun `the startup sweep removes reactions whose message is gone and keeps the rest`() = runTest {
        val gone = msg("gone", 1); val kept = msg("kept", 2)
        repo.set(gone, "👍"); repo.set(kept, "😂")
        db.messageDao().deleteMessage(gone.id) // as after a process kill inside an undo window
        assertEquals(1, repo.sweepOrphans())
        assertEquals(listOf("😂"), db.reactionDao().all().map { it.emoji })
    }

    @Test fun `re-filing a message under another address takes its reaction along`() = runTest {
        val m = msg("moved", 10, incoming = false)
        repo.set(m, "👍")
        db.reactionDao().reassignAddress(a, "+15550177", 10, false)
        assertEquals("+15550177", db.reactionDao().all().single().address)
    }

    @Test fun `markSentAsText flags only that reaction`() = runTest {
        val m = msg("hi", 10); val n = msg("yo", 11)
        repo.set(m, "👍"); repo.set(n, "😂")
        repo.markSentAsText(m)
        assertTrue(repo.current(m)!!.sentAsText)
        assertEquals(false, repo.current(n)!!.sentAsText)
        assertEquals(ReactionRepository.Change.Cleared("👍", true), repo.clear(m))
    }

    @Test fun `restore puts a cleared reaction back exactly`() = runTest {
        val m = msg("hi", 10)
        repo.set(m, "👍"); repo.markSentAsText(m)
        val before = repo.current(m)!!
        repo.clear(m)
        repo.restore(m, before)
        assertEquals(before, repo.current(m))
    }

    // ---------------- backup ----------------

    @Test fun `backup round trip carries reactions to a new database where ids differ`() = runTest {
        val m = msg("Lunch at 1?", 100); msg("other thread", 5, address = "+15550199")
        repo.set(m, "😂"); repo.markSentAsText(m)
        val out = ByteArrayOutputStream(); BackupManager(db).export(out)
        assertTrue(out.toString("UTF-8").contains("\"reactions\""))

        val dst = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            dst.messageDao().insert(MessageEntity(threadId = 0, address = "+19990000", body = "pad so ids differ", timestamp = 1, isIncoming = true))
            val result = BackupManager(dst).import(ByteArrayInputStream(out.toByteArray()))
            assertEquals(1, result.reactions)
            val restoredMsg = dst.messageDao().messagesForThread(a).single()
            val r = ReactionRepository(dst).current(restoredMsg)!!
            assertEquals("😂", r.emoji); assertTrue(r.sentAsText)
            // importing again changes nothing
            assertEquals(0, BackupManager(dst).import(ByteArrayInputStream(out.toByteArray())).reactions)
        } finally { dst.close() }
    }

    @Test fun `a backup file written before reactions existed still restores`() = runTest {
        val json = """{"format":"inboxiq-backup","version":1,"exportedAt":1,"messages":[{"address":"+15550100","body":"old","timestamp":10,"isIncoming":true}],"threadState":[],"blocked":[]}"""
        val result = BackupManager(db).import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(ImportResult(added = 1, alreadyPresent = 0, blocked = 0, reactions = 0), result)
    }

    @Test fun `restore does not overwrite a reaction already chosen on this phone and skips malformed entries`() = runTest {
        val m = msg("hello", 10)
        repo.set(m, "👍")
        val hash = ReactionKey.bodyHash(m)
        val json = """{"format":"inboxiq-backup","version":1,"messages":[],"reactions":[
            {"address":"+15550100","timestamp":10,"isIncoming":true,"bodyHash":"$hash","emoji":"😡","createdAt":1,"sentAsText":false,"futureField":{"x":1}},
            {"address":"","timestamp":10,"emoji":"x"},
            {"address":"+15550100","timestamp":11,"isIncoming":true,"bodyHash":"","emoji":"❤️","createdAt":2}
        ]}"""
        val result = BackupManager(db).import(ByteArrayInputStream(json.toByteArray()))
        assertEquals(1, result.reactions)
        assertEquals("👍", repo.current(m)!!.emoji)
    }
}
