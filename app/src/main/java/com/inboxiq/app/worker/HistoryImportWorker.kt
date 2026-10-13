package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.sms.HistoryImport
import com.inboxiq.app.sms.ImportEnv
import com.inboxiq.app.sms.ImportState

/**
 * Runs [HistoryImport] as unique, regular (not expedited, no foreground service) work so it outlives
 * the activity. It checkpoints after every page, so if the system stops it the next run resumes.
 */
class HistoryImportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val outcome = HistoryImport.run(ImportEnv.from(ctx), ImportState(ctx)) { DefaultSmsRole.isDefault(ctx) }
        return when (outcome) {
            is HistoryImport.Outcome.Done -> {
                if (outcome.inserted > 0) {
                    WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build())
                }
                Result.success()
            }
            HistoryImport.Outcome.NotDefault -> Result.success()
            is HistoryImport.Outcome.Failed ->
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
    }
}
