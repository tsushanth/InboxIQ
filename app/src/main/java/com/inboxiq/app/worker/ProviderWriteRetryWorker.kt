package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.sms.ProviderWriteRetry

/** Unique, bounded retry of failed or unfinished writes to the Android SMS provider (see [ProviderWriteRetry]). */
class ProviderWriteRetryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (ProviderWriteRetry.create(applicationContext).run()) {
        ProviderWriteRetry.Outcome.DONE, ProviderWriteRetry.Outcome.NOT_DEFAULT -> Result.success()
        ProviderWriteRetry.Outcome.RETRY_LATER -> if (runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
    }

    companion object { private const val MAX_RUNS = 5 }
}
