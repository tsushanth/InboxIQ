package com.inboxiq.app.reply

import com.inboxiq.app.classify.QuickReplySuggester
import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Automated notices never get a one-tap reply, an inline notification reply or a "needs a reply" mark. */
class ReplyGateTransactionsTest {
    @Test fun `a transactions label suppresses reply suggestions`() {
        assertEquals(Suppression.JUNK_LABEL, ReplyGate.senderCheck("+13035550116", listOf(MessageLabel.TRANSACTION)))
    }

    @Test fun `a bank alert from an ordinary number is judged as a notice by its text`() {
        val s = ReplyGate.textCheck("Vandelay Airlines: Flight AC2275 is delayed 45 minutes. New departure 11:00 AM, gate B12.", "+13035550116", 0, false)
        assertEquals(Suppression.JUNK_TEXT, s)
    }

    @Test fun `notification inline reply is not offered for transactions`() {
        assertFalse(QuickReplySuggester.needsResponse(true, MessageLabel.TRANSACTION))
    }
}
