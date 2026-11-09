package com.inboxiq.app.sms

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RespondViaMessageTest {
    private fun r(s: String?) = RespondViaMessage.recipients(s?.let(Uri::parse))

    @Test fun `single number`() = assertEquals(listOf("+15550100"), r("smsto:+15550100"))
    @Test fun `sms scheme`() = assertEquals(listOf("5550100"), r("sms:5550100"))
    @Test fun `several recipients become separate numbers`() = assertEquals(listOf("+15550100", "+15550101"), r("smsto:+15550100,+15550101"))
    @Test fun `semicolons and blanks and duplicates are cleaned`() = assertEquals(listOf("+15550100", "+15550101"), r("smsto:+15550100;;+15550101, ,+15550100"))
    @Test fun `a query part is not part of the number`() = assertEquals(listOf("+15550100"), r("sms:+15550100?body=hi"))
    @Test fun `no data or empty list sends nothing`() { assertEquals(emptyList<String>(), r(null)); assertEquals(emptyList<String>(), r("smsto:")) }
}
