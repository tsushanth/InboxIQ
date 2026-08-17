package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.inboxiq.app.worker.MmsDownloadWorker
import com.inboxiq.app.worker.SyncMmsWorker
import java.util.concurrent.TimeUnit

/**
 * Required receiver for default-SMS-handler eligibility (WAP_PUSH_DELIVER).
 *
 * Confirmed live that content://mms never gets a notification-indication row on its own —
 * the platform does NOT auto-parse/insert the incoming WAP push; that's the default app's job.
 * Real open-source SMS apps (QKSMS, klinker's android-smsmms, which this project already
 * depends on) parse the raw PDU bytes themselves and persist the resulting NotificationInd via
 * PduPersister — only then does content://mms have a row for MmsDownloadWorker to find and for
 * SmsManager.downloadMultimediaMessage's destination Uri to target.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pushData = intent.getByteArrayExtra("data")
        if (pushData == null) {
            Log.w(TAG, "WAP_PUSH_DELIVER with no 'data' extra")
        } else {
            try {
                val pdu = PduParser(pushData).parse()
                val location = contentLocationOf(pdu)
                if (location != null) {
                    val persister = PduPersister.getPduPersister(context)
                    val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
                    val uri = persister.persist(
                        pdu as NotificationInd,
                        Uri.parse("content://mms/inbox"),
                        true,
                        false,
                        null,
                        subId,
                    )
                    Log.i(TAG, "persisted notification-indication row at $uri, location=$location")
                } else {
                    Log.i(TAG, "WAP push PDU was not a NotificationInd (type=${pdu?.messageType})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to parse/persist WAP push PDU", e)
            }
        }

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<MmsDownloadWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build(),
        )
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build(),
        )
    }

    companion object {
        private const val TAG = "MmsReceiver"
    }
}

/**
 * The one decision this whole flow hinges on: is this PDU actually a notification indication
 * carrying a real content-location to download from? Split out from onReceive so it's testable
 * as plain JVM logic — no Android framework, no WAP-byte encoding, no Robolectric — against
 * hand-built PDU objects, independent of whether the third-party parser/composer round-trips
 * correctly (a real, separate concern: see MmsPduRoundTripTest).
 */
internal fun contentLocationOf(pdu: GenericPdu?): String? =
    (pdu as? NotificationInd)?.contentLocation?.takeIf { it.isNotEmpty() }?.let { String(it) }
