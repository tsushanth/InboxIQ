package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryInfoTest {
    private fun out(status: SendStatus, heal: Boolean = false, retries: Int = 0) =
        MessageEntity(threadId = 0, address = "+1", body = "b", timestamp = 1, isIncoming = false, sendStatus = status, awaitingAutoHeal = heal, autoHealRetryCount = retries)

    @Test fun `sent never claims delivery`() {
        val d = DeliveryInfo.detail(out(SendStatus.SENT))
        assertTrue(d.contains("cannot tell you the other phone got it"))
        assertFalse(DeliveryInfo.short(out(SendStatus.SENT))!!.contains("Delivered", ignoreCase = true))
    }

    @Test fun `failure states explain the next step`() {
        assertTrue(DeliveryInfo.detail(out(SendStatus.FAILED, heal = true, retries = 2)).contains("2 of 5"))
        assertTrue(DeliveryInfo.detail(out(SendStatus.FAILED)).contains("Retry now"))
        assertTrue(DeliveryInfo.isFailure(out(SendStatus.FAILED)))
    }

    @Test fun `incoming has no send state`() {
        val m = MessageEntity(threadId = 0, address = "+1", body = "b", timestamp = 1, isIncoming = true)
        assertNull(DeliveryInfo.short(m))
    }
}
