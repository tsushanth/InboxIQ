package com.inboxiq.app.sms

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony

/** Reads, from the system MMS provider, the incoming MMS that were announced but not yet downloaded. */
object MmsPending {
    /** A notification-indication row (`m_type` 130): the carrier said a message is waiting at [location]. */
    data class Row(val id: Long, val location: String?, val dateSeconds: Long)

    /** Telephony.Mms.MESSAGE_TYPE_NOTIFICATION_IND (0x82 = 130). */
    const val NOTIFICATION_IND = 0x82

    fun query(resolver: ContentResolver): List<Row> {
        val out = ArrayList<Row>()
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.MESSAGE_TYPE, Telephony.Mms.DATE)
        val selection = "${Telephony.Mms.MESSAGE_TYPE} = ?"
        val cursor = resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, arrayOf(NOTIFICATION_IND.toString()), null)
            ?: throw ProviderUnavailableException("mms provider returned no cursor")
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(Telephony.Mms._ID)
            val locIdx = it.getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION)
            val dateIdx = it.getColumnIndex(Telephony.Mms.DATE)
            while (it.moveToNext()) {
                val date = if (dateIdx >= 0 && !it.isNull(dateIdx)) it.getLong(dateIdx) else 0L
                out += Row(it.getLong(idIdx), it.getString(locIdx), date)
            }
        }
        return out
    }

    /**
     * The id of the still-undownloaded MMS that [timestampMillis] (a stored placeholder message's time) belongs to,
     * or null when there is none. The placeholder takes its time from this very row's `date`, in whole seconds.
     */
    fun idForTimestamp(resolver: ContentResolver, timestampMillis: Long): Long? =
        query(resolver).firstOrNull { it.dateSeconds != 0L && it.dateSeconds == timestampMillis / 1000 }?.id

    /** True when provider row [id] now has an image part. */
    fun hasImage(resolver: ContentResolver, id: Long): Boolean {
        resolver.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct"), "mid = ?", arrayOf(id.toString()), null)?.use { c ->
            val ctIdx = c.getColumnIndex("ct")
            if (ctIdx < 0) return false
            while (c.moveToNext()) if (c.getString(ctIdx)?.startsWith("image/") == true) return true
        }
        return false
    }
}
