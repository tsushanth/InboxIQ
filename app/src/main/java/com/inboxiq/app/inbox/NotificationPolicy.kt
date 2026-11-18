package com.inboxiq.app.inbox

import androidx.annotation.StringRes
import com.inboxiq.app.R
import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.MessageLabel

/** One Android notification channel per kind of message, so the user can tune each in system settings. */
enum class MessageChannel(val id: String, @StringRes val titleRes: Int, @StringRes val descriptionRes: Int, val importance: Int) {
    // importance values mirror NotificationManager.IMPORTANCE_* (4 high, 3 default, 2 low)
    GENERAL("incoming_messages", R.string.channel_general_title, R.string.channel_general_desc, 4),
    PERSONAL("msg_personal", R.string.label_personal, R.string.channel_personal_desc, 4),
    WORK("msg_work", R.string.label_work, R.string.channel_work_desc, 4),
    // Default importance (sound, no heads-up pop-up): bank and delivery alerts can be dozens a day, but must not be silent.
    TRANSACTIONS("msg_transactions", R.string.tab_transactions, R.string.channel_transactions_desc, 3),
    CODES("msg_codes", R.string.channel_codes_title, R.string.channel_codes_desc, 4),
    PROMO("msg_promo", R.string.tab_promos, R.string.channel_promo_desc, 2),
    JUNK("msg_junk", R.string.channel_junk_title, R.string.channel_junk_desc, 2),
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
        MessageLabel.TRANSACTION -> MessageChannel.TRANSACTIONS
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
