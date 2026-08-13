package com.inboxiq.app.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageEntity

/**
 * Once the platform (as our default-handler role grants) auto-downloads an
 * incoming MMS, it lands in content://mms. We don't hand-parse WSP/PDU bytes
 * (that requires internal AOSP classes not in the public SDK) — instead we
 * read the already-decoded row + its parts back from the MMS provider, same
 * approach the historical SMS backfill takes for content://sms.
 */
object MmsSync {

    private data class Parts(val text: String, val imagePartUri: String?)

    suspend fun syncRecent(context: Context, sinceMillis: Long) {
        val db = AppDatabase.get(context)
        val dao = db.messageDao()
        val resolver = context.contentResolver

        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX)
        val selection = "${Telephony.Mms.DATE} >= ?"
        // MMS DATE is in seconds, not millis, unlike SMS.
        val args = arrayOf((sinceMillis / 1000).toString())

        resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, args, "${Telephony.Mms.DATE} DESC")
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                val boxIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val timestamp = cursor.getLong(dateIdx) * 1000
                    val isIncoming = cursor.getInt(boxIdx) == Telephony.Mms.MESSAGE_BOX_INBOX

                    val address = PhoneNumberNormalizer.normalize(context, senderAddress(resolver, id) ?: "unknown")
                    if (isIncoming && db.blockedNumberDao().isBlocked(address)) continue
                    val parts = messageParts(resolver, id)

                    dao.insert(
                        MessageEntity(
                            threadId = 0,
                            address = address,
                            body = parts.text,
                            timestamp = timestamp,
                            isIncoming = isIncoming,
                            isRead = !isIncoming, // incoming MMS starts unread; our own sent MMS doesn't need the concept
                            imagePartUri = parts.imagePartUri,
                        ),
                    )
                }
            }
    }

    private fun senderAddress(resolver: android.content.ContentResolver, mmsId: Long): String? {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        resolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                // type 137 = PDU_HEADERS_FROM in the MMS spec
                val type = cursor.getInt(cursor.getColumnIndexOrThrow("type"))
                if (type == 137) {
                    return cursor.getString(cursor.getColumnIndexOrThrow("address"))
                }
            }
        }
        return null
    }

    private fun messageParts(resolver: android.content.ContentResolver, mmsId: Long): Parts {
        val uri = Uri.parse("content://mms/$mmsId/part")
        val textParts = mutableListOf<String>()
        var imagePartUri: String? = null

        resolver.query(uri, arrayOf("_id", "ct", "text"), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow("_id")
            val ctIdx = cursor.getColumnIndexOrThrow("ct")
            val textIdx = cursor.getColumnIndexOrThrow("text")
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(ctIdx)
                when {
                    contentType == "text/plain" -> cursor.getString(textIdx)?.let { textParts.add(it) }
                    contentType?.startsWith("image/") == true && imagePartUri == null -> {
                        // Only keep the first image part per message for v1 — most MMS have one.
                        imagePartUri = "content://mms/part/${cursor.getLong(idIdx)}"
                    }
                }
            }
        }

        val text = textParts.joinToString("\n").ifBlank {
            if (imagePartUri != null) "" else "[MMS message]"
        }
        return Parts(text, imagePartUri)
    }
}
