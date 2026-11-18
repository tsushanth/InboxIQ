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
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.sms.MmsDownloadFiles
import com.inboxiq.app.sms.MmsDownloadPolicy
import com.inboxiq.app.sms.MmsDownloadReceiver
import com.inboxiq.app.sms.MmsDownloadReport
import com.inboxiq.app.sms.MmsDownloadScheduler
import com.inboxiq.app.sms.MmsDownloadStore
import com.inboxiq.app.sms.MmsPending
import com.inboxiq.app.sms.ProviderUnavailableException

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
    private val downloadTrigger: (Context, Long, String, Uri, PendingIntent) -> Unit = ::triggerRealDownload,
    private val clock: () -> Long = System::currentTimeMillis,
    private val report: (MmsDownloadReport.Event) -> Unit = MmsDownloadReport::log,
    private val isDefaultSmsApp: (Context) -> Boolean = DefaultSmsRole::isDefault,
    private val targets: MmsDownloadFiles.TargetFactory = MmsDownloadFiles.REAL,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Only the default SMS app downloads MMS; another default app owns these rows (the periodic pass runs always).
        if (!isDefaultSmsApp(applicationContext)) return Result.success()
        val store = MmsDownloadStore(applicationContext)
        MmsDownloadFiles.cleanStale(applicationContext, now = clock())
        val only = inputData.getLong(KEY_ID, -1L).takeIf { it >= 0 }
        val now = clock()

        val allPending = try {
            MmsPending.query(applicationContext.contentResolver)
        } catch (e: SecurityException) {
            return Result.success() // no SMS permission right now: nothing to do
        } catch (e: ProviderUnavailableException) {
            // No cursor is not "nothing pending": try again later rather than forget the attempt counts.
            return Result.retry()
        }
        MmsDownloadScheduler.reconcile(applicationContext.contentResolver, store, allPending.map { it.id }.toSet(), report)
        val pending = allPending.filter { it.location != null && (only == null || it.id == only) }
        Log.i(TAG, "found ${pending.size} pending notification-indication row(s): ${pending.map { it.id }}")

        var retry = false
        for (row in pending) {
            val id = row.id
            val location = row.location ?: continue
            val origin = MmsDownloadScheduler.originOf(row, now)
            val attempts = store.attempts(id)
            // A download the user asked for by tapping Retry (an id input) is always tried once, whatever the caps say.
            if (only == null && !MmsDownloadPolicy.mayRetry(attempts, store.firstSeenAt(id, origin), now)) {
                // Out of attempts or past the carrier's expiry: say so once, then leave the row alone.
                if (!store.gaveUpReported(id)) {
                    store.markGaveUpReported(id)
                    report(
                        MmsDownloadReport.Event(
                            MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, id,
                            MmsDownloadPolicy.codeName(store.entry(id)?.lastCode ?: MmsDownloadPolicy.CODE_TRIGGER_FAILED),
                            attempts, MmsPending.hasImage(applicationContext.contentResolver, id),
                        ),
                    )
                }
                continue
            }
            // Idempotent per row: one already being fetched, or still backing off after a failure, is not hit again.
            if (store.isInFlight(id, now)) {
                Log.i(TAG, "mms id=$id already downloading, skipping")
                continue
            }
            if (only == null && store.notBefore(id) > now) {
                Log.i(TAG, "mms id=$id backing off, skipping")
                continue
            }

            // The platform writes the raw downloaded PDU to this file; MmsDownloadReceiver parses and stores it.
            val target = try {
                targets.create(applicationContext, id, attempts + 1)
            } catch (e: Exception) {
                Log.e(TAG, "no download file for id=$id: ${e.javaClass.simpleName}")
                retry = true
                continue
            }
            val downloadedIntent = Intent(applicationContext, MmsDownloadReceiver::class.java)
                .putExtra(MmsDownloadReceiver.EXTRA_MMS_ID, id)
                .putExtra(MmsDownloadReceiver.EXTRA_FILE_NAME, target.name)
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext, id.toInt(), downloadedIntent,
                // Mutable (explicit intent, so safe) so the platform can add the carrier's HTTP status to the broadcast.
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            )
            val n = store.recordAttempt(id, now, origin)
            try {
                Log.i(TAG, "downloading mms id=$id attempt=$n")
                downloadTrigger(applicationContext, id, location, target.uri, pendingIntent)
            } catch (e: Exception) {
                Log.e(TAG, "downloadMultimediaMessage failed for id=$id attempt=$n: ${e.javaClass.simpleName}")
                store.recordResult(id, MmsDownloadPolicy.CODE_TRIGGER_FAILED)
                target.file.delete()
                // Ask WorkManager for another go with its exponential backoff (the request sets it); the
                // attempt cap above ends it, so a permanently failing trigger cannot loop forever.
                if (MmsDownloadPolicy.mayRetry(n, store.firstSeenAt(id, origin), clock())) retry = true
            }
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "MmsDownloadWorker"

        /** Optional input: download only this provider row (a retry scheduled for one id). */
        const val KEY_ID = "mms_id"

        private fun triggerRealDownload(context: Context, id: Long, location: String, destination: Uri, pendingIntent: PendingIntent) {
            val msgUri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)
            // getSystemService(SmsManager::class.java) only reliably resolves on API 31+ (can
            // return null below that, silently no-op-ing every download); getDefault() works
            // on every version this app supports (minSdk 26).
            // Download over the SIM the message arrived on: on a dual-SIM phone the default SMS SIM's data
            // connection can be the wrong one, and the carrier's download link then expires unused.
            val subId = subscriptionOf(context, msgUri)
            val smsManager = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else if (subId != null) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            if (smsManager == null) {
                throw IllegalStateException("SmsManager unavailable, cannot download")
            }
            MmsDownloadFiles.grantToMmsService(context, destination)
            smsManager.downloadMultimediaMessage(context, location, destination, null, pendingIntent)
        }

        /** The subscription id recorded on the provider row (`sub_id`), or null when it is missing or invalid. */
        private fun subscriptionOf(context: Context, msgUri: Uri): Int? = try {
            context.contentResolver.query(msgUri, arrayOf(Telephony.Mms.SUBSCRIPTION_ID), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getInt(0).takeIf { id -> id >= 0 } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
