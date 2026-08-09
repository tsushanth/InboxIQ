package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.sms.MessageNotifier
import com.inboxiq.app.sms.MmsSender

/**
 * Periodic retry for MMS sends that failed and are still pending (see
 * MmsSender.retry) — purely local, recovers a transient send failure (no
 * signal, brief carrier hiccup) by trying both known strategies again.
 * No network call is ever made; nothing leaves the device.
 */
class AutoHealWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val dao = AppDatabase.get(applicationContext).messageDao()
        val pending = dao.messagesAwaitingAutoHeal()

        for (message in pending) {
            val healed = MmsSender.retry(applicationContext, message)
            if (healed) {
                val displayName = ContactResolver.displayNameFor(applicationContext, message.address)
                MessageNotifier.notifyAutoHealed(applicationContext, message.address, displayName)
            }
        }
        return Result.success()
    }
}
