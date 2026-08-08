package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ContactResolver
import com.inboxiq.app.sms.MessageNotifier
import com.inboxiq.app.sms.MmsSender

/**
 * Periodic retry for MMS sends that failed and are awaiting a self-healing
 * config fix (see MmsSender.retry / inboxiq-config). Each retry re-checks
 * the config backend, so a message only goes through once a fix for this
 * device+carrier has actually been approved — see MmsConfigApi.
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
