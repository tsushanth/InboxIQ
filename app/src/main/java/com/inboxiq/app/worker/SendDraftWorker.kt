package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.inboxiq.app.data.AgentDraftStatus
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.sms.SmsSender

/**
 * Sends an agent draft (see AgentDraftsSection) via WorkManager rather than a coroutine
 * scoped to the Settings screen — a plain `coroutineScope.launch` dies if the user backgrounds
 * the app or the process gets killed right after tapping Send, which could leave a draft
 * stuck mid-flight. WorkManager persists the job and retries across process death/reboots.
 */
class SendDraftWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        val dao = AppDatabase.get(applicationContext).agentDraftDao()
        val draft = dao.findById(draftId) ?: return Result.failure()
        if (draft.status != AgentDraftStatus.PENDING) return Result.success() // already handled

        return try {
            SmsSender.send(applicationContext, draft.address, draft.body)
            dao.setStatus(draftId, AgentDraftStatus.SENT)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_DRAFT_ID = "draft_id"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, draftId: String) {
            val work = OneTimeWorkRequestBuilder<SendDraftWorker>()
                .setInputData(workDataOf(KEY_DRAFT_ID to draftId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "send_draft_$draftId",
                androidx.work.ExistingWorkPolicy.KEEP,
                work,
            )
        }
    }
}
