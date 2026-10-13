package com.inboxiq.app.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ScheduleOptionsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        Calendar.getInstance(utc).apply { clear(); set(y, mo, d, h, mi) }.timeInMillis

    @Test fun `all options are in the future and ordered`() {
        val now = ms(2026, Calendar.OCTOBER, 14, 10) // a Wednesday
        val opts = ScheduleOptions.quick(now, utc)
        assertTrue(opts.all { it.second > now })
        assertEquals(opts.map { it.second }, opts.map { it.second }.sorted())
        assertEquals(listOf("In 1 hour", "This evening", "Tomorrow morning", "Monday morning"), opts.map { it.first })
        assertEquals(ms(2026, Calendar.OCTOBER, 14, 18), opts[1].second)
        assertEquals(ms(2026, Calendar.OCTOBER, 19, 8), opts[3].second)
    }

    @Test fun `late evening drops the evening option`() {
        val now = ms(2026, Calendar.OCTOBER, 14, 17, 45)
        assertTrue(ScheduleOptions.quick(now, utc).none { it.first == "This evening" })
    }

    @Test fun `on Sunday tomorrow is Monday so no duplicate Monday option`() {
        val now = ms(2026, Calendar.OCTOBER, 18, 10) // Sunday
        assertTrue(ScheduleOptions.quick(now, utc).none { it.first == "Monday morning" })
    }
}
