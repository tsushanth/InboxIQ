package com.inboxiq.app.inbox

import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelExplainerTest {
    @Test fun `heuristic explanation names the trigger and agrees with match`() {
        val body = "Your code is 482913"
        val reason = HeuristicRules.explain(body)!!
        assertEquals(HeuristicRules.match(body), reason.result)
        assertTrue(reason.explanation.contains("6-digit"))
        assertTrue(reason.explanation.contains("code"))
    }

    @Test fun `match behaviour is unchanged for scam promo and none`() {
        assertEquals(MessageLabel.SCAM, HeuristicRules.match("Act now to claim your prize")!!.label)
        assertEquals(MessageLabel.PROMO, HeuristicRules.match("20% off today, reply STOP")!!.label)
        assertNull(HeuristicRules.match("see you at lunch"))
        // regression from the file's own history: "irs" must not match inside "First"
        assertNull(HeuristicRules.match("First Tech Federal Credit Union balance alert"))
    }

    @Test fun `user correction wins`() {
        val e = LabelExplainer.explain(MessageLabel.WORK, 1f, true, "anything", "Work")
        assertEquals("You filed this under Work", e.headline)
    }

    @Test fun `rule-based and model-based explanations differ and low confidence is flagged`() {
        val rule = LabelExplainer.explain(MessageLabel.OTP_2FA, 0.9f, false, "Your code is 482913", "Code")
        assertTrue(rule.headline.contains("by a rule"))
        val model = LabelExplainer.explain(MessageLabel.PERSONAL, 0.42f, false, "hey are you free", "Personal")
        assertTrue(model.headline.contains("on-device model"))
        assertTrue(model.reasons.any { it.contains("low score") })
        assertNotNull(model.reasons.firstOrNull { it.contains("42%") })
    }

    @Test fun `unlabeled says it is still pending`() {
        assertEquals("Not sorted yet", LabelExplainer.explain(MessageLabel.UNLABELED, 0f, false, null, "x").headline)
    }

    @Test fun `scam explanation names the real evidence, not a keyword`() {
        val body = "Your account has been locked due to suspicious activity. Verify your identity now: bit.ly/3xK9fPq"
        val e = LabelExplainer.explain(MessageLabel.SCAM, 0.8f, false, body, "Scam", address = "+14155550199", recentIncoming = listOf(body))
        assertTrue(e.headline.contains("by a rule"))
        assertTrue(e.reasons.any { it.contains("shortened link") })
        assertTrue(e.reasons.any { it.contains("locked") })
        assertTrue(e.reasons.any { it.contains("sends nothing anywhere") })
    }

    @Test fun `known contact explanation says how many replies`() {
        val e = LabelExplainer.explain(MessageLabel.PERSONAL, 0.9f, false, "hello", "Personal", address = "+14155550142", recentIncoming = listOf("hello"), replies = 7)
        assertTrue(e.reasons.any { it.contains("7 times") })
    }

    @Test fun `a label the current rules would not reproduce is not blamed on a rule`() {
        // stored as Personal by an older build, but today's rules read the text as a scam
        val body = "FINAL NOTICE: unpaid toll balance of \$8.75. Pay now to avoid a late fee: https://tollpay-ny.cc"
        val e = LabelExplainer.explain(MessageLabel.PERSONAL, 0.9f, false, body, "Personal", address = "+14155550199", recentIncoming = listOf(body))
        assertFalse(e.headline.contains("by a rule"))
        assertTrue(e.reasons.any { it.contains("scam") && it.contains("out of date") })
    }

    @Test fun `spam decided by the model is described as a model score, not a rule`() {
        val e = LabelExplainer.explain(MessageLabel.SPAM, 0.93f, false, "hello there", "Spam", address = "+14155550177", recentIncoming = listOf("hello there"))
        assertTrue(e.headline.contains("on-device model"))
        assertTrue(e.reasons.any { it.contains("93%") })
    }

    @Test fun `a label for a transactional notice cites the notice`() {
        val body = "Initech: Purchase of \$42.18 at Corner Market with card ending 4421."
        val e = LabelExplainer.explain(MessageLabel.PERSONAL, 0.75f, false, body, "Personal", address = "38201", recentIncoming = listOf(body))
        assertTrue(e.reasons.any { it.contains("account or payment notice") })
    }
}
