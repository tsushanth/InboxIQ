package com.inboxiq.app.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpExtractorTest {
    private val t = 1_000_000L
    private fun code(body: String) = OtpExtractor.extract(body, t)?.code

    @Test fun `common formats`() {
        assertEquals("482913", code("Your Example Bank verification code is 482913. Do not share it."))
        assertEquals("739201", code("739201 is your Acme login code"))
        assertEquals("8841", code("Use code 8841 to sign in"))
        assertEquals("123456", code("G-123456 is your Google verification code")) // letter prefix ignored, digits kept
        assertEquals("557291", code("Your security code: 557-291"))
        assertEquals("90417263", code("OTP: 90417263 valid for 5 minutes"))
    }

    @Test fun `numbers that are not codes are ignored`() {
        assertNull(code("Your order #482913 shipped. Track it soon"))
        assertNull(code("Call us at 555-0100 or visit in 2024"))
        assertNull(code("Lunch at 12:30? Bring 20 dollars"))
        assertNull(code("Your balance is $1234 after the code review")) // currency, keyword alone not enough
        assertNull(code("See you in 2025, verify nothing"))
    }

    @Test fun `no keyword no code`() {
        assertNull(code("Gate is 4417 tonight"))
    }

    @Test fun `prefers the number next to the keyword`() {
        assertEquals("620418", code("Order 11223344 total. Your code is 620418"))
    }

    @Test fun `default validity is ten minutes and stated validity wins`() {
        assertEquals(t + 10 * 60_000, OtpExtractor.extract("Your code is 482913", t)!!.expiresAt)
        assertEquals(t + 5 * 60_000, OtpExtractor.extract("Code 482913 expires in 5 minutes", t)!!.expiresAt)
        assertEquals(t + 90_000, OtpExtractor.extract("Code 482913 valid for 90 seconds", t)!!.expiresAt)
        assertEquals(t + 2 * 3_600_000, OtpExtractor.extract("Code 482913 is good for 2 hours", t)!!.expiresAt)
    }

    @Test fun `expiry boundary`() {
        val otp = OtpExtractor.extract("Your code is 482913", t)!!
        assertFalse(otp.isExpired(t + 9 * 60_000))
        assertTrue(otp.isExpired(t + 10 * 60_000))
    }

    @Test fun `absurd validity is clamped`() {
        assertEquals(t + 24L * 3_600_000, OtpExtractor.extract("Code 482913 valid for 999 hours", t)!!.expiresAt)
    }
}
