package com.inboxiq.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelayRedactionTest {
    @Test
    fun `verification codes are masked before leaving the phone`() {
        val out = RelayRedaction.redact("Your verification code is 482913. Do not share it.")
        assertFalse(out.contains("482913"))
        assertEquals("Your verification code is ${RelayRedaction.MASK}. Do not share it.", out)
    }

    @Test
    fun `ordinary messages with numbers are untouched`() {
        val text = "Dinner at 7, table for 4. See you at 1234 Main St"
        assertEquals(text, RelayRedaction.redact(text))
    }

    @Test
    fun `OTP wording without a code is untouched`() {
        val text = "Please verify your email address later"
        assertEquals(text, RelayRedaction.redact(text))
    }
}
