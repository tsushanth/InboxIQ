package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.sms.MmsSync

/** Reads any MMS from the last hour into our DB, then enqueues classification for new threads. */
class SyncMmsWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        MmsSync.syncRecent(applicationContext, System.currentTimeMillis() - LOOKBACK_MS)
        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
        )
        return Result.success()
    }

    companion object {
        private const val LOOKBACK_MS = 60 * 60 * 1000L
    }
}
