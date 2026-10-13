package com.inboxiq.app.sms

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.Telephony

/**
 * Thin, throwing wrapper over the shared SMS provider (content://sms). Only the current default SMS
 * app may write to it; every other caller gets a SecurityException, which callers treat as "not
 * writable right now" (see [ProviderWrites]). Never logs addresses or bodies.
 *
 * Nothing here deletes provider rows: deletion stays a separate, explicit owner decision.
 */
class ProviderSms(private val resolver: ContentResolver) {

    /** A provider row as the provider itself reports it; the import computes its key from exactly these fields. */
    data class Row(val uri: Uri, val id: Long, val threadId: Long, val address: String, val body: String, val date: Long, val type: Int)

    /** Writes a received text to the inbox. [date] is the PDU timestamp, the same value our DB stores. */
    fun insertInbox(
        address: String,
        body: String,
        date: Long,
        dateSent: Long,
        read: Boolean,
        serviceCenter: String?,
        subscriptionId: Int,
    ): Row = insert(Telephony.Sms.Inbox.CONTENT_URI, address, body, date, dateSent, read, serviceCenter, subscriptionId, errorCode = null)

    /** Writes a text we sent (or are sending) with the matching box type: outbox, sent or failed. */
    fun insertOutgoing(address: String, body: String, date: Long, type: Int, errorCode: Int? = null): Row {
        val uri = when (type) {
            Telephony.Sms.MESSAGE_TYPE_SENT -> Telephony.Sms.Sent.CONTENT_URI
            Telephony.Sms.MESSAGE_TYPE_FAILED -> Telephony.Sms.Outbox.CONTENT_URI
            else -> Telephony.Sms.Outbox.CONTENT_URI
        }
        val row = insert(uri, address, body, date, dateSent = 0, read = true, serviceCenter = null, subscriptionId = -1, errorCode = errorCode)
        // The provider derives type from the box URI; failed has no box of its own, so set it explicitly.
        return if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) { setType(row.uri, type, errorCode); row.copy(type = type) } else row
    }

    /** Moves an existing row to [type] (sent / failed / outbox). Returns false when the row no longer exists. */
    fun setType(uri: Uri, type: Int, errorCode: Int? = null): Boolean {
        val values = ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            if (errorCode != null) put(Telephony.Sms.ERROR_CODE, errorCode)
        }
        return resolver.update(uri, values, null, null) > 0
    }

    /** Rows with this exact date and body (address compared by the caller after normalisation). */
    fun findByDateAndBody(date: Long, body: String): List<Row> {
        val out = ArrayList<Row>(2)
        resolver.query(Telephony.Sms.CONTENT_URI, PROJECTION, "${Telephony.Sms.DATE} = ? AND ${Telephony.Sms.BODY} = ?", arrayOf(date.toString(), body), null)
            ?.use { while (it.moveToNext()) out += read(it) }
        return out
    }

    /** User opened / marked a conversation: mirror it. Only unread rows are touched. */
    fun markThreadsRead(threadIds: Collection<Long>) {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        for (t in threadIds) {
            resolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(t.toString()))
            // Imported MMS carry their provider thread id too; SMS and MMS share one thread id space.
            resolver.update(Telephony.Mms.CONTENT_URI, values, "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0", arrayOf(t.toString()))
        }
    }

    fun markAllRead() {
        val values = ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) }
        resolver.update(Telephony.Sms.CONTENT_URI, values, "${Telephony.Sms.READ} = 0", null)
        resolver.update(Telephony.Mms.CONTENT_URI, values, "${Telephony.Mms.READ} = 0", null)
    }

    private fun insert(
        box: Uri,
        address: String,
        body: String,
        date: Long,
        dateSent: Long,
        read: Boolean,
        serviceCenter: String?,
        subscriptionId: Int,
        errorCode: Int?,
    ): Row {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            if (dateSent > 0) put(Telephony.Sms.DATE_SENT, dateSent)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            if (serviceCenter != null) put(Telephony.Sms.SERVICE_CENTER, serviceCenter)
            if (subscriptionId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            if (errorCode != null) put(Telephony.Sms.ERROR_CODE, errorCode)
        }
        val uri = resolver.insert(box, values) ?: throw ProviderUnavailableException("sms provider refused insert")
        val id = uri.lastPathSegment?.toLongOrNull() ?: throw ProviderUnavailableException("sms provider returned no row id")
        // Read the row back so callers key on what the provider actually stored (the import will read the same fields).
        val stored = runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { if (it.moveToFirst()) read(it) else null }
        }.getOrNull()
        return stored ?: Row(uri, id, 0, address, body, date, Telephony.Sms.MESSAGE_TYPE_INBOX)
    }

    private fun read(c: android.database.Cursor) = Row(
        uri = Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, c.getLong(0).toString()),
        id = c.getLong(0),
        threadId = c.getLong(1),
        address = c.getString(2) ?: "unknown",
        body = c.getString(3) ?: "",
        date = c.getLong(4),
        type = c.getInt(5),
    )

    companion object {
        private val PROJECTION = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
        )
    }
}
