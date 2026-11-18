package com.inboxiq.app.sms

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.google.android.mms.pdu_alt.NotifyRespInd
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The download hand-off a default SMS app must do itself: the platform writes the raw PDU to our file; we parse it
 * (PduParser), store it (PduPersister) as provider rows with parts, drop the notification row, and acknowledge.
 * The real library parses and persists here; only the system providers are faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MmsIngestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMmsStore
    private val acks = ArrayList<ByteArray>()
    private val responder = MmsIngest.NotifyResponder { _, pdu -> acks += pdu }

    @Before
    fun setUp() {
        store = FakeMmsStore(context).also { it.register() }
        store.notificationRows[42L] = ContentValues().apply {
            put(Telephony.Mms.SUBSCRIPTION_ID, 3)
            put(Telephony.Mms.TRANSACTION_ID, "TX-42")
            put(Telephony.Mms.DATE, 1_700_000_555L)
        }
    }

    private fun fileWith(bytes: ByteArray) = MmsDownloadFiles.fileFor(context, "dl_42_1_test.pdu").also { it.writeBytes(bytes) }

    @Test
    fun `a downloaded picture message becomes a provider row with an image part`() {
        val result = MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf()), responder)

        assertTrue(result.toString(), result is MmsIngest.Result.Persisted)
        // The message row: stored in the inbox as a retrieve-conf, on the thread the lookup gave.
        val msg = store.messageInserts().single().values
        assertEquals(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF, msg.getAsInteger(Telephony.Mms.MESSAGE_TYPE))
        assertEquals(77L, msg.getAsLong(Telephony.Mms.THREAD_ID))
        // The image part: type, name, and the exact bytes written to its file.
        val part = store.partInserts().single { it.values.getAsString("ct") == "image/jpeg" }
        assertEquals("pic.jpg", part.values.getAsString("cl"))
        val written = store.partData.entries.single { it.key.endsWith("/${part.id}") }.value.toByteArray()
        assertArrayEquals(MmsTestPdus.JPEG, written)
        // The sender is stored as an address row of the message.
        assertTrue(store.addrInserts().any { it.values.getAsString("address") == "+15551230000" && it.values.getAsInteger("type") == 137 })
    }

    @Test
    fun `the new row is unread and dated like the notification, then the notification row is deleted`() {
        val result = MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf()), responder) as MmsIngest.Result.Persisted

        val update = store.updates.single { it.first == result.uri }.second
        assertEquals(0, update.getAsInteger(Telephony.Mms.READ))
        assertEquals(0, update.getAsInteger(Telephony.Mms.SEEN))
        assertEquals(3, update.getAsInteger(Telephony.Mms.SUBSCRIPTION_ID))
        assertEquals(1_700_000_555L, update.getAsLong(Telephony.Mms.DATE))
        // (The persister also clears its own scratch part rows; the notification row is the message row deleted.)
        assertEquals(listOf("content://mms/42"), store.deletes.map { it.toString() }.filter { !it.endsWith("/part") })
    }

    @Test
    fun `the carrier is told the message was retrieved`() {
        MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf()), responder)

        val resp = PduParser(acks.single(), true).parse() as NotifyRespInd
        assertEquals(PduHeaders.STATUS_RETRIEVED, resp.status)
        assertEquals("TX-42", String(resp.transactionId))
    }

    @Test
    fun `a text part is kept with the image`() {
        MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf(text = "look at this")), responder)

        // The persister stores a text part's text by updating the part row it just inserted.
        val text = store.partInserts().single { it.values.getAsString("ct") == "text/plain" }
        val textUpdate = store.updates.single { it.first.toString() == "content://mms/part/${text.id}" }.second
        assertEquals("look at this", textUpdate.getAsString("text"))
        assertNotNull(store.partInserts().singleOrNull { it.values.getAsString("ct") == "image/jpeg" })
    }

    @Test
    fun `a missing or empty download is reported empty and nothing is stored or deleted`() {
        assertEquals(MmsIngest.Result.EmptyDownload, MmsIngest.ingest(context, 42L, fileWith(ByteArray(0)), responder))
        assertEquals(MmsIngest.Result.EmptyDownload, MmsIngest.ingest(context, 42L, MmsDownloadFiles.fileFor(context, "does_not_exist.pdu"), responder))
        assertTrue(store.inserts.isEmpty())
        assertTrue(store.deletes.isEmpty())
        assertTrue(acks.isEmpty())
    }

    @Test
    fun `bytes that are not a retrieve-conf are a parse failure and nothing is stored`() {
        val garbage = ByteArray(64) { (it * 7).toByte() }
        val r = MmsIngest.ingest(context, 42L, fileWith(garbage), responder)
        assertTrue(r is MmsIngest.Result.ParseFailed)

        // A well-formed PDU of another type (a notify response) is not a message either.
        val notifyResp = PduComposer(context, NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, "T".toByteArray(), PduHeaders.STATUS_RETRIEVED)).make()
        val r2 = MmsIngest.ingest(context, 42L, fileWith(notifyResp), responder) as MmsIngest.Result.ParseFailed
        assertEquals(PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND, r2.pduType)

        assertTrue(store.inserts.isEmpty())
        assertTrue(store.deletes.isEmpty())
    }

    @Test
    fun `a provider failure is a persist failure and the notification row is kept for the retry`() {
        store.failInserts = true
        val r = MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf()), responder)

        assertTrue(r is MmsIngest.Result.PersistFailed)
        assertTrue("content://mms/42" !in store.deletes.map { it.toString() })
        assertTrue(acks.isEmpty())
    }

    @Test
    fun `without a transaction id no acknowledgement is attempted`() {
        store.notificationRows[42L]!!.remove(Telephony.Mms.TRANSACTION_ID)
        val r = MmsIngest.ingest(context, 42L, fileWith(MmsTestPdus.retrieveConf()), responder)
        assertTrue(r is MmsIngest.Result.Persisted)
        assertTrue(acks.isEmpty())
    }
}
