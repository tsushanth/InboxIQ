package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private val t = 1_000_000L

    @Test fun `every label has its own channel except spam and scam which share a silent one`() {
        val channels = MessageLabel.entries.map { NotificationPolicy.channelFor(it) }
        assertEquals(MessageChannel.JUNK, NotificationPolicy.channelFor(MessageLabel.SCAM))
        assertEquals(MessageChannel.JUNK, NotificationPolicy.channelFor(MessageLabel.SPAM))
        assertEquals(6, channels.toSet().size)
        assertTrue(MessageChannel.JUNK.importance <= 2 && MessageChannel.PROMO.importance <= 2)
        assertEquals("incoming_messages", MessageChannel.GENERAL.id) // existing users keep their settings for this channel
    }

    @Test fun `muted thread does not notify`() {
        assertFalse(NotificationPolicy.plan(MessageLabel.PERSONAL, "hi", t, t, muted = true).notify)
        assertTrue(NotificationPolicy.plan(MessageLabel.PERSONAL, "hi", t, t, muted = false).notify)
    }

    @Test fun `personal and work can be replied to inline but codes promos and junk cannot`() {
        assertTrue(NotificationPolicy.plan(MessageLabel.PERSONAL, "lunch?", t, t, false).canReply)
        assertTrue(NotificationPolicy.plan(MessageLabel.WORK, "ok?", t, t, false).canReply)
        listOf(MessageLabel.OTP_2FA, MessageLabel.PROMO, MessageLabel.SPAM, MessageLabel.SCAM).forEach {
            assertFalse("$it", NotificationPolicy.plan(it, "x", t, t, false).canReply)
        }
    }

    @Test fun `live code is offered, expired code is not`() {
        val fresh = NotificationPolicy.plan(MessageLabel.OTP_2FA, "Your code is 482913", t, t + 60_000, false)
        assertEquals("482913", fresh.code)
        assertEquals(MessageChannel.CODES, fresh.channel)
        val stale = NotificationPolicy.plan(MessageLabel.OTP_2FA, "Your code is 482913", t, t + 11 * 60_000, false)
        assertNull(stale.code)
    }

    @Test fun `an unsorted message that is clearly a code still gets the codes treatment`() {
        val p = NotificationPolicy.plan(MessageLabel.UNLABELED, "Your verification code is 482913", t, t, false)
        assertEquals(MessageChannel.CODES, p.channel); assertEquals("482913", p.code)
    }

    @Test fun `plain numbers in personal texts never become copy actions`() {
        assertNull(NotificationPolicy.plan(MessageLabel.PERSONAL, "my code is 482913", t, t, false).code)
    }
}
