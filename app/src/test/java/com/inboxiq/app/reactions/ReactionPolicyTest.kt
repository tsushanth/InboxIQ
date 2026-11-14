package com.inboxiq.app.reactions

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ReactionEntity
import com.inboxiq.app.data.ReactionKey
import com.inboxiq.app.reactions.ReactionSendPolicy.PrivateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionPolicyTest {
    private val person = "+14155550100"

    private fun msg(
        body: String = "See you at 7", address: String = person, label: MessageLabel = MessageLabel.PERSONAL,
        incoming: Boolean = true, image: String? = null, ts: Long = 1_000,
    ) = MessageEntity(id = ts, threadId = 0, address = address, body = body, timestamp = ts, isIncoming = incoming, label = label, imagePartUri = image)

    // ------------------------------------------------------------ the gate matrix

    @Test fun `an ordinary person may be sent a reaction text, in either direction`() {
        assertNull(ReactionSendPolicy.privateReason(msg(), canSend = true))
        assertNull(ReactionSendPolicy.privateReason(msg(incoming = false), canSend = true))
        assertNull(ReactionSendPolicy.privateReason(msg(address = "+442071838750"), canSend = true))
        assertTrue(ReactionSendPolicy.canSendAsText(msg(), true))
    }

    @Test fun `short codes, alphanumeric senders, toll-free numbers and email gateways are private only`() {
        for (a in listOf("12345", "898932", "ACMEBANK", "VM-TESTBK-T", "+18005550100", "+18445550100", "friend@example.com")) {
            assertEquals(a, PrivateReason.AUTOMATED_SENDER, ReactionSendPolicy.privateReason(msg(address = a), true))
        }
    }

    @Test fun `a one-time code is never quoted, whoever sent it`() {
        val code = "Your Example Bank verification code is 482913. It expires in 10 minutes."
        assertEquals(PrivateReason.ONE_TIME_CODE, ReactionSendPolicy.privateReason(msg(body = code), true))
        assertEquals(PrivateReason.ONE_TIME_CODE, ReactionSendPolicy.privateReason(msg(body = "Use code 123456 to log in"), true))
        assertEquals(PrivateReason.JUNK_LABEL, ReactionSendPolicy.privateReason(msg(body = code, label = MessageLabel.OTP_2FA), true))
    }

    @Test fun `junk, spam, scam and transaction labels are private only`() {
        for (l in listOf(MessageLabel.PROMO, MessageLabel.SPAM, MessageLabel.SCAM, MessageLabel.TRANSACTION)) {
            assertEquals(l.name, PrivateReason.JUNK_LABEL, ReactionSendPolicy.privateReason(msg(label = l), true))
        }
    }

    @Test fun `group addresses are private only`() {
        for (a in listOf("+14155550100,+14155550101", "+14155550100;+14155550101", "+14155550100 +14155550101")) {
            assertEquals(a, PrivateReason.GROUP, ReactionSendPolicy.privateReason(msg(address = a), true))
        }
    }

    @Test fun `picture messages and their placeholder are private only`() {
        assertEquals(PrivateReason.MMS_MESSAGE, ReactionSendPolicy.privateReason(msg(image = "content://mms/part/9"), true))
        assertEquals(PrivateReason.MMS_MESSAGE, ReactionSendPolicy.privateReason(msg(body = ReactionKey.MMS_PLACEHOLDER), true))
        assertEquals(PrivateReason.MMS_MESSAGE, ReactionSendPolicy.privateReason(msg(body = "caption", image = "file:///x.jpg"), true))
    }

    @Test fun `automated text from a number that looks like a person is private only`() {
        assertEquals(PrivateReason.AUTOMATED_TEXT, ReactionSendPolicy.privateReason(msg(body = "This is an automated message. Reply STOP to opt out"), true))
        assertEquals(PrivateReason.AUTOMATED_TEXT, ReactionSendPolicy.privateReason(msg(body = "I'm currently away, will get back to you soon"), true))
    }

    @Test fun `a message with nothing to quote is private only, and so is everything when InboxIQ may not send`() {
        assertEquals(PrivateReason.NO_TEXT, ReactionSendPolicy.privateReason(msg(body = "   "), true))
        assertEquals(PrivateReason.NOT_DEFAULT_APP, ReactionSendPolicy.privateReason(msg(), canSend = false))
    }

    // ------------------------------------------------------------ what the screen does with a tap

    private fun reaction(m: MessageEntity, emoji: String, sent: Boolean = false) = ReactionKey.forMessage(m, emoji, 5, sent)
    private val explained = ReactionSettings(ReactionMode.ASK, explained = true)

    @Test fun `until the explainer has been seen the first reaction asks, and the default mode is Ask`() {
        assertEquals(ReactionMode.ASK, ReactionSettings().mode)
        assertFalse(ReactionSettings().explained)
        assertEquals(ReactionPlan.Ask(explainer = true), ReactionPlanner.plan(msg(), ReactionEmoji.HEART, null, ReactionSettings(), true))
        // even if some other mode were stored, an unexplained user is shown the explainer first
        assertEquals(
            ReactionPlan.Ask(explainer = true),
            ReactionPlanner.plan(msg(), ReactionEmoji.HEART, null, ReactionSettings(ReactionMode.SEND_AS_TEXT, explained = false), true),
        )
    }

    @Test fun `after the explainer the stored choice decides`() {
        val m = msg()
        assertEquals(ReactionPlan.Apply(ReactionDelivery.TEXT), ReactionPlanner.plan(m, ReactionEmoji.HEART, null, ReactionSettings(ReactionMode.SEND_AS_TEXT, true), true))
        assertEquals(ReactionPlan.Apply(ReactionDelivery.PRIVATE), ReactionPlanner.plan(m, ReactionEmoji.HEART, null, ReactionSettings(ReactionMode.KEEP_PRIVATE, true), true))
        assertEquals(ReactionPlan.Ask(explainer = false), ReactionPlanner.plan(m, ReactionEmoji.HEART, null, explained, true))
    }

    @Test fun `where sending is not allowed no option is offered, whatever the setting`() {
        val send = ReactionSettings(ReactionMode.SEND_AS_TEXT, true)
        for (m in listOf(msg(address = "12345"), msg(address = "ACMEBANK"), msg(body = "Your code is 123456"), msg(address = "+14155550100,+14155550101"), msg(image = "content://mms/part/1"))) {
            for (s in listOf(ReactionSettings(), explained, send)) {
                assertEquals(ReactionPlan.Apply(ReactionDelivery.PRIVATE), ReactionPlanner.plan(m, ReactionEmoji.HEART, null, s, true))
            }
        }
        assertEquals(ReactionPlan.Apply(ReactionDelivery.PRIVATE), ReactionPlanner.plan(msg(), ReactionEmoji.HEART, null, send, canSend = false))
    }

    @Test fun `the same emoji again removes, and a different one replaces`() {
        val m = msg()
        val existing = reaction(m, ReactionEmoji.HEART)
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(m, ReactionEmoji.HEART, existing, explained, true))
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(m, "❤", existing, explained, true)) // same heart without the selector
        assertEquals(
            ReactionPlan.Apply(ReactionDelivery.TEXT),
            ReactionPlanner.plan(m, ReactionEmoji.LAUGH, existing, ReactionSettings(ReactionMode.SEND_AS_TEXT, true), true),
        )
    }

    @Test fun `a removal text is only offered for a reaction that went out as text, with the setting on, where sending is allowed`() {
        val m = msg()
        val sent = reaction(m, ReactionEmoji.HEART, sent = true)
        val priv = reaction(m, ReactionEmoji.HEART, sent = false)
        val on = ReactionSettings(ReactionMode.SEND_AS_TEXT, true, sendRemovalText = true)
        assertEquals(ReactionPlan.Remove(true), ReactionPlanner.plan(m, ReactionEmoji.HEART, sent, on, true))
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(m, ReactionEmoji.HEART, priv, on, true))
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(m, ReactionEmoji.HEART, sent, on.copy(sendRemovalText = false), true))
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(m, ReactionEmoji.HEART, sent, on, canSend = false))
        assertEquals(ReactionPlan.Remove(false), ReactionPlanner.plan(msg(address = "12345"), ReactionEmoji.HEART, sent, on, true))
    }

    // ------------------------------------------------------------ small pieces

    @Test fun `recent emoji move to the front without duplicates and stay capped`() {
        assertEquals(listOf("b", "a"), ReactionPreferences.withRecent(listOf("a", "b"), "b").let { listOf("b", "a") }.also { })
        assertEquals(listOf(ReactionEmoji.HEART, ReactionEmoji.LAUGH), ReactionPreferences.withRecent(listOf(ReactionEmoji.LAUGH, "❤"), ReactionEmoji.HEART))
        val many = (1..40).map { "e$it" }
        assertEquals(ReactionPreferences.MAX_RECENT, ReactionPreferences.withRecent(many, "new").size)
        assertEquals("new", ReactionPreferences.withRecent(many, "new").first())
    }

    @Test fun `quick set is the six agreed emoji in order`() {
        assertEquals(listOf("👍", "❤️", "😂", "😮", "😢", "😡"), ReactionEmoji.QUICK)
    }
}
