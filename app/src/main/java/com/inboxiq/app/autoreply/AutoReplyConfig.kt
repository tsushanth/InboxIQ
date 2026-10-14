package com.inboxiq.app.autoreply

import android.content.Context
import android.content.SharedPreferences
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** The user's settings for "Auto-reply when away". Everything defaults to OFF / the most conservative value. */
data class AutoReplyConfig(
    val enabled: Boolean = false,
    val message: String = TEMPLATES[0],
    val scheduleOn: Boolean = false,
    /** Minutes after local midnight. */
    val startMin: Int = 22 * 60,
    val endMin: Int = 7 * 60,
    /** Days the schedule window STARTS on. */
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Epoch millis the auto-reply switches itself off; 0 = no end time. */
    val untilMillis: Long = 0,
    val perSenderHours: Int = MAX_PER_SENDER_HOURS,
    val dailyCap: Int = MAX_DAILY,
) {
    /** The values actually used: the limits can be lowered, never raised past the hard maximums. */
    val effectivePerSenderMs: Long get() = perSenderHours.coerceIn(1, MAX_PER_SENDER_HOURS) * 3_600_000L
    val effectiveDailyCap: Int get() = dailyCap.coerceIn(1, MAX_DAILY)

    fun withinSchedule(nowMillis: Long, zone: ZoneId): Boolean {
        if (!scheduleOn) return true
        if (startMin == endMin) return false // an empty window never fires
        val t = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val min = t.hour * 60 + t.minute
        val today = t.dayOfWeek
        return if (startMin < endMin) {
            today in days && min in startMin until endMin
        } else {
            // Crosses midnight: the early-morning part belongs to the day the window started on.
            (min >= startMin && today in days) || (min < endMin && today.minus(1) in days)
        }
    }

    /** True when a text arriving at [nowMillis] may be answered, as far as the switches and schedule go. */
    fun activeAt(nowMillis: Long, zone: ZoneId): Boolean =
        enabled && (untilMillis == 0L || nowMillis < untilMillis) && withinSchedule(nowMillis, zone)

    /** Why [validate] refused a message, or null when it is fine to send automatically. */
    fun validate(): String? = validateMessage(message)

    companion object {
        const val MAX_DAILY = 20
        const val MAX_PER_SENDER_HOURS = 24
        const val MAX_MESSAGE_CHARS = 160 // one SMS segment: one text per recipient, no multi-part cost surprises

        val TEMPLATES = listOf(
            "I am busy right now and will reply later.",
            "I am driving, I will reply when I can.",
            "I am in a meeting and will get back to you soon.",
        )

        /**
         * Characters of the basic GSM 03.38 alphabet that are also plain ASCII. Anything else (the extension characters
         * { } [ ] ~ | ^ \\ count double; ` and all non-GSM characters force 70-character UCS-2 segments) could turn the
         * 160-character message into several billable texts.
         */
        private val SMS_SAFE: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + " !\"#$%&'()*+,-./:;<=>?@_".toList()).toSet()

        private val url = Regex("https?://|www\\.|\\b[a-z0-9-]+\\.(com|org|net|io|co|app|me|ly|link)\\b", RegexOption.IGNORE_CASE)

        fun validateMessage(m: String): String? = when {
            m.isBlank() -> "Write the message to send."
            m.length > MAX_MESSAGE_CHARS -> "Keep it under $MAX_MESSAGE_CHARS characters so it is a single text."
            url.containsMatchIn(m) -> "Leave out links."
            m.any { it !in SMS_SAFE } -> "Use plain letters and common punctuation so it stays one text (no emoji, accents, or characters like { } [ ] ~ | ^ ` or backslash)."
            else -> null
        }
    }
}

/** One automatic send, for the "Auto-replies sent" screen. The text itself lives in the thread. */
data class AutoReplyLogEntry(val at: Long, val address: String, val messageId: Long)

/**
 * Small SharedPreferences file (no Room schema change). Holds the settings, the per-sender timestamps, the
 * daily counter, the ids of automatic messages (for the "Sent automatically" marker) and a capped log.
 * No message text from other people is stored here.
 */
class AutoReplyStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    fun config(): AutoReplyConfig {
        val d = AutoReplyConfig()
        return AutoReplyConfig(
            enabled = prefs.getBoolean("enabled", false),
            message = prefs.getString("message", d.message) ?: d.message,
            scheduleOn = prefs.getBoolean("scheduleOn", false),
            startMin = prefs.getInt("startMin", d.startMin),
            endMin = prefs.getInt("endMin", d.endMin),
            days = prefs.getInt("days", 0x7F).let { mask -> DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet() },
            untilMillis = prefs.getLong("until", 0),
            perSenderHours = prefs.getInt("perSenderHours", d.perSenderHours).coerceIn(1, AutoReplyConfig.MAX_PER_SENDER_HOURS),
            dailyCap = prefs.getInt("dailyCap", d.dailyCap).coerceIn(1, AutoReplyConfig.MAX_DAILY),
        )
    }

    /** Saves everything except the on/off switch, which only [enable] and [disable] may change. Editing the text switches it off. */
    fun save(c: AutoReplyConfig) {
        val old = config()
        val textChanged = old.message != c.message
        prefs.edit()
            .putString("message", c.message)
            .putBoolean("scheduleOn", c.scheduleOn)
            .putInt("startMin", c.startMin).putInt("endMin", c.endMin)
            .putInt("days", c.days.fold(0) { m, d -> m or (1 shl d.ordinal) })
            .putLong("until", c.untilMillis)
            .putInt("perSenderHours", c.perSenderHours.coerceIn(1, AutoReplyConfig.MAX_PER_SENDER_HOURS))
            .putInt("dailyCap", c.dailyCap.coerceIn(1, AutoReplyConfig.MAX_DAILY))
            .apply { if (textChanged) putBoolean("enabled", false) }
            .commit()
    }

    /**
     * Turns it on, but only for the exact text the user was just shown: [confirmedText] must equal the saved
     * message and must be the confirmation produced by [AutoReplyConfirmation.text] for the current settings.
     */
    fun enable(confirmedText: String): Boolean {
        val c = config()
        if (c.validate() != null) return false
        if (confirmedText != AutoReplyConfirmation.text(c)) return false
        prefs.edit().putBoolean("enabled", true).commit()
        return true
    }

    fun disable() { prefs.edit().putBoolean("enabled", false).commit() }

    // ------------------------------------------------------------ per-sender and rolling daily limits
    /** The same person written "+14155550101", "14155550101" or "4155550101" is one sender. */
    private fun key(address: String) = canonical(address)

    fun lastSentAt(address: String): Long = prefs.getLong("last.${key(address)}", 0)

    /** Automatic texts sent in the 24 hours up to [now] (a rolling window, so midnight does not hand out a new allowance). */
    fun sentInLast24h(now: Long): Int = sendTimes().count { it > now - DAY_MS }

    private fun sendTimes(): List<Long> = prefs.getString("sends", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** Reserves the slot BEFORE sending, so two racing receivers cannot both send. [messageId] is remembered so a redelivery is ignored. */
    fun reserve(address: String, at: Long, messageId: Long) {
        val times = (sendTimes().filter { it > at - DAY_MS } + at).takeLast(AutoReplyConfig.MAX_DAILY * 2)
        prefs.edit().putLong("last.${key(address)}", at).putString("sends", times.joinToString(","))
            .putString("handled", (handledIds() + messageId).takeLast(MAX_HANDLED).joinToString(",")).commit()
    }

    /** Gives the slot back after a send that did not happen. */
    fun release(address: String, previous: Long, at: Long, messageId: Long) {
        prefs.edit().putLong("last.${key(address)}", previous)
            .putString("sends", sendTimes().filter { it != at }.joinToString(","))
            .putString("handled", handledIds().filter { it != messageId }.joinToString(",")).commit()
    }

    private fun handledIds(): List<Long> = prefs.getString("handled", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /** True when an automatic reply was already sent (or is being sent) for this incoming message. */
    fun alreadyHandled(messageId: Long): Boolean = messageId in handledIds()

    fun record(entry: AutoReplyLogEntry) {
        val lines = (log().map { "${it.at}|${it.address}|${it.messageId}" } + "${entry.at}|${entry.address}|${entry.messageId}").takeLast(MAX_LOG)
        val ids = (autoIds() + entry.messageId).filter { it > 0 }.takeLast(MAX_AUTO_IDS)
        prefs.edit().putString("log", lines.joinToString("\n")).putString("autoIds", ids.joinToString(",")).commit()
    }

    fun log(): List<AutoReplyLogEntry> = prefs.getString("log", "").orEmpty().lines().mapNotNull { l ->
        val p = l.split('|')
        if (p.size != 3) null else runCatching { AutoReplyLogEntry(p[0].toLong(), p[1], p[2].toLong()) }.getOrNull()
    }.reversed()

    private fun autoIds(): List<Long> = prefs.getString("autoIds", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    /**
     * Ids of messages InboxIQ sent automatically: shown as "Sent automatically" in the thread, and kept out of
     * "texts the user wrote". Kept apart from the visible list, so clearing the list does not change either.
     */
    fun sentIds(): Set<Long> = (autoIds() + log().map { it.messageId }).filter { it > 0 }.toSet()

    fun clearLog() { prefs.edit().remove("log").commit() }

    companion object {
        const val FILE = "inboxiq_autoreply"
        const val MAX_LOG = 200
        const val MAX_AUTO_IDS = 2000
        const val MAX_HANDLED = 200
        const val DAY_MS = 24 * 3_600_000L

        /** One key per real-world number: digits only, with the North American "1" country code dropped. */
        fun canonical(address: String): String {
            val d = address.filter { it.isDigit() }
            return if (d.length == 11 && d.startsWith("1")) d.substring(1) else d
        }

        /** Every way the same number may be stored, for looking up what the user sent to it. */
        fun variants(address: String): List<String> {
            val c = canonical(address)
            return if (c.length == 10) listOf(address, "+1$c", "1$c", c) else listOf(address, "+$c", c)
        }
    }
}

/** What the confirmation dialog says. Kept here (pure) so tests can pin it to the exact message that will be sent. */
object AutoReplyConfirmation {
    fun text(c: AutoReplyConfig): String = buildString {
        appendLine("InboxIQ will send this text automatically:")
        appendLine()
        appendLine("\"${c.message}\"")
        appendLine()
        appendLine("It is sent only to people you have texted before or saved as contacts, in one-to-one Personal or Work conversations. Never to businesses, short codes, groups, spam, or numbers you blocked.")
        appendLine()
        append("Limits: one automatic text per person every ${c.perSenderHours.coerceIn(1, AutoReplyConfig.MAX_PER_SENDER_HOURS)} hours, at most ${c.dailyCap.coerceIn(1, AutoReplyConfig.MAX_DAILY)} a day in total. ")
        append("It only answers new texts, and only while InboxIQ is your default SMS app. ")
        appendLine("You can turn it off any time and see everything it sent.")
        appendLine()
        append("Your carrier may charge for these texts, as for any text you send.")
    }.trim()
}
