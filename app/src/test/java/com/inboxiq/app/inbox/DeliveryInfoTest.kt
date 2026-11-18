package com.inboxiq.app.inbox

import com.inboxiq.app.R
import com.inboxiq.app.data.MessageEntity
import com.inboxiq.app.data.SendStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class DeliveryInfoTest {
    private fun out(status: SendStatus, heal: Boolean = false, retries: Int = 0) =
        MessageEntity(threadId = 0, address = "+1", body = "b", timestamp = 1, isIncoming = false, sendStatus = status, awaitingAutoHeal = heal, autoHealRetryCount = retries)

    @Test fun `sent never claims delivery`() {
        assertEquals(R.string.delivery_detail_sent, DeliveryInfo.detailRes(out(SendStatus.SENT)))
        assertEquals(R.string.delivery_short_sent, DeliveryInfo.shortRes(out(SendStatus.SENT)))
    }

    @Test fun `failure states explain the next step`() {
        assertEquals(R.string.delivery_detail_retrying, DeliveryInfo.detailRes(out(SendStatus.FAILED, heal = true, retries = 2)))
        assertEquals(R.string.delivery_detail_failed, DeliveryInfo.detailRes(out(SendStatus.FAILED)))
        assertTrue(DeliveryInfo.isFailure(out(SendStatus.FAILED)))
    }

    @Test fun `the retry text names the tries used`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(DeliveryInfo.detail(context, out(SendStatus.FAILED, heal = true, retries = 2)).contains("2 of 5"))
        assertTrue(DeliveryInfo.detail(context, out(SendStatus.SENT)).contains("cannot tell you the other phone got it"))
        assertTrue(DeliveryInfo.detail(context, out(SendStatus.FAILED)).contains("Retry now"))
    }

    @Test fun `incoming has no send state`() {
        val m = MessageEntity(threadId = 0, address = "+1", body = "b", timestamp = 1, isIncoming = true)
        assertNull(DeliveryInfo.shortRes(m))
    }
}
