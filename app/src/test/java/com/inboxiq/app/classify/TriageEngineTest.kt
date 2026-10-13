package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.MessageLabel.OTP_2FA
import com.inboxiq.app.data.MessageLabel.PERSONAL
import com.inboxiq.app.data.MessageLabel.PROMO
import com.inboxiq.app.data.MessageLabel.SCAM
import com.inboxiq.app.data.MessageLabel.SPAM
import com.inboxiq.app.data.MessageLabel.WORK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageEngineTest {
    private fun thread(address: String, replies: Int, vararg bodies: String) = ThreadContext(address, bodies.toList(), replies)
    private fun label(address: String, replies: Int, body: String, model: Float? = null) =
        TriageEngine.classify(thread(address, replies, body), model?.let { p -> SpamScorer { p } }).label

    private val friend = "+14155550142"

    // ------------------------------------------------------------------ OTP
    @Test fun `verification codes in many formats`() {
        for (t in listOf(
            "Your code is 482913", "G-482913 is your Google verification code.", "<#> 4821 is your OTP. FA+abc",
            "Use 482 913 to verify your account", "Your Steam Guard code: K4T9X", "Login code: 482-913. Expires in 5 min.",
            "482913 is your Uber code. Never share it.", "Your one-time passcode is 4829",
        )) assertEquals(t, OTP_2FA, label("22000", 0, t))
    }

    @Test fun `numbers that are not codes stay out of OTP`() {
        assertEquals(PERSONAL, label(friend, 9, "my zip code is 78666, right?"))
        assertEquals(PERSONAL, label(friend, 9, "send me the wifi code again"))
        assertTrue(label("22000", 0, "Use promo code SAVE20 at checkout") != OTP_2FA)
        assertEquals(PERSONAL, label(friend, 5, "Pin is 4821 for the garage, don't tell anyone"))
    }

    // ------------------------------------------------------------------ SCAM
    @Test fun `smishing patterns are scams from an ordinary number`() {
        for (t in listOf(
            "USPS: Your parcel could not be delivered. A redelivery fee of \$1.99 is required. Pay here: https://usps-redeliver.top/x1",
            "FINAL NOTICE: unpaid toll balance of \$8.75. Pay now to avoid a late fee: https://tollpay-ny.cc",
            "Your account has been locked due to suspicious activity. Verify your identity now: bit.ly/3xK9fPq",
            "IRS: You have an unpaid tax balance. Legal action will be taken. Call 555-0143 immediately to avoid arrest.",
            "Is this you in this video? tinyurl.com/9ia7h6C",
            "Congratulations! You have been selected to receive a \$1000 gift card. Claim it now: bit.ly/3xK9fPq",
            "We are hiring remote assistants. Earn \$200-\$500 per day, no experience needed. Contact HR on WhatsApp +1 555 0143",
            "Visit http://193.46.12.8/confirm to keep your account",
        )) assertEquals(t, SCAM, label("+14155550199", 0, t))
    }

    @Test fun `a shortened link alone or urgency alone is not a scam`() {
        assertEquals(PERSONAL, label("+14155550199", 0, "see this bit.ly/3xK9fPq"))
        assertEquals(PERSONAL, label("+14155550199", 0, "call me asap, urgent, it's about the car"))
    }

    @Test fun `a bank alert from a short code that says call us is not a scam`() {
        val t = "Acme Bank: Card ending 4341 was used for \$441.49 at Acme. If this wasn't you, call 1-888-555-0119 or lock your card in the app."
        assertEquals(PERSONAL, label("815299", 0, t))
        assertEquals(PERSONAL, label("ACMEBANK", 0, t))
    }

    @Test fun `the same words from an ordinary number posing as a company are a scam`() {
        val t = "Amazon: an order of \$187.40 for an iPhone was placed on your account. If this wasn't you call 555-0110 to cancel."
        assertEquals(SCAM, label("+14155550110", 0, t))
    }

    // ------------------------------------------------------------------ relationship
    @Test fun `a number you really talk to is never spam`() {
        val pitch = "Are you interested in selling your home? We pay cash. Call 555-0188 for a free quote"
        assertEquals(SPAM, label(friend, 0, pitch))
        assertEquals(PERSONAL, label(friend, 4, pitch))
        assertEquals(PERSONAL, label(friend, 4, "hey u free tonight", model = 0.99f))
    }

    @Test fun `a known contact is scam only on overwhelming evidence`() {
        // hijacked friend: video lure plus a shortened link is two independent strong signals
        assertEquals(SCAM, label(friend, 9, "is this you in this video? bit.ly/3xK9fPq"))
        // a friend sharing a link or being urgent is not
        assertEquals(PERSONAL, label(friend, 9, "call me asap!! urgent!! see this bit.ly/3xK9fPq"))
        assertEquals(PERSONAL, label(friend, 9, "have you seen this? https://news.example.com/a/1"))
    }

    @Test fun `answering once is weak evidence`() {
        val scam = "FINAL NOTICE: unpaid toll balance of \$8.75. Pay now to avoid a late fee: https://tollpay-ny.cc"
        assertEquals(SCAM, label(friend, 1, scam)) // "who is this?" to a scammer must not rescue it
        assertEquals(Relationship.WEAK, Relationship.of(SenderProfile.of(friend), 2))
        assertEquals(Relationship.STRONG, Relationship.of(SenderProfile.of(friend), 3))
        assertEquals("short codes cannot be a relationship", Relationship.NONE, Relationship.of(SenderProfile.of("22000"), 9))
    }

    // ------------------------------------------------------------------ work / promo / spam / personal
    @Test fun `work vocabulary`() {
        for (t in listOf(
            "Standup moved to 10:15 today, same zoom link as the calendar invite.",
            "Can anyone cover my 6am shift on Saturday? I'll take your next closing shift.",
            "[PagerDuty] TRIGGERED #4821: payments high error rate (SEV2). Ack: https://pd.example.com/i/1",
            "PR #482 is ready for review when you have a sec. It touches the billing service.",
            "Hi, this is Dana from Contoso Talent. Your profile stood out for our Staff Engineer opening. Open to a quick call?",
        )) assertEquals(t, WORK, label(friend, 6, t))
    }

    @Test fun `ordinary talk about a meeting or a team is not work`() {
        assertEquals(PERSONAL, label(friend, 8, "see you at dinner, I'm meeting the team for drinks first lol"))
        assertEquals(PERSONAL, label(friend, 8, "can you review my resume? the link is https://docs.example.com/d/8262"))
    }

    @Test fun `marketing with opt-out is promo, appointment reminder with opt-out is not`() {
        assertEquals(PROMO, label("72345", 0, "Acme: 20% off everything this weekend only! Shop now example.com/s. Reply STOP to opt out."))
        assertEquals(PERSONAL, label("ACMERX", 0, "Acme Pharmacy: Your prescription is ready for pickup. Reply STOP to opt out."))
        assertEquals(PERSONAL, label("BRIGHTSMILE", 0, "Reminder: you have an appointment on Tuesday at 3:30 PM. Reply C to confirm or R to reschedule."))
    }

    @Test fun `delivery and account notices are personal and cannot be called spam even when the model is sure`() {
        assertEquals(PERSONAL, label("ACMEPARCEL", 0, "Your order #48213 has shipped! Track it here: https://acme.example.com/t/1", model = 0.97f))
        assertEquals(PERSONAL, label("38201", 0, "Initech: Purchase of \$42.18 at Corner Market with card ending 4421.", model = 0.95f))
    }

    @Test fun `short code broadcasts without a code or account words are promo`() {
        assertEquals(PROMO, label("38201", 0, "Your cart misses you! Complete your order in the next hour and enjoy free shipping."))
    }

    @Test fun `spam model alone needs high confidence and is ignored for known contacts`() {
        assertEquals(PERSONAL, label("+14155550177", 0, "hello there how are you", model = 0.5f))
        assertEquals(SPAM, label("+14155550177", 0, "hello there how are you", model = 0.95f))
        assertEquals(PERSONAL, label("+14155550177", 5, "hello there how are you", model = 0.95f))
    }

    @Test fun `personal chatter stays personal`() {
        for (t in listOf("lol that is so funny 😂😂", "ok", "running 10 min late, grab us a table?", "Happy birthday!! 🎂", "")) {
            assertEquals(t, PERSONAL, label(friend, 3, t))
        }
    }

    // ------------------------------------------------------------------ thread level
    @Test fun `newest message counts most but one clear scam is not outvoted`() {
        val ctx = thread("+14155550199", 0, "ok sounds good", "see you then", "Are you free on Saturday?")
        assertEquals(PERSONAL, TriageEngine.classify(ctx, null).label)
        val hijack = thread(friend, 8, "is this you in this video? bit.ly/3xK9fPq", "hey how are you", "lunch tomorrow?")
        assertEquals(SCAM, TriageEngine.classify(hijack, null).label)
    }

    @Test fun `model is not consulted when rules decide`() {
        var calls = 0
        val counting = SpamScorer { calls++; 0.1f }
        TriageEngine.classify(thread("22000", 0, "Your code is 482913"), counting)
        TriageEngine.classify(thread("+14155550199", 0, "FINAL NOTICE: unpaid toll balance of \$8.75. Pay now: https://tollpay-ny.cc"), counting)
        TriageEngine.classify(thread(friend, 12, "anything at all"), counting)
        assertEquals("OTP, scam and known-contact threads never load the model", 0, calls)
        TriageEngine.classify(thread("+14155550177", 0, "hello there"), counting)
        assertEquals(1, calls)
    }

    @Test fun `a missing model means rules only, not a crash or a guess`() {
        val nan = SpamScorer { Float.NaN }
        val v = TriageEngine.classify(thread("+14155550177", 0, "hello there"), nan)
        assertEquals(PERSONAL, v.label)
        assertFalse(v.usedModel)
    }

    @Test fun `evidence is human readable and never empty for rule decisions`() {
        val v = TriageEngine.classify(thread("+14155550199", 0, "Your account has been locked. Verify your identity now: bit.ly/3xK9fPq"), null)
        assertEquals(SCAM, v.label)
        assertTrue(v.evidence.isNotEmpty() && v.evidence.all { it.isNotBlank() })
        assertEquals(Basis.RULE, v.basis)
    }

    @Test fun `HeuristicRules is text only and keeps its contract`() {
        assertEquals(OTP_2FA, HeuristicRules.match("Your code is 482913")!!.label)
        assertEquals(SCAM, HeuristicRules.match("Act now to claim your prize")!!.label)
        assertEquals(PROMO, HeuristicRules.match("20% off today, reply STOP")!!.label)
        assertEquals(null, HeuristicRules.match("see you at lunch"))
        assertEquals(null, HeuristicRules.match("First Tech Federal Credit Union balance alert"))
        assertEquals("never claims a label for a plain personal or model-only decision", null, HeuristicRules.match("hey are you free"))
    }

    @Test fun `receiver override sees the sender - a known contact is not re-filed by one pushy text`() {
        val pushy = "call me asap!! urgent!! see this bit.ly/3xK9fPq"
        assertEquals(null, HeuristicRules.match(pushy, friend, 9))
        assertEquals(null, HeuristicRules.match("20% off today, reply STOP", friend, 9))
        assertEquals(SCAM, HeuristicRules.match("is this you in this video? bit.ly/3xK9fPq", friend, 9)!!.label)
        assertEquals(OTP_2FA, HeuristicRules.match("Your code is 482913", "22000", 0)!!.label)
    }

    @Test fun `all outputs are real labels`() {
        val v = TriageEngine.classify(thread(friend, 0, "x"), null)
        assertTrue(v.label != MessageLabel.UNLABELED && v.confidence in 0f..1f)
    }
}
