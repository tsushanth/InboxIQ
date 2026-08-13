package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.sms.MmsSender

/**
 * Periodic retry for MMS sends that failed and are still pending (see
 * MmsSender.retry) — purely local, recovers a transient send failure (no
 * signal, brief carrier hiccup) by trying both known strategies again.
 * No network call is ever made; nothing leaves the device.
 *
 * A dispatched-without-throwing retry only means handed off, not delivered —
 * MmsStatusReceiver owns the real async result and fires the "Fixed!" notification
 * only once delivery is actually confirmed.
 */
class AutoHealWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).messageDao()
        val pending = dao.messagesAwaitingAutoHeal()

        for (message in pending) {
            MmsSender.retry(applicationContext, message)
        }
        return Result.success()
    }
}
