package com.inboxiq.app.sms

import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.SendReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for tonight's actual bug: MmsReceiver used to assume the platform
 * auto-inserts a notification-indication row into content://mms just from a WAP push
 * arriving. Confirmed live it doesn't — nothing was ever in that table for any incoming MMS,
 * because nothing was parsing the WAP push and writing the row in the first place.
 * `contentLocationOf` is the decision at the center of the fix: given whatever PduParser
 * handed back, is there actually a content-location to act on? Tested directly against
 * hand-built PDU objects — no wire encoding, no Robolectric, no Android framework at all,
 * since that's the third-party library's own concern, not this app's.
 */
class MmsReceiverTest {

    @Test
    fun `extracts content-location from a real notification indication`() {
        val notification = NotificationInd().apply {
            contentLocation = "http://mmsc.example.com/path?id=abc123".toByteArray()
        }

        assertEquals("http://mmsc.example.com/path?id=abc123", contentLocationOf(notification))
    }

    @Test
    fun `returns null for a send-request — never mistakes our own outgoing pdu for an incoming notification`() {
        val sendReq = SendReq().apply {
            from = EncodedStringValue("+18135551234")
        }

        assertNull(contentLocationOf(sendReq))
    }

    @Test
    fun `returns null for a completely unparsed pdu`() {
        assertNull(contentLocationOf(null))
    }

    @Test
    fun `returns null rather than an empty string when content-location is present but empty`() {
        // A NotificationInd with a zero-length content-location isn't a usable download target —
        // treating "" as truthy here would have MmsDownloadWorker try to download from an empty
        // URL instead of correctly leaving the row alone.
        val notification = NotificationInd().apply {
            contentLocation = ByteArray(0)
        }

        assertNull(contentLocationOf(notification))
    }
}
