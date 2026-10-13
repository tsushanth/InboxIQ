package com.inboxiq.app.inbox

/** The mute choices offered in the UI. [until] returns the epoch-millis to store (Long.MAX_VALUE = until turned back on). */
enum class MuteDuration(val label: String, private val millis: Long?) {
    HOUR("For 1 hour", 60L * 60_000),
    EIGHT_HOURS("For 8 hours", 8L * 60 * 60_000),
    WEEK("For 1 week", 7L * 24 * 60 * 60_000),
    FOREVER("Until I turn it back on", null);

    fun until(now: Long): Long = if (millis == null) Long.MAX_VALUE else now + millis
}
