package com.inboxiq.app.reactions

import com.inboxiq.app.sms.SmsLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionTextTest {
    private val family = "👨‍👩‍👧‍👦" // 11 units
    private val flag = "🇺🇸" // regional indicators U and S
    private val zwsp = "​"; private val zwnj = "‌"; private val hair = " "

    // ------------------------------------------------------------ exact formats

    @Test fun `the six quick emoji and the extra tapbacks use the iPhone wording with curly quotes`() {
        assertEquals("Liked “See you at 7”", ReactionText.compose(ReactionEmoji.THUMBS_UP, "See you at 7"))
        assertEquals("Loved “See you at 7”", ReactionText.compose(ReactionEmoji.HEART, "See you at 7"))
        assertEquals("Laughed at “See you at 7”", ReactionText.compose(ReactionEmoji.LAUGH, "See you at 7"))
        assertEquals("Emphasized “See you at 7”", ReactionText.compose(ReactionEmoji.SURPRISED, "See you at 7"))
        assertEquals("Disliked “See you at 7”", ReactionText.compose(ReactionEmoji.THUMBS_DOWN, "See you at 7"))
        assertEquals("Emphasized “See you at 7”", ReactionText.compose(ReactionEmoji.EXCLAMATION, "See you at 7"))
        assertEquals("Questioned “See you at 7”", ReactionText.compose(ReactionEmoji.QUESTION, "See you at 7"))
    }

    @Test fun `a heart without the variation selector is still Loved`() {
        assertEquals("Loved “hi”", ReactionText.compose("❤", "hi"))
    }

    @Test fun `sad and angry have no iPhone tapback and use the Google Messages form with invisible markers`() {
        assertEquals(
            "$zwsp😢$zwsp to “${hair}See you at 7$hair”",
            ReactionText.compose(ReactionEmoji.SAD, "See you at 7"),
        )
        assertEquals(
            "$zwsp😡$zwsp to “${hair}No$hair”",
            ReactionText.compose(ReactionEmoji.ANGRY, "No"),
        )
        assertEquals(
            "$zwsp😎$zwsp to “${hair}cool$hair”",
            ReactionText.compose("😎", "cool"),
        )
    }

    @Test fun `removal uses the iPhone removal wording for tapbacks and zero-width non-joiners for the rest`() {
        assertEquals("Removed a heart from “hi”", ReactionText.composeRemoval(ReactionEmoji.HEART, "hi"))
        assertEquals("Removed a like from “hi”", ReactionText.composeRemoval(ReactionEmoji.THUMBS_UP, "hi"))
        assertEquals("Removed a dislike from “hi”", ReactionText.composeRemoval(ReactionEmoji.THUMBS_DOWN, "hi"))
        assertEquals("Removed a laugh from “hi”", ReactionText.composeRemoval(ReactionEmoji.LAUGH, "hi"))
        assertEquals("Removed an exclamation from “hi”", ReactionText.composeRemoval(ReactionEmoji.SURPRISED, "hi"))
        assertEquals("Removed a question mark from “hi”", ReactionText.composeRemoval(ReactionEmoji.QUESTION, "hi"))
        assertEquals(
            "Removed $zwnj😢$zwnj from “${hair}hi$hair”",
            ReactionText.composeRemoval(ReactionEmoji.SAD, "hi"),
        )
    }

    // ------------------------------------------------------------ budget

    private val bodies = listOf(
        "", "ok", "See you at 7", "x".repeat(49), "x".repeat(50), "x".repeat(51), "x".repeat(400),
        "word ".repeat(40), "line one\nline two\r\n\r\nline three", "😀".repeat(60), family.repeat(10), flag.repeat(30),
        "é".repeat(60), "  \t spaced   out   text with   gaps ".repeat(4), "你好世界".repeat(20),
    )
    private val emojis = ReactionEmoji.QUICK + listOf(
        ReactionEmoji.THUMBS_DOWN, ReactionEmoji.EXCLAMATION, ReactionEmoji.QUESTION, "😎", "🔥", flag, "1️⃣",
        "👍🏽", family,
    )

    @Test fun `every composed text stays inside one UCS-2 segment, invisible characters included`() {
        for (e in emojis) for (b in bodies) {
            for (t in listOf(ReactionText.compose(e, b), ReactionText.composeRemoval(e, b))) {
                assertTrue("too long (${t.length}) for $e / ${b.take(12)}", t.length <= ReactionText.SEGMENT_UNITS)
                val c = SmsLength.of(t)
                assertEquals(1, c.segments)
                if (b.isNotBlank()) assertEquals(SmsLength.Encoding.UCS2, c.encoding)
            }
        }
    }

    @Test fun `the quote is capped at 50 units including the ellipsis`() {
        val t = ReactionText.compose(ReactionEmoji.THUMBS_UP, "x".repeat(400))
        val quote = t.removePrefix("Liked “").removeSuffix("”")
        assertEquals(50, quote.length)
        assertTrue(quote.endsWith("…"))
        assertEquals("x".repeat(49) + "…", quote)
        // exactly 50 characters fit without a cut
        assertEquals("Liked “${"x".repeat(50)}”", ReactionText.compose(ReactionEmoji.THUMBS_UP, "x".repeat(50)))
        assertEquals("Liked “${"x".repeat(49)}…”", ReactionText.compose(ReactionEmoji.THUMBS_UP, "x".repeat(51)))
    }

    @Test fun `removal texts get a smaller quote so the longer wording still fits`() {
        val t = ReactionText.composeRemoval(ReactionEmoji.QUESTION, "x".repeat(400))
        assertTrue(t.length <= 70)
        val quote = t.removePrefix("Removed a question mark from “").removeSuffix("”")
        assertTrue(quote.length < 50 && quote.endsWith("…"))
    }

    @Test fun `a very long emoji sequence shrinks the quote instead of overflowing the segment`() {
        val t = ReactionText.compose(family.repeat(4), "x".repeat(400))
        assertTrue(t.length <= 70)
        assertTrue(t.contains("…"))
    }

    // ------------------------------------------------------------ truncation on character boundaries

    private fun assertNoLoneSurrogate(s: String) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) { assertTrue("lone high surrogate", i + 1 < s.length && Character.isLowSurrogate(s[i + 1])); i += 2; continue }
            assertFalse("lone low surrogate", Character.isLowSurrogate(c))
            i++
        }
    }

    @Test fun `a cut never splits a surrogate pair`() {
        for (n in 20..70) {
            val t = ReactionText.compose(ReactionEmoji.HEART, "a" + "😀".repeat(n))
            assertNoLoneSurrogate(t)
        }
        val quote = ReactionText.compose(ReactionEmoji.HEART, "😀".repeat(60)).removePrefix("Loved “").removeSuffix("”")
        assertEquals("😀".repeat(24) + "…", quote) // 24 * 2 + 1 = 49 units; a 25th would need 51
    }

    @Test fun `a cut never splits a ZWJ sequence, a flag, a skin tone or a combining mark`() {
        for (unit in listOf(family, flag, "👍🏽", "é", "1️⃣", "❤️")) {
            for (n in 3..30) {
                val body = "x" + unit.repeat(n)
                val t = ReactionText.compose(ReactionEmoji.THUMBS_UP, body)
                val quote = t.removePrefix("Liked “").removeSuffix("”").removeSuffix("…")
                assertTrue("quote must be x then whole units", quote == "x" || quote.startsWith("x"))
                val rest = quote.removePrefix("x")
                assertEquals("whole units only", "", rest.replace(unit, ""))
                assertNoLoneSurrogate(t)
            }
        }
    }

    @Test fun `whitespace and newlines collapse to single spaces and the ends are trimmed`() {
        assertEquals("Liked “one two three”", ReactionText.compose(ReactionEmoji.THUMBS_UP, "  one\n\ntwo\t\tthree \r\n"))
        assertEquals("Liked “a b”", ReactionText.compose(ReactionEmoji.THUMBS_UP, "a  b"))
    }

    @Test fun `a trailing space before the cut does not leave a space before the ellipsis`() {
        val t = ReactionText.compose(ReactionEmoji.THUMBS_UP, "x".repeat(48) + " yyyy")
        assertTrue(t.contains("x…”"))
    }

    @Test fun `invisible markers inside the quoted text are dropped so they cannot be mistaken for the format`() {
        val t = ReactionText.compose(ReactionEmoji.SAD, "a${zwsp}b${hair}c${zwnj}d")
        assertEquals("$zwsp😢$zwsp to “${hair}abcd$hair”", t)
    }

    @Test fun `an empty or blank body falls back to a short phrase without quotes`() {
        assertEquals("Liked a message", ReactionText.compose(ReactionEmoji.THUMBS_UP, ""))
        assertEquals("Liked a message", ReactionText.compose(ReactionEmoji.THUMBS_UP, " \n\t "))
        assertEquals("$zwsp😢$zwsp to a message", ReactionText.compose(ReactionEmoji.SAD, ""))
        assertEquals("Removed a heart from a message", ReactionText.composeRemoval(ReactionEmoji.HEART, ""))
        assertEquals("Removed $zwnj😢$zwnj from a message", ReactionText.composeRemoval(ReactionEmoji.SAD, ""))
    }

    // ------------------------------------------------------------ the reading side (slice 2 builds on this)

    @Test fun `what is composed is read back, for every emoji and both directions`() {
        for (e in emojis) for (b in listOf("See you at 7", "short", "x".repeat(200))) {
            val add = ReactionText.parse(ReactionText.compose(e, b))
            assertNotNull("add $e", add)
            assertFalse(add!!.removed)
            assertTrue("emoji for $e was ${add.emoji}", ReactionEmoji.same(add.emoji, ReactionEmoji.tapback(e)?.emoji ?: e))
            assertTrue(ReactionText.normalize(b).startsWith(add.quoted))
            val rem = ReactionText.parse(ReactionText.composeRemoval(e, b))
            assertNotNull("remove $e", rem)
            assertTrue(rem!!.removed)
            assertTrue(ReactionEmoji.same(rem.emoji, ReactionEmoji.tapback(e)?.emoji ?: e))
        }
    }

    @Test fun `a truncated quote is flagged and loses its ellipsis`() {
        val p = ReactionText.parse(ReactionText.compose(ReactionEmoji.HEART, "y".repeat(200)))!!
        assertTrue(p.truncated)
        assertEquals("y".repeat(49), p.quoted)
        assertFalse(ReactionText.parse("Loved “short”")!!.truncated)
    }

    @Test fun `iPhone wording in all six forms and the removal forms`() {
        val words = mapOf(
            "Loved" to ReactionEmoji.HEART, "Liked" to ReactionEmoji.THUMBS_UP, "Disliked" to ReactionEmoji.THUMBS_DOWN,
            "Laughed at" to ReactionEmoji.LAUGH, "Emphasized" to ReactionEmoji.EXCLAMATION, "Emphasised" to ReactionEmoji.EXCLAMATION,
            "Questioned" to ReactionEmoji.QUESTION,
        )
        for ((w, e) in words) {
            val p = ReactionText.parse("$w “Dinner at 8?”")!!
            assertEquals(e, p.emoji); assertEquals("Dinner at 8?", p.quoted); assertFalse(p.removed)
            assertEquals(ReactionText.Style.IPHONE_TAPBACK, p.style)
        }
        val removals = mapOf(
            "a heart" to ReactionEmoji.HEART, "a like" to ReactionEmoji.THUMBS_UP, "a dislike" to ReactionEmoji.THUMBS_DOWN,
            "a laugh" to ReactionEmoji.LAUGH, "an exclamation" to ReactionEmoji.EXCLAMATION, "an exclamation mark" to ReactionEmoji.EXCLAMATION,
            "a question mark" to ReactionEmoji.QUESTION,
        )
        for ((n, e) in removals) {
            val p = ReactionText.parse("Removed $n from “Dinner at 8?”")!!
            assertEquals(e, p.emoji); assertTrue(p.removed)
        }
    }

    @Test fun `Google Messages form, with and without its invisible characters, and the iOS 18 any-emoji form`() {
        val g = ReactionText.parse("$zwsp😂$zwsp to “${hair}See you$hair”")!!
        assertEquals("😂", g.emoji); assertEquals("See you", g.quoted); assertEquals(ReactionText.Style.GOOGLE, g.style)

        val plain = ReactionText.parse("😂 to “See you”")!!
        assertEquals("😂", plain.emoji); assertEquals(ReactionText.Style.PLAIN, plain.style); assertFalse(plain.removed)

        val gr = ReactionText.parse("Removed $zwnj😂$zwnj from “${hair}See you$hair”")!!
        assertTrue(gr.removed); assertEquals(ReactionText.Style.GOOGLE, gr.style)

        val ios = ReactionText.parse("Reacted 😎 to “See you”")!!
        assertEquals("😎", ios.emoji); assertEquals(ReactionText.Style.IPHONE_EMOJI, ios.style)
        val iosRemoved = ReactionText.parse("Removed 😎 from “See you”")!!
        assertTrue(iosRemoved.removed)
    }

    @Test fun `straight quotes are accepted as an alternate and multi-line quotes are kept`() {
        assertEquals("hi", ReactionText.parse("Liked \"hi\"")!!.quoted)
        assertEquals("line1\nline2", ReactionText.parse("Liked “line1\nline2”")!!.quoted)
    }

    @Test fun `ordinary text is not a reaction`() {
        for (t in listOf(
            "I liked “this” movie", "Liked it a lot", "Loved your message", "liked “x”", "Loved “x” and more",
            "want to “go”", "I want to “do it”", "ok to “go”", "to “x”", "", "Removed a thing from “x”",
            "Reacted badly to “x”", "Hello “world”", "9 to “x” wait",
        )) assertNull(t, ReactionText.parse(t))
        assertFalse(ReactionText.isReactionText("See you at 7"))
        assertTrue(ReactionText.isReactionText("Loved “See you at 7”"))
    }

    @Test fun `quote budget arithmetic`() {
        assertEquals(50, ReactionText.quoteBudget("Laughed at “", "”"))
        assertEquals(40, ReactionText.quoteBudget("Removed an exclamation from “", "”"))
        assertEquals(1, ReactionText.quoteBudget("x".repeat(80), "”"))
    }
}
