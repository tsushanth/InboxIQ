package com.inboxiq.app.classify

import com.inboxiq.app.classify.LinkFeatures.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderAndLinkTest {
    private fun kind(a: String?) = SenderProfile.of(a).kind

    @Test fun `sender kinds`() {
        assertEquals(SenderKind.SHORT_CODE, kind("22000"))
        assertEquals(SenderKind.SHORT_CODE, kind("32665"))
        assertEquals(SenderKind.ALPHA_ID, kind("ACMEBANK"))
        assertEquals(SenderKind.ALPHA_ID, kind("1-800-FLOWERS"))
        assertEquals(SenderKind.TOLL_FREE, kind("+18005550100"))
        assertEquals(SenderKind.TOLL_FREE, kind("8885550100"))
        assertEquals(SenderKind.PHONE, kind("+14155550142"))
        assertEquals(SenderKind.PHONE, kind("(415) 555-0142"))
        assertEquals(SenderKind.PHONE, kind("14155550142"))
        assertEquals(SenderKind.INTL_PHONE, kind("+447700900123"))
        assertEquals(SenderKind.EMAIL_GATEWAY, kind("5550142@example.com"))
        assertEquals(SenderKind.UNKNOWN, kind(""))
        assertEquals(SenderKind.UNKNOWN, kind(null))
    }

    @Test fun `automated and person-like`() {
        assertTrue(SenderProfile.of("22000").isAutomated && SenderProfile.of("ACME").isAutomated && SenderProfile.of("+18005550100").isAutomated)
        assertFalse(SenderProfile.of("+14155550142").isAutomated)
        assertTrue(SenderProfile.of("+14155550142").isPersonLike && SenderProfile.of("+447700900123").isPersonLike)
        assertFalse(SenderProfile.of("5550142@example.com").isPersonLike)
    }

    private fun kinds(url: String) = LinkFeatures.extract(url).flatMap { it.kinds }.toSet()

    @Test fun `link kinds`() {
        assertTrue(Kind.SHORTENER in kinds("see bit.ly/3xK9fPq now"))
        assertTrue(Kind.SHORTENER in kinds("rb.gy/SL2dWW3"))
        assertTrue(Kind.SHORTENER in kinds("shorturl.at/JCnQkGG"))
        assertTrue(Kind.IP_HOST in kinds("go to http://193.46.12.8/confirm"))
        assertTrue(Kind.ODD_TLD in kinds("pay at tollpay-ny.cc"))
        assertTrue(Kind.LOOKALIKE in kinds("https://usps-redelivery-help.example-svc.top/x"))
        assertTrue(Kind.BRAND_IMPERSONATION in kinds("https://paypal-resolution.vip/login"))
        assertTrue(Kind.PUNYCODE in kinds("https://xn--pypal-4ve.com/login"))
        assertTrue(Kind.PLAIN_HTTP in kinds("http://shop.example.com/a"))
    }

    @Test fun `ordinary links and non-links are not flagged`() {
        assertEquals(emptySet<Kind>(), kinds("https://www.paypal.com/myaccount"))
        assertEquals(emptySet<Kind>(), kinds("https://acme.example.com/a/xyz"))
        assertEquals(emptySet<Kind>(), kinds("https://usps.com/track"))
        assertTrue(LinkFeatures.extract("see you at 5 p.m. e.g. tomorrow").isEmpty())
        assertTrue(LinkFeatures.extract("meet at 7.30pm").isEmpty())
        assertTrue(LinkFeatures.extract("Mr.Smith said ok").isEmpty())
    }

    @Test fun `bare reply list matches its sql twin`() {
        assertTrue(BareReplies.isBare(" stop ") && BareReplies.isBare("Y") && BareReplies.isBare("1"))
        assertFalse(BareReplies.isBare("who is this?") || BareReplies.isBare("stop texting me"))
        assertEquals(BareReplies.SQL_LIST.split(',').size, 16)
    }
}
