package com.inboxiq.app.sms

import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.SendReq
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies the actual wire round trip for PDU types this app really composes (outgoing
 * SendReq — see MmsSender). NotificationInd is deliberately NOT round-tripped here:
 * PduComposer (klinker's android-smsmms, already a dependency) has no makeNotificationInd —
 * confirmed via `javap -p`, only makeSendReqPdu/makeNotifyResp/makeAckInd/makeReadRecInd exist.
 * That's correct for a client app (only carriers ever compose a notification indication; a
 * phone only ever parses one), so composing one here would test a code path this app's own
 * production code never exercises. MmsReceiver's actual handling of a parsed NotificationInd
 * is covered directly in MmsReceiverTest, against hand-built objects instead of wire bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsPduRoundTripTest {

    @Test
    fun `a send-request pdu is never misidentified as a notification indication`() {
        // MmsReceiver branches on `pdu is NotificationInd` — if an outgoing SendReq (or any
        // other PDU type) ever parsed back into that same Kotlin type, MmsReceiver would try
        // to persist and download a message that was never a notification in the first place.
        val sendReq = SendReq().apply {
            transactionId = "T2".toByteArray()
            mmsVersion = PduHeaders.CURRENT_MMS_VERSION
            from = EncodedStringValue("+18135551234")
        }

        val bytes = PduComposer(RuntimeEnvironment.getApplication(), sendReq).make()
        val parsed = PduParser(bytes).parse()

        checkNotNull(parsed) { "PduParser returned null for a freshly composed SendReq" }
        assertFalse(
            "A SendReq parsed back as a NotificationInd — MmsReceiver's type check would misfire",
            parsed is NotificationInd,
        )
    }
}
