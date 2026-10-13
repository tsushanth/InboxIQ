package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus

/** Plain-language send state. SENT means "accepted by the network", never "read" or "delivered". */
object DeliveryInfo {
    const val MAX_RETRIES = 5 // mirrors MmsSender.MAX_AUTO_HEAL_RETRIES

    fun short(m: MessageEntity): String? = when {
        m.isIncoming -> null
        m.awaitingAutoHeal -> "Couldn't send, retrying"
        m.sendStatus == SendStatus.FAILED -> "Not delivered"
        m.sendStatus == SendStatus.PENDING -> "Sending"
        m.sendStatus == SendStatus.SENT -> "Sent"
        else -> null
    }

    fun detail(m: MessageEntity): String = when {
        m.isIncoming -> "Received on this phone."
        m.awaitingAutoHeal ->
            "Couldn't be sent. InboxIQ will try again automatically (${m.autoHealRetryCount} of $MAX_RETRIES tries used). You can also choose Retry now."
        m.sendStatus == SendStatus.FAILED ->
            "Not delivered. The network refused it or there was no signal. Choose Retry now, or send it again."
        m.sendStatus == SendStatus.PENDING -> "Handing the message to your carrier."
        m.sendStatus == SendStatus.SENT ->
            "Sent from this phone to your carrier's network. InboxIQ does not receive delivery receipts, so it cannot tell you the other phone got it or read it."
        else -> "No send information was recorded for this message."
    }

    val isFailure: (MessageEntity) -> Boolean = { !it.isIncoming && (it.awaitingAutoHeal || it.sendStatus == SendStatus.FAILED) }
}
