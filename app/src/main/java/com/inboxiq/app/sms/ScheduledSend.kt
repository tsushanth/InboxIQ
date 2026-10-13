package com.inboxiq.app.sms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ScheduledMessageEntity
import java.util.concurrent.TimeUnit

/**
 * Database side of scheduled send and undo send. Kept free of Android services so the rules
 * (who wins the race between "cancel" and "send", what is overdue) are unit-testable.
 *
 * The row is the source of truth: whoever deletes it first owns the message. A cancel that
 * deletes it means the text is never sent; a sender that deletes it sends exactly once, even
 * if both the in-process timer and the WorkManager fallback fire.
 */
class ScheduledStore(private val db: AppDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.scheduledMessageDao()

    suspend fun create(address: String, body: String, sendAt: Long, isUndoWindow: Boolean): Long =
        dao.insert(ScheduledMessageEntity(address = address, body = body, sendAt = sendAt, createdAt = clock(), isUndoWindow = isUndoWindow))

    /** True when this call cancelled it (nothing will be sent). False if it was already sent or cancelled. */
    suspend fun cancel(id: Long): Boolean = dao.delete(id) == 1

    /** Claims the message for sending. Returns it only to the single caller that wins. */
    suspend fun claim(id: Long): ScheduledMessageEntity? {
        val row = dao.get(id) ?: return null
        return if (dao.delete(id) == 1) row else null
    }

    suspend fun pending(): List<ScheduledMessageEntity> = dao.all()
    fun overdue(rows: List<ScheduledMessageEntity>): List<ScheduledMessageEntity> = rows.filter { it.sendAt <= clock() }
}

object ScheduledSend {
    private fun workName(id: Long) = "scheduled_send_$id"

    /** Queues [body] for [sendAt]. WorkManager is not exact: expect it within a few minutes of the chosen time. */
    suspend fun schedule(context: Context, address: String, body: String, sendAt: Long, isUndoWindow: Boolean): Long {
        val id = ScheduledStore(AppDatabase.get(context)).create(address, body, sendAt, isUndoWindow)
        enqueueWork(context, id, sendAt)
        return id
    }

    private fun enqueueWork(context: Context, id: Long, sendAt: Long) {
        val delay = (sendAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<SendScheduledWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(SendScheduledWorker.KEY_ID, id).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(context: Context, id: Long): Boolean {
        val cancelled = ScheduledStore(AppDatabase.get(context)).cancel(id)
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        return cancelled
    }

    /** Sends it now if nobody else has (used by the undo timer and by the worker). */
    suspend fun dispatch(context: Context, id: Long): Boolean {
        val row = ScheduledStore(AppDatabase.get(context)).claim(id) ?: return false
        SmsSender.send(context, row.address, row.body)
        return true
    }

    /** On app start: make sure every waiting message has a work request (e.g. after a clear of WorkManager data). */
    suspend fun reenqueueAll(context: Context) {
        ScheduledStore(AppDatabase.get(context)).pending().forEach { enqueueWork(context, it.id, it.sendAt) }
    }
}

class SendScheduledWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.failure()
        ScheduledSend.dispatch(applicationContext, id)
        return Result.success()
    }

    companion object { const val KEY_ID = "scheduled_id" }
}
