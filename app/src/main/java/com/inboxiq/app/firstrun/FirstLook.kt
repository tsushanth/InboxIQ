package com.inboxiq.app.firstrun

import android.content.Context
import androidx.annotation.PluralsRes
import com.inboxiq.app.R
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.inbox.ThreadRow

/** Conversation counts per label, for the "your inbox, sorted" card. */
data class FirstLookSummary(val sorted: Int, val parts: List<Pair<String, Int>>) {
    /** "12 personal, 4 promos and 2 codes". Empty groups are left out. */
    fun sentence(context: Context): String {
        val res = context.resources
        val items = parts.filter { it.second > 0 }.map { (name, n) -> res.getQuantityString(countPlural(name), n, n) }
        if (items.isEmpty()) return ""
        // The joiners are strings so each language can choose its own separator and conjunction.
        var text = items[0]
        for (i in 1 until items.size - 1) text = context.getString(R.string.firstlook_join_more, text, items[i])
        if (items.size > 1) text = context.getString(R.string.firstlook_join_last, text, items.last())
        return text
    }

    @PluralsRes
    private fun countPlural(group: String): Int = when (group) {
        "personal" -> R.plurals.firstlook_count_personal
        "work" -> R.plurals.firstlook_count_work
        "transactions" -> R.plurals.firstlook_count_transactions
        "promos" -> R.plurals.firstlook_count_promos
        "codes" -> R.plurals.firstlook_count_codes
        else -> R.plurals.firstlook_count_junk
    }
}

object FirstLook {

    /** Counts conversations by the label of their newest message. Archived ones are included: they were sorted too. */
    fun summarize(rows: List<ThreadRow>): FirstLookSummary {
        val by = rows.groupingBy { it.label }.eachCount()
        fun n(vararg l: MessageLabel) = l.sumOf { by[it] ?: 0 }
        val parts = listOf(
            "personal" to n(MessageLabel.PERSONAL),
            "work" to n(MessageLabel.WORK),
            "transactions" to n(MessageLabel.TRANSACTION),
            "promos" to n(MessageLabel.PROMO),
            "codes" to n(MessageLabel.OTP_2FA),
            "junk" to n(MessageLabel.SPAM, MessageLabel.SCAM),
        )
        return FirstLookSummary(parts.sumOf { it.second }, parts)
    }

    /**
     * Show the card once the copy has finished and at least one conversation has a label. While the model is
     * still working the counts simply grow; before any label exists the card would say nothing.
     */
    fun shouldShow(pending: Boolean, isDefault: Boolean, importRunning: Boolean, summary: FirstLookSummary): Boolean =
        pending && isDefault && !importRunning && summary.sorted > 0
}

/** The notifications-off warning: only while we hold the SMS role, where a silent app means missed texts. */
object NotificationNudge {
    enum class Action { ASK, OPEN_SETTINGS }

    fun shouldShow(isDefault: Boolean, notificationsEnabled: Boolean): Boolean = isDefault && !notificationsEnabled

    /**
     * Android 13+ shows the permission dialog once or twice, then ignores further requests, so after one ask the
     * button must open the app's notification settings instead. Before 13 there is no runtime permission at all:
     * notifications off means the user turned them off, and settings is the only place to fix that.
     */
    fun action(sdk: Int, asked: Boolean): Action = if (sdk >= 33 && !asked) Action.ASK else Action.OPEN_SETTINGS
}
