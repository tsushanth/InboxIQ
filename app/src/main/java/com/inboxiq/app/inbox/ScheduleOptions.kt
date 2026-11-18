package com.inboxiq.app.inbox

import com.inboxiq.app.R
import java.util.Calendar
import java.util.TimeZone

/** Preset "send later" times (a string resource for the label, and the epoch millis), always strictly in the future. */
object ScheduleOptions {
    fun quick(now: Long, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, Long>> {
        fun at(dayOffset: Int, hour: Int): Long = Calendar.getInstance(zone).apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val out = mutableListOf(R.string.schedule_in_one_hour to now + 60L * 60_000)
        val evening = at(0, 18)
        if (evening > now + 30 * 60_000) out += R.string.schedule_this_evening to evening
        val tomorrow = at(1, 8)
        out += R.string.schedule_tomorrow_morning to tomorrow
        val cal = Calendar.getInstance(zone).apply { timeInMillis = now }
        val daysToMonday = ((Calendar.MONDAY - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7).let { if (it == 0) 7 else it }
        if (daysToMonday > 1) out += R.string.schedule_monday_morning to at(daysToMonday, 8)
        return out
    }
}
