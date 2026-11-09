package com.inboxiq.app.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

class RelayRedactionSearchTest {
    @Test
    fun `grouped codes are masked too`() {
        val out = RelayRedaction.redact("Your code is 482 913, do not share")
        assertFalse(out.contains("482"))
        assertFalse(out.contains("913"))
        assertEquals("Your code is ${RelayRedaction.MASK}, do not share", RelayRedaction.redact("Your code is 482 913, do not share"))
        assertFalse(RelayRedaction.redact("Your verification code: 482-913").contains("482"))
    }

    @Test
    fun `a search cannot confirm a hidden code`() {
        val body = "Your verification code is 482913. Do not share it."
        assertFalse(RelayRedaction.matchesVisible(body, "482913"))
        assertFalse(RelayRedaction.matchesVisible(body, "4829"))
        assertFalse(RelayRedaction.matchesVisible(body, "is 48"))
        assertTrue(RelayRedaction.matchesVisible(body, "verification code"))
        assertTrue(RelayRedaction.matchesVisible(body, "DO NOT SHARE"))
    }

    @Test
    fun `ordinary numbers stay searchable`() {
        assertTrue(RelayRedaction.matchesVisible("Table for 4 at 7:30", "7:30"))
        assertTrue(RelayRedaction.matchesVisible("Order 123456 has shipped", "123456")) // not an OTP message
    }
}
