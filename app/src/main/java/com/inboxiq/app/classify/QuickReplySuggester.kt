package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.reply.ReplyRequest
import com.inboxiq.app.reply.SmartReply
import com.inboxiq.app.reply.ThreadMsg

/**
 * Entry point for the thread's reply chips and the inbox's "Needs a reply" mark. The work is in
 * [com.inboxiq.app.reply]: a context-aware, fully on-device intent reader and a personalised reply bank
 * (docs/SMART_REPLY.md). These are thin, synchronous wrappers; callers on the UI thread should go through
 * [com.inboxiq.app.reply.RulesReplySuggester] instead, which moves the work off the main thread.
 */
object QuickReplySuggester {

    /**
     * Label-only check, kept for callers that have no message text (notification inline-reply). The inbox
     * row and the thread use [needsReply], which reads the text.
     */
    fun needsResponse(lastMessageIsIncoming: Boolean, label: MessageLabel): Boolean =
        lastMessageIsIncoming && label in RESPONDABLE_LABELS

    /** True when a person is waiting on an answer: a question, a request, an unanswered ask. Not "ok", thanks or automated senders. */
    fun needsReply(
        address: String,
        body: String,
        label: MessageLabel,
        isIncoming: Boolean = true,
        hasImage: Boolean = false,
    ): Boolean {
        if (!isIncoming) return false
        return SmartReply.needsReply(
            ReplyRequest(address, label, listOf(ThreadMsg(body, true, hasImage = hasImage, label = label))),
        )
    }

    private val RESPONDABLE_LABELS = setOf(MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.UNLABELED)
}
