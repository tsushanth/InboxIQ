package com.inboxiq.app.sms

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.inboxiq.app.worker.MmsDownloadWorker
import java.util.concurrent.TimeUnit

/** Queues [MmsDownloadWorker]: only with a network, with exponential backoff, one unique job per purpose. */
object MmsDownloadScheduler {
    private const val TAG = "MmsDownloadScheduler"

    /** Every pending row. KEEP: a request while one is already queued or running changes nothing. */
    const val WORK_ALL = "mms_download_all"
    private fun workNameFor(id: Long) = "mms_download_$id"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun request(delayMs: Long, id: Long?) = OneTimeWorkRequestBuilder<MmsDownloadWorker>()
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .apply { if (id != null) setInputData(Data.Builder().putLong(MmsDownloadWorker.KEY_ID, id).build()) }
        .build()

    /**
     * Download whatever is pending, [delayMs] from now. [replace] is for a brand-new message: it must not wait behind
     * an older pass that is sitting in backoff (attempt counts live in [MmsDownloadStore], so nothing is lost).
     */
    fun enqueueAll(context: Context, delayMs: Long = 0L, replace: Boolean = false) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_ALL, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(delayMs, null),
        )
    }

    /** Try row [id] again in [delayMs] (the backoff after a failed download). */
    fun enqueueRetry(context: Context, id: Long, delayMs: Long) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workNameFor(id), ExistingWorkPolicy.KEEP, request(delayMs, id))
    }

    /** A pass every few hours while online, which also covers "connectivity regained" for rows with no job queued. */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<MmsDownloadWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork("mms_download_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** The user asked: forget the attempts so far for [id] and download it now. */
    fun retryNow(context: Context, id: Long, store: MmsDownloadStore = MmsDownloadStore(context)) {
        store.resetAttempts(id)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workNameFor(id), ExistingWorkPolicy.REPLACE, request(0L, id))
    }

    /**
     * The cheap self-healing pass: when any announced MMS is still undownloaded and may be tried now (not running,
     * not backing off, not out of attempts), queue one download pass. Returns how many rows were eligible.
     */
    fun healPending(
        context: Context,
        now: Long = System.currentTimeMillis(),
        store: MmsDownloadStore = MmsDownloadStore(context),
        enqueue: (Context) -> Unit = { enqueueAll(it) },
    ): Int {
        val pending = try {
            MmsPending.query(context.contentResolver)
        } catch (e: Exception) {
            Log.w(TAG, "heal: provider query failed: ${e.javaClass.simpleName}")
            return 0
        }
        reconcile(context.contentResolver, store, pending.map { it.id }.toSet())
        val eligible = pending.count { eligible(it.id, originOf(it, now), store, now) }
        if (eligible > 0) enqueue(context)
        return eligible
    }

    /**
     * Rows this store holds a record for that are no longer undownloaded: the platform turned them into real
     * messages. Reports the ones that needed more than one attempt (did the image arrive?), then forgets them.
     */
    fun reconcile(
        resolver: android.content.ContentResolver,
        store: MmsDownloadStore,
        pendingIds: Set<Long>,
        report: (MmsDownloadReport.Event) -> Unit = MmsDownloadReport::log,
    ) {
        for (id in store.knownIds() - pendingIds) {
            val e = store.entry(id)
            if (e != null && (e.attempts > 1 || store.gaveUpReported(id))) {
                report(
                    MmsDownloadReport.Event(
                        MmsDownloadReport.Kind.RESOLVED_AFTER_RETRY, id,
                        MmsDownloadPolicy.codeName(e.lastCode ?: Activity.RESULT_OK), e.attempts, MmsPending.hasImage(resolver, id),
                    ),
                )
            }
            store.forget(id)
        }
    }

    /** When the carrier announced the message: the row's own date, else now. The link's life is counted from there. */
    internal fun originOf(row: MmsPending.Row, now: Long): Long =
        if (row.dateSeconds > 0) minOf(row.dateSeconds * 1000, now) else now

    internal fun eligible(id: Long, origin: Long, store: MmsDownloadStore, now: Long): Boolean =
        MmsDownloadPolicy.mayRetry(store.attempts(id), store.firstSeenAt(id, origin), now) &&
            !store.isInFlight(id, now) && store.notBefore(id) <= now
}
