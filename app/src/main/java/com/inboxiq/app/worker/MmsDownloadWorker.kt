package com.inboxiq.app.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.sms.MmsDownloadReceiver

/**
 * Explicitly triggers the actual MMS content download. Confirmed live that the OS does not
 * reliably auto-download MMS content just because this app holds the default-SMS-handler
 * role — a real incoming photo left the notification-indication row sitting undownloaded in
 * content://mms indefinitely. A default SMS app is responsible for calling
 * SmsManager.downloadMultimediaMessage() itself, using the content-location URL the carrier's
 * notification PDU provides — which the platform stores in the message row (column ct_l)
 * as soon as the WAP push notification arrives, well before any content is fetched.
 */
class MmsDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(Telephony.Mms._ID, Telephony.Mms.CONTENT_LOCATION, Telephony.Mms.MESSAGE_TYPE)
        val selection = "${Telephony.Mms.MESSAGE_TYPE} = ?"
        val args = arrayOf(NOTIFICATION_IND.toString())

        val pending = mutableListOf<Pair<Long, String>>()
        resolver.query(Telephony.Mms.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
            val locIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.CONTENT_LOCATION)
            while (cursor.moveToNext()) {
                val location = cursor.getString(locIdx) ?: continue
                pending.add(cursor.getLong(idIdx) to location)
            }
        }

        val smsManager = applicationContext.getSystemService(SmsManager::class.java)
        for ((id, location) in pending) {
            val msgUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            val downloadedIntent = Intent(applicationContext, MmsDownloadReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, id.toInt(), downloadedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                smsManager.downloadMultimediaMessage(applicationContext, location, msgUri, null, pendingIntent)
            } catch (e: Exception) {
                // Leave it for the next periodic pass — SyncMmsWorker's later read will just
                // find no content yet, same as if this row hadn't been noticed at all.
            }
        }
        return Result.success()
    }

    companion object {
        private const val NOTIFICATION_IND = 0x82 // Telephony.Mms.MESSAGE_TYPE_NOTIFICATION_IND
    }
}
