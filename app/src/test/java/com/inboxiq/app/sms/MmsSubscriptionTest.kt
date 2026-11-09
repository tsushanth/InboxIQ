package com.inboxiq.app.sms

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsSubscriptionTest {
    @Test fun `the SIM the push arrived on is used when the platform tags it`() {
        assertEquals(3, MmsReceiver.subscriptionOf(Intent().putExtra("subscription", 3)))
        assertEquals(0, MmsReceiver.subscriptionOf(Intent().putExtra("subscription", 0)))
    }

    @Test fun `an untagged push falls back to the default SMS SIM and never reports an invalid id as a SIM`() {
        val untagged = MmsReceiver.subscriptionOf(Intent())
        val negative = MmsReceiver.subscriptionOf(Intent().putExtra("subscription", -1))
        assertEquals(untagged, negative)
    }
}
