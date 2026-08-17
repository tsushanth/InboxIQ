package com.inboxiq.app.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
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
class MmsDownloadWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    // Overridable only from tests (see MmsDownloadWorkerTest) — WorkManager's real factory
    // always uses the default, which is the actual SmsManager call. Splitting this out is
    // what makes it possible to verify "did we ask to download the right id/location" without
    // a real device, since Robolectric's SmsManager shadow doesn't track calls meaningfully.
    private val downloadTrigger: (Context, String, Uri, PendingIntent) -> Unit = ::triggerRealDownload,
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
        Log.i(TAG, "found ${pending.size} pending notification-indication row(s): ${pending.map { it.first }}")

        for ((id, location) in pending) {
            val msgUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            val downloadedIntent = Intent(applicationContext, MmsDownloadReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, id.toInt(), downloadedIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                Log.i(TAG, "downloading mms id=$id location=$location")
                downloadTrigger(applicationContext, location, msgUri, pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "downloadMultimediaMessage failed for id=$id", e)
                // Leave it for the next periodic pass — SyncMmsWorker's later read will just
                // find no content yet, same as if this row hadn't been noticed at all.
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MmsDownloadWorker"
        private const val NOTIFICATION_IND = 0x82 // Telephony.Mms.MESSAGE_TYPE_NOTIFICATION_IND

        private fun triggerRealDownload(context: Context, location: String, msgUri: Uri, pendingIntent: PendingIntent) {
            // getSystemService(SmsManager::class.java) only reliably resolves on API 31+ (can
            // return null below that, silently no-op-ing every download); getDefault() works
            // on every version this app supports (minSdk 26).
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (smsManager == null) {
                Log.e(TAG, "SmsManager unavailable — cannot download from $location")
                return
            }
            smsManager.downloadMultimediaMessage(context, location, msgUri, null, pendingIntent)
        }
    }
}
