package com.inboxiq.app.inbox

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSafetyTest {
    private fun risk(url: String, label: MessageLabel = MessageLabel.PERSONAL) = LinkSafety.analyze(url, label).risk

    @Test fun `ordinary https link is not flagged`() {
        assertEquals(LinkRisk.NONE, risk("https://www.example.com/menu"))
        assertEquals(LinkRisk.NONE, risk("https://paypal.com/signin"))
        assertEquals(LinkRisk.NONE, risk("https://www.amazon.co.uk/orders"))
    }

    @Test fun `lookalike brand domain is danger`() {
        val a = LinkSafety.analyze("https://paypal-secure-login.xyz/verify", MessageLabel.PERSONAL)
        assertEquals(LinkRisk.DANGER, a.risk)
        assertTrue(a.reasons.any { it.contains("Paypal") })
        assertEquals(LinkRisk.DANGER, risk("usps-tracking.top/pay"))
    }

    @Test fun `ip address, userinfo and punycode are danger`() {
        assertEquals(LinkRisk.DANGER, risk("http://192.168.4.7/login"))
        assertEquals(LinkRisk.DANGER, risk("https://paypal.com@evil.example/x"))
        assertEquals(LinkRisk.DANGER, risk("https://xn--pypal-4ve.com/"))
    }

    @Test fun `shorteners and plain http are caution`() {
        assertEquals(LinkRisk.CAUTION, risk("https://bit.ly/3abc"))
        assertEquals(LinkRisk.CAUTION, risk("http://example.com/a"))
        assertEquals(LinkRisk.CAUTION, risk("https://deals.example.top/x"))
    }

    @Test fun `scam label forces danger and spam label forces caution`() {
        assertEquals(LinkRisk.DANGER, risk("https://example.com", MessageLabel.SCAM))
        assertEquals(LinkRisk.CAUTION, risk("https://example.com", MessageLabel.SPAM))
    }

    @Test fun `host is reported without scheme or path`() {
        assertEquals("evil.example", LinkSafety.analyze("https://user@evil.example:8080/p?q=1", MessageLabel.PERSONAL).host)
        assertEquals("example.com", LinkSafety.analyze("example.com/path", MessageLabel.PERSONAL).host)
    }

    @Test fun `registrable domain handles two-part suffixes`() {
        assertEquals("amazon.co.uk", LinkSafety.registrable("www.amazon.co.uk"))
        assertEquals("example.com", LinkSafety.registrable("a.b.example.com"))
    }
}
