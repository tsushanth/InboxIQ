package com.inboxiq.app.reply

import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartReplyUnitTest {
    private val addr = "+14155550101"
    private fun req(vararg m: Pair<Boolean, String>, label: MessageLabel = MessageLabel.PERSONAL, sent: List<String> = emptyList(), known: Boolean = true) =
        ReplyRequest(addr, label, m.mapIndexed { i, (inc, b) -> ThreadMsg(b, inc, i * 1000L, label = label) }, sent, known)

    @Test fun `a maps link is acknowledged, never answered yes or no`() {
        val r = SmartReply.analyze(req(true to "here's the spot https://maps.app.goo.gl/AbC123"))
        assertEquals(ReplyIntent.SHARE, r.intent)
        assertFalse(r.needsReply)
        assertTrue(r.suggestions.none { it.equals("Yes", true) || it.equals("No", true) })
        assertTrue(r.suggestions.none { it.contains("http") })
    }

    @Test fun `a verification code gets nothing`() {
        val r = SmartReply.analyze(req(true to "Your code is 482913. Do not share it.", known = false))
        assertTrue(r.suggestions.isEmpty())
    }

    @Test fun `user who never uses emoji never gets one`() {
        val r = SmartReply.analyze(req(true to "wanna get dinner?", false to "sure", true to "ok", false to "sounds good", true to "lol", false to "yes", true to "are you free tonight?"))
        assertTrue(r.suggestions.isNotEmpty())
        assertTrue(r.suggestions.none { UserStyle.EMOJI.containsMatchIn(it) })
    }

    @Test fun `emoji user can get their own favourite emoji`() {
        val sent = listOf("sounds good 👍", "yep 👍", "on it 👍", "see you there 😂")
        val r = SmartReply.analyze(req(true to "Are you coming to the party?", sent = sent, known = true).copy(
            messages = sent.map { ThreadMsg(it, false) } + ThreadMsg("Are you coming to the party?", true, 99)))
        assertTrue(r.suggestions.any { it.contains("👍") })
    }

    @Test fun `never an emoji or exclamation on bad news`() {
        val sent = listOf("lol 😂", "haha yes!", "great!!")
        val r = SmartReply.analyze(ReplyRequest(addr, MessageLabel.PERSONAL, sent.map { ThreadMsg(it, false) } + ThreadMsg("my dad passed away this morning", true, 99), isKnownContact = true))
        assertEquals(ReplyIntent.BAD_NEWS, r.intent)
        assertTrue(r.suggestions.all { !it.contains('!') && !UserStyle.EMOJI.containsMatchIn(it) })
    }

    @Test fun `learns the users own phrasing for this contact`() {
        val mine = listOf("yup works for me", "yup works for me", "yup works for me", "nah can't", "ok")
        val r = SmartReply.analyze(ReplyRequest(addr, MessageLabel.PERSONAL, mine.map { ThreadMsg(it, false) } + ThreadMsg("are you free tomorrow?", true, 99), isKnownContact = true))
        assertTrue(r.suggestions.toString(), r.suggestions.contains("yup works for me"))
        assertTrue(r.personalized >= 1)
    }

    @Test fun `own phrasings with digits, names or questions are never learned`() {
        val mine = listOf("call Bob at 555-0100", "Meet at 7?", "see you at Marylinn", "ok")
        val st = UserStyle(mine, mine)
        assertNull(st.phrase(Stance.AFFIRM)); assertNull(st.phrase(Stance.BYE))
    }

    @Test fun `matches lowercase and no-period habits`() {
        val mine = listOf("yeah i can do that", "sounds good to me", "no worries", "see you then")
        val r = SmartReply.analyze(ReplyRequest(addr, MessageLabel.PERSONAL, mine.map { ThreadMsg(it, false) } + ThreadMsg("thanks for your help", true, 99), isKnownContact = true))
        assertTrue(r.suggestions.toString(), r.suggestions.all { it.first().isLowerCase() || !it.first().isLetter() })
        assertTrue(r.suggestions.none { it.endsWith(".") })
    }

    @Test fun `formal thread gets formal wording`() {
        val mine = listOf("Hello, thank you for your message. I will send it this evening.", "Kindly let me know. Best regards.")
        val r = SmartReply.analyze(ReplyRequest(addr, MessageLabel.WORK, mine.map { ThreadMsg(it, false) } + ThreadMsg("Are you available for a call this afternoon?", true, 99), isKnownContact = true))
        assertTrue(r.suggestions.toString(), r.suggestions.any { it.endsWith(".") })
    }

    @Test fun `money request never offers a plain yes`() {
        val r = SmartReply.analyze(req(true to "can you venmo me \$20 for dinner?"))
        assertTrue(r.suggestions.isNotEmpty())
        assertTrue(r.suggestions.toString(), r.suggestions.none { StanceMatcher.of(it) == Stance.AFFIRM })
    }

    @Test fun `nothing when the user wrote last`() {
        assertEquals(Suppression.NOT_INCOMING, SmartReply.analyze(req(true to "hi?", false to "hey")).suppressed)
    }

    @Test fun `junk labels, short codes, alpha ids, toll free, group and email gateways are silent`() {
        for (a in listOf("55123", "EXAMPLEBANK", "+18005550142", "+14155550101,+14155550102", "a@example.com")) {
            assertTrue(a, SmartReply.analyze(ReplyRequest(a, MessageLabel.PERSONAL, listOf(ThreadMsg("are you free?", true)))).suggestions.isEmpty())
        }
        for (l in listOf(MessageLabel.PROMO, MessageLabel.SPAM, MessageLabel.SCAM, MessageLabel.OTP_2FA)) {
            assertTrue(l.name, SmartReply.analyze(req(true to "are you free?", label = l)).suggestions.isEmpty())
        }
    }

    @Test fun `an earlier question stays answerable behind a nudge`() {
        val r = SmartReply.analyze(req(true to "can you call me when you get a chance", true to "ok?"))
        assertEquals(ReplyIntent.REQUEST, r.intent)
    }

    @Test fun `Needs a reply - question yes, ok and thanks and reactions no`() {
        fun n(b: String) = SmartReply.needsReply(req(true to b))
        assertTrue(n("are you coming tonight?")); assertTrue(n("can you send me the file")); assertTrue(n("where are you"))
        assertFalse(n("ok")); assertFalse(n("thanks!")); assertFalse(n("Liked \"see you at 7\"")); assertFalse(n("👍"))
        assertFalse(SmartReply.needsReply(ReplyRequest("55123", MessageLabel.PERSONAL, listOf(ThreadMsg("Are you coming?", true)))))
    }

    // ---------------------------------------------------------------- generative tier
    private val askReq = req(true to "are you free tomorrow?")

    @Test fun `no model, a throwing model and a slow model all fall back to the rules`() = runTest {
        val base = RulesReplySuggester.suggest(askReq)
        assertEquals(base, GenerativeReplySuggester(null).suggest(askReq))
        assertEquals(base, GenerativeReplySuggester(ReplyModel { throw IllegalStateException("boom") }).suggest(askReq))
        assertEquals(base, GenerativeReplySuggester(ReplyModel { delay(10_000); "Sure" }, timeoutMs = 50).suggest(askReq))
    }

    @Test fun `generated lines are filtered for links, numbers, codes and length`() = runTest {
        val out = "Sure, see https://evil.example\nCall me on 4155550100\nYour code is fine\n${"x".repeat(200)}\nSure, I am around\n- Happy to help"
        val r = GenerativeReplySuggester(ReplyModel { out }).suggest(askReq)
        assertTrue(r.suggestions.toString(), r.suggestions.contains("Sure, I am around"))
        assertTrue(r.suggestions.all { ReplySafety.ok(it) })
        assertTrue(r.suggestions.size <= 3)
    }

    @Test fun `the model is never consulted for a message the rules suppress`() = runTest {
        var called = false
        val m = ReplyModel { called = true; "Yes" }
        GenerativeReplySuggester(m).suggest(req(true to "Your code is 482913", known = false))
        GenerativeReplySuggester(m).suggest(ReplyRequest("55123", MessageLabel.PERSONAL, listOf(ThreadMsg("are you free?", true))))
        GenerativeReplySuggester(m).suggest(req(true to "thanks!"))
        assertFalse(called)
    }
}
