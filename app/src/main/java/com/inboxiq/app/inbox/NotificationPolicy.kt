package com.inboxiq.app.inbox

import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.MessageLabel

/** One Android notification channel per kind of message, so the user can tune each in system settings. */
enum class MessageChannel(val id: String, val title: String, val description: String, val importance: Int) {
    // importance values mirror NotificationManager.IMPORTANCE_* (4 high, 3 default, 2 low)
    GENERAL("incoming_messages", "Messages", "Texts that have not been sorted yet", 4),
    PERSONAL("msg_personal", "Personal", "Texts from people you know", 4),
    WORK("msg_work", "Work", "Texts about work", 4),
    CODES("msg_codes", "Verification codes", "One-time codes, with a Copy code button", 4),
    PROMO("msg_promo", "Promos", "Marketing and sales texts, kept quiet", 2),
    JUNK("msg_junk", "Spam and scams", "Texts filed as spam or scam, silent", 2),
}

data class NotificationPlan(
    val notify: Boolean,
    val channel: MessageChannel,
    val canReply: Boolean,
    /** A still-valid one-time code to offer a Copy action for, or null. */
    val code: String?,
)

object NotificationPolicy {
    fun channelFor(label: MessageLabel): MessageChannel = when (label) {
        MessageLabel.PERSONAL -> MessageChannel.PERSONAL
        MessageLabel.WORK -> MessageChannel.WORK
        MessageLabel.OTP_2FA -> MessageChannel.CODES
        MessageLabel.PROMO -> MessageChannel.PROMO
        MessageLabel.SPAM, MessageLabel.SCAM -> MessageChannel.JUNK
        MessageLabel.UNLABELED -> MessageChannel.GENERAL
    }

    fun plan(label: MessageLabel, body: String, receivedAt: Long, now: Long, muted: Boolean): NotificationPlan {
        val channel = channelFor(label)
        val otp = if (label == MessageLabel.OTP_2FA || label == MessageLabel.UNLABELED) OtpExtractor.extract(body, receivedAt) else null
        val liveCode = otp?.takeIf { !it.isExpired(now) }?.code
        return NotificationPlan(
            notify = !muted,
            channel = if (liveCode != null && label == MessageLabel.UNLABELED) MessageChannel.CODES else channel,
            // Never offer inline reply to a code sender, promo or junk: replying to those is pointless or risky.
            canReply = QuickReplySuggester.needsResponse(true, label),
            code = liveCode,
        )
    }
}
