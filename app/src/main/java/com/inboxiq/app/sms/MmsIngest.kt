package com.inboxiq.app.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import com.google.android.mms.pdu_alt.NotifyRespInd
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import java.io.File

/**
 * Turns the raw PDU that SmsManager.downloadMultimediaMessage wrote to our scratch file into a real message in
 * the system MMS store. The platform does not do this for the default SMS app: it only writes the bytes.
 *
 * Reads the file, parses it as an m-retrieve-conf, persists it into content://mms/inbox (headers, addresses,
 * parts, thread), points the new row's time at the notification's so the stored placeholder is recognised as the
 * same message, deletes the notification-indication row, and tells the carrier the message was retrieved.
 * Only ids, counts and result names are logged; never an address or message content.
 */
object MmsIngest {
    private const val TAG = "MmsIngest"

    sealed interface Result {
        /** The message is stored at [uri] and the notification row is gone. */
        data class Persisted(val uri: Uri) : Result
        /** No bytes arrived. Transient. */
        data object EmptyDownload : Result
        /** The bytes are not an m-retrieve-conf. Permanent. */
        data class ParseFailed(val pduType: Int?) : Result
        /** Writing to the MMS store failed. Transient. */
        data class PersistFailed(val error: String) : Result
    }

    /** What the notification-indication row told us (read before it is deleted). */
    internal data class Notification(val subId: Int?, val transactionId: String?, val dateSeconds: Long?)

    /** Sends the NotifyRespInd bytes to the carrier. Best effort; the default writes them to a file for SmsManager. */
    fun interface NotifyResponder {
        fun send(context: Context, pdu: ByteArray)
    }

    val REAL_RESPONDER = NotifyResponder { context, pdu ->
        val name = "nr_${System.nanoTime()}.pdu"
        val file = MmsDownloadFiles.fileFor(context, name)
        file.writeBytes(pdu)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        MmsDownloadFiles.grantToMmsService(context, uri)
        // No location url: the platform posts it to the MMSC of the APN, which is where a notify response belongs.
        defaultSmsManager(context).sendMultimediaMessage(context, uri, null, null, null)
    }

    private fun defaultSmsManager(context: Context): SmsManager =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    fun ingest(
        context: Context,
        id: Long,
        file: File,
        responder: NotifyResponder = REAL_RESPONDER,
    ): Result {
        val bytes = try {
            if (file.exists()) file.readBytes() else ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
        if (bytes.isEmpty()) {
            Log.w(TAG, "mms id=$id download file empty")
            return Result.EmptyDownload
        }

        val pdu = try {
            PduParser(bytes, true).parse()
        } catch (e: Throwable) {
            // The parser asserts on malformed input (an Error, not an Exception): garbage from the network must not crash us.
            if (e is VirtualMachineError) throw e
            Log.w(TAG, "mms id=$id parse threw ${e.javaClass.simpleName}")
            null
        }
        if (pdu !is RetrieveConf) {
            Log.w(TAG, "mms id=$id downloaded ${bytes.size} bytes that are not a retrieve-conf (type=${pdu?.messageType})")
            return Result.ParseFailed(pdu?.messageType)
        }

        val resolver = context.contentResolver
        val note = notificationOf(context, id)
        val subId = note.subId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val stored = try {
            PduPersister.getPduPersister(context).persist(pdu, Telephony.Mms.Inbox.CONTENT_URI, true, false, null, subId)
        } catch (e: Exception) {
            val where = describe(e)
            Log.e(TAG, "mms id=$id persist failed: $where")
            return Result.PersistFailed(where)
        }
        if (stored == null) {
            Log.e(TAG, "mms id=$id persist returned no row")
            // i18n-ok: a log detail, never shown to the user
            return Result.PersistFailed("null uri")
        }

        // A new incoming message: unread, unseen, and dated like the notification so the placeholder that
        // was stored for it is recognised as the same message (the platform does the same after a download).
        try {
            val values = ContentValues().apply {
                put(Telephony.Mms.READ, 0)
                put(Telephony.Mms.SEEN, 0)
                put(Telephony.Mms.SUBSCRIPTION_ID, subId)
                note.dateSeconds?.let { put(Telephony.Mms.DATE, it) }
            }
            resolver.update(stored, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "mms id=$id could not update the new row: ${e.javaClass.simpleName}")
        }

        try {
            resolver.delete(Uri.parse("content://mms/$id"), null, null)
        } catch (e: Exception) {
            Log.w(TAG, "mms id=$id could not delete the notification row: ${e.javaClass.simpleName}")
        }

        notifyCarrier(context, id, note.transactionId, responder)
        Log.i(TAG, "mms id=$id stored as $stored (${bytes.size} bytes)")
        return Result.Persisted(stored)
    }

    /** Exception class and the first library frame: enough to find a bug, nothing about the message. */
    internal fun describe(e: Throwable): String {
        val frame = e.stackTrace.firstOrNull { it.className.startsWith("com.google.android.mms") } ?: e.stackTrace.firstOrNull()
        return e.javaClass.simpleName + (frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "")
    }

    private fun notifyCarrier(context: Context, id: Long, transactionId: String?, responder: NotifyResponder) {
        if (transactionId.isNullOrEmpty()) return
        try {
            val resp = NotifyRespInd(PduHeaders.CURRENT_MMS_VERSION, transactionId.toByteArray(), PduHeaders.STATUS_RETRIEVED)
            val pdu = PduComposer(context, resp).make() ?: return
            responder.send(context, pdu)
        } catch (e: Exception) {
            Log.w(TAG, "mms id=$id notify response failed: ${e.javaClass.simpleName}")
        }
    }

    internal fun notificationOf(context: Context, id: Long): Notification {
        return try {
            val projection = arrayOf(Telephony.Mms.SUBSCRIPTION_ID, Telephony.Mms.TRANSACTION_ID, Telephony.Mms.DATE)
            context.contentResolver.query(Uri.parse("content://mms/$id"), projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return Notification(null, null, null)
                val sub = c.getColumnIndex(Telephony.Mms.SUBSCRIPTION_ID).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getInt(it) }?.takeIf { it >= 0 }
                val tr = c.getColumnIndex(Telephony.Mms.TRANSACTION_ID).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getString(it) }
                val date = c.getColumnIndex(Telephony.Mms.DATE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }?.takeIf { it > 0 }
                Notification(sub, tr, date)
            } ?: Notification(null, null, null)
        } catch (e: Exception) {
            Notification(null, null, null)
        }
    }
}
