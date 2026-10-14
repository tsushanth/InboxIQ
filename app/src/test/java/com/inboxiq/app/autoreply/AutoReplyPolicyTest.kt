package com.inboxiq.app.autoreply

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

class AutoReplyPolicyTest {
    private val utc = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 10, 14, 12, 0).atZone(utc).toInstant().toEpochMilli() // a Wednesday
    private val on = AutoReplyConfig(enabled = true)
    private val person = "+14155550101"
    private fun msg(body: String = "are you around later?", address: String = person, label: MessageLabel = MessageLabel.PERSONAL, at: Long = now - 10_000, mms: Boolean = false) =
        Incoming(address, body, at, label, mms)
    private val good = Facts(isDefaultSmsApp = true, importRunning = false, isBlocked = false, sentToBefore = 2, isSavedContact = false, lastAutoReplyAt = 0, sentToday = 0)
    private fun eval(c: AutoReplyConfig = on, m: Incoming = msg(), f: Facts = good, n: Long = now, build: Boolean = true, zone: ZoneId = utc) =
        AutoReplyPolicy.evaluate(build, c, m, f, n, zone)

    @Test fun `off by default`() {
        assertFalse(AutoReplyConfig().enabled)
        assertEquals(Skip.OFF, eval(c = AutoReplyConfig()))
    }

    @Test fun `build flag off blocks everything even when switched on`() = assertEquals(Skip.BUILD_DISABLED, eval(build = false))

    @Test fun `an eligible person gets a reply`() = assertNull(eval())

    // ------------------------------------------------------------------ recipient eligibility matrix
    @Test fun `who may be answered`() {
        data class Row(val name: String, val m: Incoming, val f: Facts, val expect: Skip?)
        val rows = listOf(
            Row("texted before", msg(), good, null),
            Row("saved contact never texted", msg(), good.copy(sentToBefore = 0, isSavedContact = true), null),
            Row("work thread", msg(label = MessageLabel.WORK), good, null),
            Row("international person", msg(address = "+447700900123"), good, null),
            Row("unknown first time sender", msg(), good.copy(sentToBefore = 0), Skip.NEVER_TEXTED),
            Row("short code", msg(address = "55123"), good, Skip.NOT_A_PERSON),
            Row("six digit short code", msg(address = "227898"), good, Skip.NOT_A_PERSON),
            Row("alphanumeric id", msg(address = "EXAMPLEBANK"), good, Skip.NOT_A_PERSON),
            Row("toll free", msg(address = "+18005550142"), good, Skip.NOT_A_PERSON),
            Row("toll free 888", msg(address = "+18885550123"), good, Skip.NOT_A_PERSON),
            Row("email gateway", msg(address = "friend@example.com"), good, Skip.NOT_A_PERSON),
            Row("group comma", msg(address = "+14155550101,+14155550102"), good, Skip.GROUP),
            Row("group semicolon", msg(address = "+14155550101;+14155550103"), good, Skip.GROUP),
            Row("promo label", msg(label = MessageLabel.PROMO), good, Skip.LABEL),
            Row("spam label", msg(label = MessageLabel.SPAM), good, Skip.LABEL),
            Row("scam label", msg(label = MessageLabel.SCAM), good, Skip.LABEL),
            Row("otp label", msg(label = MessageLabel.OTP_2FA), good, Skip.LABEL),
            Row("unlabeled", msg(label = MessageLabel.UNLABELED), good, Skip.LABEL),
            Row("blocked", msg(), good.copy(isBlocked = true), Skip.BLOCKED),
            Row("mms", msg(mms = true), good, Skip.MMS),
            Row("mms placeholder", msg(body = "[MMS message]"), good, Skip.MMS),
            Row("blank", msg(body = ""), good, Skip.MMS),
            Row("not default sms app", msg(), good.copy(isDefaultSmsApp = false), Skip.NOT_DEFAULT_APP),
            Row("importer running", msg(), good.copy(importRunning = true), Skip.IMPORTING),
        )
        for (r in rows) assertEquals(r.name, r.expect, eval(m = r.m, f = r.f))
    }

    @Test fun `texts that look automatic or are asking for a code are never answered`() {
        for (b in listOf("Auto-reply: I am away", "This is an automated message", "Please do not reply", "STOP", "reply STOP to unsubscribe", "I am out of office until Monday",
            "I'm currently driving and will reply when I can", "Your verification code is 482913", "send me the code you just got")) {
            assertNotNull(b, eval(m = msg(body = b)))
        }
    }

    // ------------------------------------------------------------------ limits and loops
    @Test fun `one per sender per 24 hours, configurable down but never up`() {
        val hour = 3_600_000L
        assertEquals(Skip.SENDER_LIMIT, eval(f = good.copy(lastAutoReplyAt = now - 23 * hour)))
        assertNull(eval(f = good.copy(lastAutoReplyAt = now - 25 * hour)))
        val six = on.copy(perSenderHours = 6)
        assertNull(eval(c = six, f = good.copy(lastAutoReplyAt = now - 7 * hour)))
        assertEquals(Skip.SENDER_LIMIT, eval(c = six, f = good.copy(lastAutoReplyAt = now - 5 * hour)))
        val tooHigh = on.copy(perSenderHours = 72) // cannot be raised past 24
        assertEquals(24 * hour, tooHigh.effectivePerSenderMs)
        assertEquals(Skip.SENDER_LIMIT, eval(c = tooHigh, f = good.copy(lastAutoReplyAt = now - 30 * hour + 7 * hour)))
    }

    @Test fun `loop prevention - nothing within a minute of our own auto reply`() {
        assertEquals(Skip.JUST_REPLIED, eval(c = on.copy(perSenderHours = 1), f = good.copy(lastAutoReplyAt = now - 30_000)))
    }

    @Test fun `global cap is a kill switch and cannot be raised past 20`() {
        assertEquals(Skip.DAILY_CAP, eval(f = good.copy(sentToday = 20)))
        assertNull(eval(f = good.copy(sentToday = 19)))
        assertEquals(Skip.DAILY_CAP, eval(c = on.copy(dailyCap = 5), f = good.copy(sentToday = 5)))
        assertEquals(20, on.copy(dailyCap = 500).effectiveDailyCap)
        assertEquals(Skip.DAILY_CAP, eval(c = on.copy(dailyCap = 500), f = good.copy(sentToday = 20)))
    }

    @Test fun `old texts such as imported history are not answered`() {
        assertEquals(Skip.TOO_OLD, eval(m = msg(at = now - 6 * 60_000)))
        assertNull(eval(m = msg(at = now - 4 * 60_000)))
        assertEquals(Skip.TOO_OLD, eval(m = msg(at = now - 3 * 24 * 3_600_000L)))
    }

    @Test fun `day key follows the local day`() {
        val t = LocalDateTime.of(2026, 10, 14, 23, 30).atZone(utc).toInstant().toEpochMilli()
        assertEquals("2026-10-14", AutoReplyPolicy.dayKey(t, utc))
        assertEquals("2026-10-15", AutoReplyPolicy.dayKey(t, ZoneId.of("Asia/Tokyo")))
    }

    // ------------------------------------------------------------------ schedule
    private fun at(day: DayOfWeek, h: Int, m: Int = 0, zone: ZoneId = utc): Long {
        var d = LocalDateTime.of(2026, 10, 12, h, m) // a Monday
        d = d.plusDays((day.ordinal).toLong())
        return d.atZone(zone).toInstant().toEpochMilli()
    }

    @Test fun `daytime window with weekdays`() {
        val c = on.copy(scheduleOn = true, startMin = 9 * 60, endMin = 17 * 60, days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY))
        assertTrue(c.activeAt(at(DayOfWeek.MONDAY, 9), utc))
        assertTrue(c.activeAt(at(DayOfWeek.TUESDAY, 16, 59), utc))
        assertFalse(c.activeAt(at(DayOfWeek.MONDAY, 17), utc))
        assertFalse(c.activeAt(at(DayOfWeek.MONDAY, 8, 59), utc))
        assertFalse(c.activeAt(at(DayOfWeek.WEDNESDAY, 12), utc))
    }

    @Test fun `a window across midnight belongs to the day it starts on`() {
        val c = on.copy(scheduleOn = true, startMin = 22 * 60, endMin = 7 * 60, days = setOf(DayOfWeek.FRIDAY))
        assertTrue(c.activeAt(at(DayOfWeek.FRIDAY, 23), utc))
        assertTrue("early Saturday is still Friday's window", c.activeAt(at(DayOfWeek.SATURDAY, 3), utc))
        assertFalse("Saturday evening is not", c.activeAt(at(DayOfWeek.SATURDAY, 23), utc))
        assertFalse("early Friday belongs to Thursday's window", c.activeAt(at(DayOfWeek.FRIDAY, 3), utc))
        assertFalse(c.activeAt(at(DayOfWeek.SATURDAY, 7), utc))
        assertTrue(c.activeAt(at(DayOfWeek.SATURDAY, 6, 59), utc))
    }

    @Test fun `schedule is evaluated in the zone the phone is in now`() {
        val c = on.copy(scheduleOn = true, startMin = 9 * 60, endMin = 17 * 60)
        val instant = LocalDateTime.of(2026, 10, 14, 12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertTrue(c.activeAt(instant, ZoneId.of("UTC")))
        assertFalse("20:00 in Tokyo", c.activeAt(instant, ZoneId.of("Asia/Tokyo")))
        assertFalse("05:00 in New York", c.activeAt(instant, ZoneId.of("America/New_York")))
    }

    @Test fun `daylight saving does not shift the wall-clock window`() {
        val ny = ZoneId.of("America/New_York")
        val c = on.copy(scheduleOn = true, startMin = 22 * 60, endMin = 7 * 60)
        // 2026-11-01 01:30 happens twice in New York; both are inside the overnight window.
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(ny).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(ny).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertTrue(c.activeAt(first, ny)); assertTrue(c.activeAt(second, ny))
    }

    @Test fun `an empty window never fires and no schedule means always`() {
        assertFalse(on.copy(scheduleOn = true, startMin = 600, endMin = 600).activeAt(now, utc))
        assertTrue(on.copy(scheduleOn = false, startMin = 600, endMin = 600).activeAt(now, utc))
    }

    @Test fun `until time ends it`() {
        val c = on.copy(untilMillis = now + 60_000)
        assertNull(eval(c = c))
        assertEquals(Skip.EXPIRED, eval(c = c, n = now + 60_000, m = msg(at = now + 59_000)))
    }

    // ------------------------------------------------------------------ message and confirmation
    @Test fun `message must be one short plain text with no link`() {
        assertNull(AutoReplyConfig.validateMessage(AutoReplyConfig.TEMPLATES[0]))
        AutoReplyConfig.TEMPLATES.forEach { assertNull(AutoReplyConfig.validateMessage(it)) }
        assertNotNull(AutoReplyConfig.validateMessage(""))
        assertNotNull(AutoReplyConfig.validateMessage("x".repeat(161)))
        assertNotNull(AutoReplyConfig.validateMessage("call me at https://example.com"))
        assertNotNull(AutoReplyConfig.validateMessage("busy 😊"))
        assertEquals(Skip.BAD_MESSAGE, eval(c = on.copy(message = "see example.com")))
    }

    @Test fun `characters that make a text longer or more expensive are refused`() {
        // GSM extension characters count twice and anything outside the GSM alphabet forces 70-character segments.
        listOf("call me {later}", "busy [now]", "back ~5", "a|b", "back\\later", "cost^2", "it`s me", "100% sure \u20ac").forEach {
            assertNotNull(it, AutoReplyConfig.validateMessage(it))
        }
        assertNull(AutoReplyConfig.validateMessage("I'm busy: call back (or text) at 5, OK? Thanks! #1 @home $5 & more_"))
        assertNull(AutoReplyConfig.validateMessage("x".repeat(160)))
    }

    @Test fun `schedule follows the wall clock across a daylight saving change`() {
        val la = ZoneId.of("America/Los_Angeles")
        val c = AutoReplyConfig(enabled = true, scheduleOn = true, startMin = 22 * 60, endMin = 7 * 60)
        // 2026-11-01: clocks go back at 02:00, so 01:30 happens twice; both are inside the 22:00-07:00 window.
        val first = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(la).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(la).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertTrue(c.withinSchedule(first, la)); assertTrue(c.withinSchedule(second, la))
        assertFalse(c.withinSchedule(LocalDateTime.of(2026, 11, 1, 7, 30).atZone(la).toInstant().toEpochMilli(), la))
        // the spring-forward night: 02:30 does not exist, 03:30 is still inside the window
        assertTrue(c.withinSchedule(LocalDateTime.of(2027, 3, 14, 3, 30).atZone(la).toInstant().toEpochMilli(), la))
    }

    @Test fun `confirmation shows the exact message, recipients, limits and the carrier warning`() {
        val c = AutoReplyConfig(message = "I am driving, I will reply when I can.", perSenderHours = 12, dailyCap = 10)
        val t = AutoReplyConfirmation.text(c)
        assertTrue(t.contains("\"I am driving, I will reply when I can.\""))
        assertTrue(t.contains("people you have texted before or saved as contacts"))
        assertTrue(t.contains("Never to businesses, short codes, groups"))
        assertTrue(t.contains("every 12 hours")); assertTrue(t.contains("at most 10 a day"))
        assertTrue(t.contains("carrier may charge"))
        assertTrue(t.contains("default SMS app"))
    }
}
