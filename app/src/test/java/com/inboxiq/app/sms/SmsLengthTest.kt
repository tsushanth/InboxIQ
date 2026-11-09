package com.inboxiq.app.sms

import android.telephony.SmsMessage
import com.inboxiq.app.autoreply.AutoReplyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Segment counting: fixed cases from the standard, then a seeded random property check against the
 * platform's own counter (`SmsMessage.calculateLength`), which is what the radio stack uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SmsLengthTest {

    @Test fun `plain text up to 160 is one segment, 161 is two`() {
        assertEquals(SmsLength.Count(SmsLength.Encoding.GSM_7BIT, 160, 1), SmsLength.of("a".repeat(160)))
        assertEquals(2, SmsLength.of("a".repeat(161)).segments)
        assertEquals(2, SmsLength.of("a".repeat(306)).segments)
        assertEquals(3, SmsLength.of("a".repeat(307)).segments)
    }

    @Test fun `extension characters cost two septets`() {
        assertEquals(2, SmsLength.of("{").units)
        assertEquals(1, SmsLength.of("a".repeat(158) + "{").segments)
        assertEquals(2, SmsLength.of("a".repeat(159) + "{").segments)
        assertEquals(2, SmsLength.of("€").units)
    }

    @Test fun `one emoji, curly quote or backtick makes the whole text UCS-2`() {
        assertEquals(SmsLength.Encoding.UCS2, SmsLength.of("hello 😊").encoding)
        assertEquals(SmsLength.Encoding.UCS2, SmsLength.of("it’s").encoding)
        assertEquals(SmsLength.Encoding.UCS2, SmsLength.of("use `this`").encoding)
        assertEquals(1, SmsLength.of("a".repeat(69) + "’").segments)
        assertEquals(2, SmsLength.of("a".repeat(70) + "’").segments)
        assertEquals(2, SmsLength.of("😊".repeat(36)).segments) // 72 UTF-16 units
    }

    @Test fun `accented letters in the GSM alphabet stay 7-bit, others do not`() {
        assertEquals(SmsLength.Encoding.GSM_7BIT, SmsLength.of("café ñ ü à £ ¥ §").encoding)
        assertEquals(SmsLength.Encoding.UCS2, SmsLength.of("ç").encoding) // lower-case c-cedilla is not in the table
        assertEquals(0, SmsLength.of("").segments)
    }

    @Test fun `matches the platform counter on random text`() {
        val rnd = Random(20261109)
        val pool = ("abcXYZ 0123 .,!?@\$_\n\r" + "{}[]^~|\\€" + "£¥èéùìòÇØøÅåÄÖÑÜäöñüà" + "`’“—çÿ" + "😊🎉").toList()
        // Surrogate halves must stay together, so draw from code points.
        val cps = pool.map { it.toString() }
        repeat(400) {
            val n = rnd.nextInt(0, 420)
            val text = buildString { repeat(n) { append(cps[rnd.nextInt(cps.size)]) } }
            val platform = SmsMessage.calculateLength(text, false)
            val mine = SmsLength.of(text)
            if (text.isEmpty()) return@repeat
            assertEquals("segments for '$text'", platform[0], mine.segments)
            assertEquals("units for '$text'", platform[1], mine.units)
            val platformUcs2 = platform[3] == SmsMessage.ENCODING_16BIT
            assertEquals("encoding for '$text'", platformUcs2, mine.encoding == SmsLength.Encoding.UCS2)
        }
    }

    @Test fun `any auto-reply message the validator accepts really is a single 7-bit segment`() {
        val rnd = Random(99)
        val chars = "abc xyz.,!?'\"()-_@#%&*+=:;/<>{}[]^~|\\`\$".toList()
        var accepted = 0
        repeat(600) {
            val m = buildString { repeat(rnd.nextInt(1, 200)) { append(chars[rnd.nextInt(chars.size)]) } }
            if (AutoReplyConfig.validateMessage(m) == null) {
                accepted++
                val platform = SmsMessage.calculateLength(m, false)
                assertEquals("accepted '$m' must be one segment", 1, platform[0])
                assertEquals("accepted '$m' must be 7-bit", SmsMessage.ENCODING_7BIT, platform[3])
            }
        }
        assert(accepted > 20) { "property test exercised too few accepted messages: $accepted" }
    }

    @Test fun `validator rejects a backtick and a text that only looks short`() {
        assertNotNull(AutoReplyConfig.validateMessage("busy, call me `later`"))
        assertNotNull(AutoReplyConfig.validateMessage("a".repeat(159) + "{")) // 161 septets
        assertNull(AutoReplyConfig.validateMessage("a".repeat(158) + "{"))   // 160 septets
        assertNull(AutoReplyConfig.validateMessage("I'm busy (back at 5), text me."))
    }
}
