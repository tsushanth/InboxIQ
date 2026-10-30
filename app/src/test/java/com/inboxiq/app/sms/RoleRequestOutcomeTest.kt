package com.inboxiq.app.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RoleRequestOutcomeTest {
    @Test fun `holding the role is granted however fast it came back`() {
        assertEquals(RoleRequestOutcome.GRANTED, RoleRequestOutcome.classify(true, 0))
        assertEquals(RoleRequestOutcome.GRANTED, RoleRequestOutcome.classify(true, 30_000))
    }

    @Test fun `an instant cancel means the system suppressed the prompt`() {
        assertEquals(RoleRequestOutcome.SYSTEM_SUPPRESSED, RoleRequestOutcome.classify(false, 0))
        assertEquals(RoleRequestOutcome.SYSTEM_SUPPRESSED, RoleRequestOutcome.classify(false, RoleRequestOutcome.SUPPRESSED_BELOW_MS - 1))
    }

    @Test fun `a cancel after the user had time to read is a decline`() {
        assertEquals(RoleRequestOutcome.DECLINED, RoleRequestOutcome.classify(false, RoleRequestOutcome.SUPPRESSED_BELOW_MS))
        assertEquals(RoleRequestOutcome.DECLINED, RoleRequestOutcome.classify(false, 4_000))
    }

    @Test fun `granted says nothing, the other two offer a next step`() {
        assertNull(RoleRequestMessage.forOutcome(RoleRequestOutcome.GRANTED))
        assertEquals(RoleRequestMessage.Action.TRY_AGAIN, RoleRequestMessage.forOutcome(RoleRequestOutcome.DECLINED)!!.action)
        val s = RoleRequestMessage.forOutcome(RoleRequestOutcome.SYSTEM_SUPPRESSED)
        assertNotNull(s)
        assertEquals(RoleRequestMessage.Action.OPEN_SETTINGS, s!!.action)
    }

    @Test fun `decline copy reassures that nothing changed and never claims the app is broken`() {
        val t = RoleRequestMessage.forOutcome(RoleRequestOutcome.DECLINED)!!.text
        assert(t.contains("keeps working")) { t }
    }
}
