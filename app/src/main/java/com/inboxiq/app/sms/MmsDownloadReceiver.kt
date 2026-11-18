package com.inboxiq.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.SyncMmsWorker
import java.util.concurrent.TimeUnit

/**
 * Fires once SmsManager.downloadMultimediaMessage (see MmsDownloadWorker) actually completes —
 * success or failure. Reads the real outcome: RESULT_OK, or one of SmsManager's MMS_ERROR_* codes.
 * A transient failure schedules another download after an exponential backoff, up to the cap in
 * [MmsDownloadPolicy]; a permanent one stops and is reported. Either way the sync runs again so InboxIQ
 * picks up whatever is now in content://mms.
 */
class MmsDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_MMS_ID, -1L)
        val httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)
        handleResult(
            context = context, id = id, resultCode = resultCode, httpStatus = httpStatus,
            store = MmsDownloadStore(context),
            scheduleRetry = { rowId, delayMs -> MmsDownloadScheduler.enqueueRetry(context, rowId, delayMs) },
        )
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncMmsWorker>()
                .setInitialDelay(2, TimeUnit.SECONDS)
                .build(),
        )
    }

    companion object {
        private const val TAG = "MmsDownloadReceiver"

        /** Intent extra carrying the provider row id the download was for. */
        const val EXTRA_MMS_ID = "com.inboxiq.app.extra.MMS_ID"

        /**
         * What to do with one download result. Logs the id and the result-code name (no addresses, no content),
         * records it, and on a transient failure schedules the next attempt with the policy's backoff.
         * Returns the classified outcome.
         */
        internal fun handleResult(
            context: Context,
            id: Long,
            resultCode: Int,
            httpStatus: Int,
            store: MmsDownloadStore,
            scheduleRetry: (id: Long, delayMs: Long) -> Unit,
            now: Long = System.currentTimeMillis(),
            report: (MmsDownloadReport.Event) -> Unit = MmsDownloadReport::log,
        ): MmsDownloadPolicy.Outcome {
            val outcome = MmsDownloadPolicy.classify(resultCode, httpStatus)
            val name = MmsDownloadPolicy.codeName(resultCode)
            val attempts = if (id >= 0) store.attempts(id) else 0
            Log.i(TAG, "download result id=$id code=$name($resultCode) httpStatus=$httpStatus outcome=$outcome attempt=$attempts")
            if (id < 0) return outcome // a result without a row id (an older pending intent): nothing to track

            when (outcome) {
                // The platform says it fetched the message. The row should now be a real message; if it is still an
                // undownloaded notification after a few minutes the heal pass tries again, so back off first.
                MmsDownloadPolicy.Outcome.SUCCESS ->
                    store.recordResult(id, resultCode, notBefore = now + maxOf(MmsDownloadPolicy.delayMillis(attempts), MmsDownloadPolicy.SUCCESS_RECHECK_MS))
                MmsDownloadPolicy.Outcome.PERMANENT -> {
                    store.recordResult(id, resultCode)
                    giveUp(context, id, name, attempts, MmsDownloadReport.Kind.GAVE_UP_PERMANENT, store, report)
                }
                MmsDownloadPolicy.Outcome.TRANSIENT -> {
                    if (MmsDownloadPolicy.mayRetry(attempts, store.firstSeenAt(id, now), now)) {
                        val delay = MmsDownloadPolicy.delayMillis(attempts)
                        store.recordResult(id, resultCode, notBefore = now + delay)
                        Log.i(TAG, "mms id=$id will retry in ${delay / 1000}s (attempt $attempts of ${MmsDownloadPolicy.MAX_ATTEMPTS})")
                        scheduleRetry(id, delay)
                    } else {
                        store.recordResult(id, resultCode)
                        giveUp(context, id, name, attempts, MmsDownloadReport.Kind.GAVE_UP_EXHAUSTED, store, report)
                    }
                }
            }
            return outcome
        }

        private fun giveUp(
            context: Context, id: Long, codeName: String, attempts: Int, kind: MmsDownloadReport.Kind,
            store: MmsDownloadStore, report: (MmsDownloadReport.Event) -> Unit,
        ) {
            if (store.gaveUpReported(id)) return
            store.markGaveUpReported(id)
            val arrived = try {
                MmsPending.hasImage(context.contentResolver, id)
            } catch (e: Exception) {
                false
            }
            report(MmsDownloadReport.Event(kind, id, codeName, attempts, arrived))
        }
    }
}
