package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadStateEntity
import com.inboxiq.app.data.ThreadSummary

/** The label tray: one tab per group of on-device labels. */
enum class InboxTab(val title: String, val labels: Set<MessageLabel>?) {
    ALL("All", null),
    PERSONAL("Personal", setOf(MessageLabel.PERSONAL)),
    WORK("Work", setOf(MessageLabel.WORK)),
    PROMO("Promos", setOf(MessageLabel.PROMO)),
    CODES("Codes", setOf(MessageLabel.OTP_2FA)),
    JUNK("Junk", setOf(MessageLabel.SPAM, MessageLabel.SCAM)),
}

/** A conversation as the list sees it: its newest message plus the user's pin/archive/mute/draft state. */
data class ThreadRow(
    val summary: ThreadSummary,
    val state: ThreadStateEntity? = null,
) {
    val address: String get() = summary.latestMessage.address
    val label: MessageLabel get() = summary.latestMessage.label
    val unread: Int get() = summary.unreadCount
    val lastTimestamp: Long get() = summary.latestMessage.timestamp
    val pinned: Boolean get() = state?.pinnedAt != null
    val archived: Boolean get() = state?.archived == true
    fun muted(now: Long): Boolean = state?.isMuted(now) == true
    val draft: String? get() = state?.draft?.takeIf { it.isNotBlank() }
}

object InboxLogic {

    fun join(summaries: List<ThreadSummary>, states: List<ThreadStateEntity>): List<ThreadRow> {
        val byAddress = states.associateBy { it.address }
        return summaries.map { ThreadRow(it, byAddress[it.latestMessage.address]) }
    }

    private fun inTab(row: ThreadRow, tab: InboxTab): Boolean = when (tab) {
        // "All" is the inbox: everything except what the on-device model filed as junk.
        InboxTab.ALL -> row.label != MessageLabel.SPAM && row.label != MessageLabel.SCAM
        else -> row.label in tab.labels!!
    }

    /** Pinned first (most recently pinned on top), then newest message first. Archived rows never appear here. */
    fun visible(rows: List<ThreadRow>, tab: InboxTab): List<ThreadRow> =
        rows.filter { !it.archived && inTab(it, tab) }.sortedWith(order)

    fun archived(rows: List<ThreadRow>): List<ThreadRow> =
        rows.filter { it.archived }.sortedByDescending { it.lastTimestamp }

    private val order = compareByDescending<ThreadRow> { it.state?.pinnedAt ?: Long.MIN_VALUE }
        .thenByDescending { it.lastTimestamp }

    /** Unread conversations per tab, archived ones excluded. */
    fun unreadCounts(rows: List<ThreadRow>): Map<InboxTab, Int> =
        InboxTab.entries.associateWith { tab ->
            rows.count { !it.archived && inTab(it, tab) && it.unread > 0 }
        }

    fun totalUnreadMessages(rows: List<ThreadRow>): Int = rows.filter { !it.archived }.sumOf { it.unread }
}

/** Search across conversations (by name or number) and message text. */
object SearchLogic {

    data class ConversationHit(val address: String, val title: String)
    data class MessageHit(val message: MessageEntity, val title: String, val snippet: Snippet)
    data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

    /** Escapes SQL LIKE wildcards so "50%" or "a_b" are searched literally. Pair with ESCAPE '\'. */
    fun escapeLike(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun digits(s: String) = s.filter { it.isDigit() }

    fun conversations(query: String, rows: List<ThreadRow>, nameOf: (String) -> String?): List<ConversationHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val qDigits = digits(q)
        return rows.mapNotNull { row ->
            val name = nameOf(row.address)
            val nameHit = name?.contains(q, ignoreCase = true) == true
            // Only treat the query as a number when it's mostly digits, so "a1" doesn't match every number.
            val numberHit = qDigits.length >= 3 && qDigits.length * 2 >= q.length && digits(row.address).contains(qDigits)
            if (nameHit || numberHit) ConversationHit(row.address, name ?: row.address) else null
        }
    }

    /** A short excerpt centred on the first match, so a long message still shows why it matched. */
    fun snippet(body: String, query: String, radius: Int = 36): Snippet {
        val q = query.trim()
        val flat = body.replace('\n', ' ')
        val idx = flat.indexOf(q, ignoreCase = true)
        if (idx < 0 || q.isEmpty()) return Snippet(flat.take(radius * 2), 0, 0)
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + q.length + radius).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        val text = prefix + flat.substring(start, end) + suffix
        val ms = prefix.length + (idx - start)
        return Snippet(text, ms, ms + q.length)
    }
}
