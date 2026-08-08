package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/**
 * Retrieval-based quick replies (a fixed candidate set picked by simple
 * heuristics) — the same approach Gmail's original Smart Reply used before
 * generative Magic Compose. We don't have a generative model in the
 * pipeline yet (see README classifier tiers), so this is honest about what
 * it is: canned suggestions, not AI-drafted text.
 */
object QuickReplySuggester {

    /** Threads worth surfacing a "needs a reply" signal for at all. */
    fun needsResponse(lastMessageIsIncoming: Boolean, label: MessageLabel): Boolean =
        lastMessageIsIncoming && label in RESPONDABLE_LABELS

    fun suggest(lastIncomingBody: String): List<String> {
        val text = lastIncomingBody.lowercase()
        return when {
            text.contains("?") && listOf("can you", "could you", "would you", "will you").any { text.contains(it) } ->
                listOf("Yes", "Not right now", "Let me check and get back to you")
            text.contains("?") ->
                listOf("Yes", "No", "Let me get back to you")
            listOf("thank", "thanks").any { text.contains(it) } ->
                listOf("You're welcome!", "No problem", "Anytime")
            listOf("when", "schedule", "available", "meet").any { text.contains(it) } ->
                listOf("Works for me", "Let me check my calendar", "Can we do another time?")
            else ->
                listOf("Got it", "Sounds good", "Thanks!")
        }
    }

    private val RESPONDABLE_LABELS = setOf(MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.UNLABELED)
}
