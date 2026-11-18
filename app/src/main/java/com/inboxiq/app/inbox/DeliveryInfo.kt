package com.inboxiq.app.inbox

import android.content.Context
import androidx.annotation.StringRes
import com.inboxiq.app.R
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus

/** Plain-language send state. SENT means "accepted by the network", never "read" or "delivered". */
object DeliveryInfo {
    const val MAX_RETRIES = 5 // mirrors MmsSender.MAX_AUTO_HEAL_RETRIES

    @StringRes
    fun shortRes(m: MessageEntity): Int? = when {
        m.isIncoming -> null
        m.awaitingAutoHeal -> R.string.delivery_short_retrying
        m.sendStatus == SendStatus.FAILED -> R.string.delivery_short_failed
        m.sendStatus == SendStatus.PENDING -> R.string.delivery_short_sending
        m.sendStatus == SendStatus.SENT -> R.string.delivery_short_sent
        else -> null
    }

    @StringRes
    fun detailRes(m: MessageEntity): Int = when {
        m.isIncoming -> R.string.delivery_detail_received
        m.awaitingAutoHeal -> R.string.delivery_detail_retrying
        m.sendStatus == SendStatus.FAILED -> R.string.delivery_detail_failed
        m.sendStatus == SendStatus.PENDING -> R.string.delivery_detail_sending
        m.sendStatus == SendStatus.SENT -> R.string.delivery_detail_sent
        else -> R.string.delivery_detail_unknown
    }

    fun short(context: Context, m: MessageEntity): String? = shortRes(m)?.let { context.getString(it) }

    /** The retry counters are formatted into the one string that has placeholders for them; the others ignore the extra arguments. */
    fun detail(context: Context, m: MessageEntity): String = context.getString(detailRes(m), m.autoHealRetryCount, MAX_RETRIES)

    val isFailure: (MessageEntity) -> Boolean = { !it.isIncoming && (it.awaitingAutoHeal || it.sendStatus == SendStatus.FAILED) }
}
