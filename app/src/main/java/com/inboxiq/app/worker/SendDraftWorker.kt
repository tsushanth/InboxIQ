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
 * stuck mid-flight. WorkManager persists the job across process death/reboots (sends are claimed, never auto-retried).
 */
class SendDraftWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val draftId = inputData.getString(KEY_DRAFT_ID) ?: return Result.failure()
        val dao = AppDatabase.get(applicationContext).agentDraftDao()
        val draft = dao.findById(draftId) ?: return Result.failure()
        // At-most-once: a retry after a crash between dispatch and the status write would
        // send the real SMS twice (and SmsSender inserts a message row per call). Claim first;
        // if the claim is lost or the draft was already handled, do nothing.
        if (dao.claimForSending(draftId) == 0) return Result.success()

        return try {
            SmsSender.send(applicationContext, draft.address, draft.body)
            dao.setStatus(draftId, AgentDraftStatus.SENT)
            Result.success()
        } catch (e: Exception) {
            // Not retried automatically: the failure may have happened after dispatch. Return the
            // draft to PENDING so the user sees it and can decide to tap Send again.
            dao.setStatus(draftId, AgentDraftStatus.PENDING)
            Result.failure()
        }
    }

    companion object {
        private const val KEY_DRAFT_ID = "draft_id"
    
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
