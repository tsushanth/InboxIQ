package com.inboxiq.app.inbox

import androidx.annotation.StringRes
import com.inboxiq.app.R

/** The mute choices offered in the UI. [until] returns the epoch-millis to store (Long.MAX_VALUE = until turned back on). */
enum class MuteDuration(@StringRes val labelRes: Int, private val millis: Long?) {
    HOUR(R.string.mute_one_hour, 60L * 60_000),
    EIGHT_HOURS(R.string.mute_eight_hours, 8L * 60 * 60_000),
    WEEK(R.string.mute_one_week, 7L * 24 * 60 * 60_000),
    FOREVER(R.string.mute_until_turned_on, null);

    fun until(now: Long): Long = if (millis == null) Long.MAX_VALUE else now + millis
}
