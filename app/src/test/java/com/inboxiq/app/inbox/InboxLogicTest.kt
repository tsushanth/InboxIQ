package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadStateEntity
import com.inboxiq.app.data.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxLogicTest {

    private fun row(
        address: String,
        label: MessageLabel,
        ts: Long,
        unread: Int = 0,
        state: ThreadStateEntity? = null,
    ) = ThreadRow(
        ThreadSummary(
            MessageEntity(id = ts, threadId = 0, address = address, body = "b", timestamp = ts, isIncoming = true, label = label),
            unread,
        ),
        state,
    )

    private val rows = listOf(
        row("+15550101", MessageLabel.PERSONAL, 100, unread = 2),
        row("+15550102", MessageLabel.WORK, 300),
        row("+15550103", MessageLabel.PROMO, 200, unread = 1),
        row("+15550104", MessageLabel.OTP_2FA, 400, unread = 1),
        row("+15550105", MessageLabel.SPAM, 500, unread = 3),
        row("+15550106", MessageLabel.SCAM, 600),
        row("+15550107", MessageLabel.UNLABELED, 50),
    )

    @Test fun `all tab hides spam and scam but keeps unlabeled`() {
        val addrs = InboxLogic.visible(rows, InboxTab.ALL).map { it.address }
        assertEquals(listOf("+15550104", "+15550102", "+15550103", "+15550101", "+15550107"), addrs)
    }

    @Test fun `junk tab holds both spam and scam`() {
        assertEquals(listOf("+15550106", "+15550105"), InboxLogic.visible(rows, InboxTab.JUNK).map { it.address })
    }

    @Test fun `each label tab shows only its label`() {
        assertEquals(listOf("+15550102"), InboxLogic.visible(rows, InboxTab.WORK).map { it.address })
        assertEquals(listOf("+15550104"), InboxLogic.visible(rows, InboxTab.CODES).map { it.address })
    }

    @Test fun `archived rows leave every tab and show in archive`() {
        val withArchived = rows.map { if (it.address == "+15550102") it.copy(state = ThreadStateEntity(it.address, archived = true)) else it }
        assertTrue(InboxLogic.visible(withArchived, InboxTab.ALL).none { it.address == "+15550102" })
        assertEquals(listOf("+15550102"), InboxLogic.archived(withArchived).map { it.address })
    }

    @Test fun `pinned rows sort first and latest pin leads`() {
        val pinned = rows.map {
            when (it.address) {
                "+15550101" -> it.copy(state = ThreadStateEntity(it.address, pinnedAt = 10))
                "+15550103" -> it.copy(state = ThreadStateEntity(it.address, pinnedAt = 20))
                else -> it
            }
        }
        val addrs = InboxLogic.visible(pinned, InboxTab.ALL).map { it.address }
        assertEquals(listOf("+15550103", "+15550101"), addrs.take(2))
    }

    @Test fun `unread counts are per conversation and skip archived`() {
        val withArchived = rows.map { if (it.address == "+15550101") it.copy(state = ThreadStateEntity(it.address, archived = true)) else it }
        val counts = InboxLogic.unreadCounts(withArchived)
        assertEquals(2, counts[InboxTab.ALL]) // promo + code (personal archived, spam excluded)
        assertEquals(1, counts[InboxTab.JUNK])
        assertEquals(0, counts[InboxTab.PERSONAL])
    }

    @Test fun `join pairs state by address`() {
        val joined = InboxLogic.join(rows.map { it.summary }, listOf(ThreadStateEntity("+15550104", draft = "hi")))
        assertEquals("hi", joined.first { it.address == "+15550104" }.draft)
        assertEquals(null, joined.first { it.address == "+15550101" }.draft)
    }

    @Test fun `mute expiry is respected`() {
        val r = row("+1", MessageLabel.WORK, 1, state = ThreadStateEntity("+1", mutedUntil = 1000))
        assertTrue(r.muted(999)); assertTrue(!r.muted(1000))
    }

    // --- search ---

    @Test fun `escapeLike neutralises wildcards`() {
        assertEquals("50\\% off", SearchLogic.escapeLike("50% off"))
        assertEquals("a\\_b", SearchLogic.escapeLike("a_b"))
        assertEquals("c:\\\\x", SearchLogic.escapeLike("c:\\x"))
    }

    @Test fun `conversation search matches names case-insensitively and numbers by digits`() {
        val names = mapOf("+15550101" to "Avery Test")
        val byName = SearchLogic.conversations("avery", rows) { names[it] }
        assertEquals(listOf("+15550101"), byName.map { it.address })
        assertEquals("Avery Test", byName.single().title)
        val byNumber = SearchLogic.conversations("(555) 0102", rows) { names[it] }
        assertEquals(listOf("+15550102"), byNumber.map { it.address })
    }

    @Test fun `short or non-numeric queries do not match every number`() {
        assertTrue(SearchLogic.conversations("a1", rows) { null }.isEmpty())
        assertTrue(SearchLogic.conversations("  ", rows) { null }.isEmpty())
    }

    @Test fun `snippet centres on the match with ellipses`() {
        val body = "x".repeat(100) + " pickup at noon " + "y".repeat(100)
        val s = SearchLogic.snippet(body, "pickup", radius = 10)
        assertTrue(s.text.startsWith("…") && s.text.endsWith("…"))
        assertEquals("pickup", s.text.substring(s.matchStart, s.matchEnd))
    }

    @Test fun `snippet without a match falls back to the start`() {
        val s = SearchLogic.snippet("hello world", "zzz")
        assertEquals("hello world", s.text)
    }
}
