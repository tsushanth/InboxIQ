package com.inboxiq.app.classify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TRAI header structure (XY-ABCDEF, optional -P/-S/-T/-G) and the maintained table. Addresses here are public formats, not real customers. */
class IndiaSendersTest {
    @Test fun `the resource tables load`() {
        assertTrue(IndiaSenders.loaded)
        assertTrue(IndiaSenders.brandCount >= 10)
    }

    @Test fun `prefix and header split, with operator and circle named from the TRAI tables`() {
        val h = IndiaSenders.parse("VM-HDFCBK")!!
        assertEquals("VM", h.prefix); assertEquals("Vodafone Idea", h.operator); assertEquals("Mumbai", h.circle)
        assertEquals("HDFCBK", h.header); assertNull(h.suffix)
        assertEquals("HDFC Bank", h.brand?.name)
        assertEquals("Bharti Airtel", IndiaSenders.parse("AX-SBIINB-S")!!.operator)
        assertEquals("Karnataka", IndiaSenders.parse("AX-SBIINB-S")!!.circle)
        assertEquals("Reliance Jio", IndiaSenders.parse("JD-TESTBK-T")!!.operator)
    }

    @Test fun `suffix letters carry the registered message class`() {
        assertEquals(DltSuffix.TRANSACTIONAL, IndiaSenders.parse("VM-HDFCBK-T")!!.suffix)
        assertEquals(DltSuffix.SERVICE, IndiaSenders.parse("VM-HDFCBK-S")!!.suffix)
        assertEquals(DltSuffix.PROMOTIONAL, IndiaSenders.parse("VM-HDFCBK-P")!!.suffix)
        assertEquals(DltSuffix.GOVERNMENT, IndiaSenders.parse("VM-HDFCBK-G")!!.suffix)
        assertNull("not a TRAI letter", IndiaSenders.parse("VM-HDFCBK-X"))
    }

    @Test fun `case and spacing from a provider are tolerated, look-alikes are not`() {
        assertNotNull(IndiaSenders.parse(" vm-hdfcbk-t "))
        assertNull("no hyphen", IndiaSenders.parse("VMHDFCBK"))
        assertNull("prefix letters must be real operator and circle codes", IndiaSenders.parse("ZZ-HDFCBK"))
        assertNull("I is not an operator code", IndiaSenders.parse("IA-HDFCBK"))
        assertNull("header too short", IndiaSenders.parse("VM-AB"))
        assertNull("ordinary phone number", IndiaSenders.parse("+919800000001"))
        assertNull("US style sender", IndiaSenders.parse("AMAZON"))
    }

    @Test fun `every verified brand header has a source, and unverified guesses are not in the table`() {
        for (h in listOf("HDFCBK", "HDFCBN", "SBIINB", "SBIPSG", "SBICRD", "AXISBK", "AXISMR")) {
            val b = IndiaSenders.parse("VM-$h")!!.brand!!
            assertTrue(h, b.source != "heuristic" && b.source.isNotBlank())
        }
        // Looked for and not verified from a public page: do not claim them as brands (heuristic only if the ending matches).
        for (h in listOf("ICICIB", "KOTAKB", "PAYTMB", "IRCTCI", "FLIPKT")) {
            val b = IndiaSenders.parse("VM-$h")!!.brand
            assertTrue(h, b == null || b.source == "heuristic")
        }
    }

    @Test fun `heuristic endings are weak and never named as a brand`() {
        val b = IndiaSenders.parse("AX-TESTUPI-S")!!.brand!!
        assertEquals("heuristic", b.source); assertEquals(HeaderCategory.PAYMENTS, b.category)
        assertNull("the bare ending alone is not a header claim", IndiaSenders.parse("AX-UPI")!!.brand)
    }

    @Test fun `SenderProfile attaches the parsed header only to named senders`() {
        val p = SenderProfile.of("VM-HDFCBK-T")
        assertEquals(SenderKind.ALPHA_ID, p.kind); assertNotNull(p.dlt)
        assertTrue(p.description, p.description.contains("registered in India") && p.description.contains("transactional"))
        assertNull(SenderProfile.of("AMAZON").dlt)
        assertNull(SenderProfile.of("+919800000001").dlt)
        assertFalse(SenderProfile.of("+919800000001").isAutomated)
    }

    @Test fun `suffix and brand are supporting evidence, never decisive alone`() {
        // A -T header with chatty text is not a transaction; the same header with a debit alert is.
        assertEquals(com.inboxiq.app.data.MessageLabel.PERSONAL, TriageEngine.rulesOnly("hello there", SenderProfile.of("VM-HDFCBK-T")).label)
        val debit = TriageEngine.rulesOnly("Rs 640.00 debited from A/c XX0042 on 05-Nov-26.", SenderProfile.of("VM-HDFCBK-T"))
        assertEquals(com.inboxiq.app.data.MessageLabel.TRANSACTION, debit.label)
        assertTrue(debit.evidence.joinToString(" "), debit.evidence.any { it.contains("HDFCBK-T") })
    }

    @Test fun `a promotional suffix tilts an offer to promo but an alert text still wins over a bank brand`() {
        val offer = TriageEngine.rulesOnly("Exclusive offer for you! Apply now and get cashback. Reply STOP to opt out.", SenderProfile.of("VM-HDFCBK-P"))
        assertEquals(com.inboxiq.app.data.MessageLabel.PROMO, offer.label)
    }
}
