package com.inboxiq.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.sms.DefaultSmsRole
import com.inboxiq.app.data.SendStatus
import com.inboxiq.app.sms.MmsSender
import com.inboxiq.app.sms.SentMmsLookup

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
        // Play's SMS policy: once InboxIQ is no longer the default SMS app it must stop using the SMS permissions,
        // and a retry from a non-default app could re-send an old picture message without the user knowing.
        if (!isDefaultSms(applicationContext)) return Result.success()
        val dao = AppDatabase.get(applicationContext).messageDao()
        val pending = dao.messagesAwaitingAutoHeal()

        for (listed in pending) {
            // Read the row again: a result callback may have settled it since the list was taken.
            val message = dao.getById(listed.id) ?: continue
            if (!message.awaitingAutoHeal || message.sendStatus == SendStatus.SENT) continue
            // Never send a picture the platform already has as sent: settle the row instead.
            if (hasSentCopy(applicationContext, message.address, message.timestamp)) {
                dao.markMmsSent(message.id)
                continue
            }
            MmsSender.retry(applicationContext, message)
        }
        return Result.success()
    }

    companion object {
        /** Swappable in tests; the real check is the platform's. */
        @Volatile internal var isDefaultSms: (Context) -> Boolean = { DefaultSmsRole.isDefault(it) }

        /** Swappable in tests; whether the MMS provider already holds this message as sent (see SentMmsLookup). */
        @Volatile internal var hasSentCopy: (Context, String, Long) -> Boolean = { c, a, t -> SentMmsLookup.hasSentCopy(c, a, t) }
    }
}
