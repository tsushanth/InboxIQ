package com.inboxiq.app.ui

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.data.ThreadStateEntity
import com.inboxiq.app.data.ThreadSummary
import com.inboxiq.app.inbox.ThreadRow

/** Obviously fake people and 555-01xx numbers, used only by render tests. */
object Fixtures {
    const val NOW = 1_790_000_000_000L
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    val names = mapOf(
        "+15550101" to "Avery Park",
        "+15550102" to "Dana (Team Lead)",
        "+15550103" to null,
        "+15550104" to null,
        "+15550105" to null,
        "+15550106" to null,
        "+15550107" to "Sam Rivera",
    )

    private var nextId = 1L
    fun msg(
        address: String, body: String, ago: Long, incoming: Boolean = true, label: MessageLabel = MessageLabel.PERSONAL,
        read: Boolean = true, status: SendStatus = SendStatus.NONE, confidence: Float = 0.9f, image: String? = null,
    ) = MessageEntity(
        id = nextId++, threadId = 0, address = address, body = body, timestamp = NOW - ago, isIncoming = incoming,
        label = label, labelConfidence = confidence, isRead = read, sendStatus = status, imagePartUri = image,
    )

    private fun row(m: MessageEntity, unread: Int = 0, state: ThreadStateEntity? = null) = ThreadRow(ThreadSummary(m, unread), state)

    fun rows(): List<ThreadRow> = listOf(
        row(msg("+15550101", "Are we still on for dinner at 7? I booked the corner table.", 6 * MIN, read = false), unread = 2,
            state = ThreadStateEntity("+15550101", pinnedAt = NOW - DAY)),
        row(msg("+15550102", "Shift swap approved. Can you cover Saturday 9 to 5?", 40 * MIN, label = MessageLabel.WORK, read = false), unread = 1),
        row(msg("+15550104", "Your Example Bank verification code is 482913. It expires in 10 minutes.", 3 * MIN, label = MessageLabel.OTP_2FA, read = false), unread = 1),
        row(msg("+15550103", "FLASH SALE: 40% off everything today only. Reply STOP to opt out.", 3 * HOUR, label = MessageLabel.PROMO)),
        row(msg("+15550107", "Sounds good, see you then", 2 * DAY, incoming = false, status = SendStatus.SENT), state = ThreadStateEntity("+15550107", mutedUntil = Long.MAX_VALUE)),
        row(msg("+15550105", "Your package is held. Pay the 1.99 fee at usps-redelivery.top to release it.", 5 * HOUR, label = MessageLabel.SCAM, read = false), unread = 1),
        row(msg("+15550106", "Congratulations, you have won a gift card!", 9 * HOUR, label = MessageLabel.SPAM)),
    )

    fun thread(): List<MessageEntity> = listOf(
        msg("+15550101", "Hey! Are we still on for dinner?", 26 * HOUR),
        msg("+15550101", "Yes. Does 7 work?", 25 * HOUR, incoming = false, status = SendStatus.SENT),
        msg("+15550101", "Perfect. I booked the corner table. Menu is at https://www.example.com/menu", 70 * MIN),
        msg("+15550101", "Also, what's the door code again?", 6 * MIN, read = false),
    )

    fun codeThread(): List<MessageEntity> = listOf(
        msg("+15550104", "Your Example Bank verification code is 482913. It expires in 10 minutes. Never share it.", 3 * MIN, label = MessageLabel.OTP_2FA),
        msg("+15550104", "Your Example Bank verification code is 120934. It expires in 5 minutes.", 2 * HOUR, label = MessageLabel.OTP_2FA),
    )

    fun scamThread(): List<MessageEntity> = listOf(
        msg("+15550105", "Your package is held. Pay the 1.99 fee at usps-redelivery.top to release it.", 5 * HOUR, label = MessageLabel.SCAM, confidence = 0.7f),
        msg("+15550105", "Final notice: act now or your parcel will be returned", 4 * HOUR, label = MessageLabel.SCAM, confidence = 0.7f),
    )
}
