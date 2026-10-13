package com.inboxiq.app.inbox

import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
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
        assertTrue(rule.headline.contains("keyword rule"))
        val model = LabelExplainer.explain(MessageLabel.PERSONAL, 0.42f, false, "hey are you free", "Personal")
        assertTrue(model.headline.contains("on-device model"))
        assertTrue(model.reasons.any { it.contains("low score") })
        assertNotNull(model.reasons.firstOrNull { it.contains("42%") })
    }

    @Test fun `unlabeled says it is still pending`() {
        assertEquals("Not sorted yet", LabelExplainer.explain(MessageLabel.UNLABELED, 0f, false, null, "x").headline)
    }
}
