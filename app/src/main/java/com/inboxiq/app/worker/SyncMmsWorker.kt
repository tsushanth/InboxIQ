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
        val fresh = MmsSync.syncRecent(applicationContext, System.currentTimeMillis() - LOOKBACK_MS)
        notifyNew(applicationContext, fresh)
        WorkManager.getInstance(applicationContext).enqueue(
            OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
        )
        return Result.success()
    }

    companion object {
        /** A picture or group message arriving must alert the user like a text does; one alert per conversation. */
        internal suspend fun notifyNew(context: Context, fresh: List<com.inboxiq.app.data.MessageEntity>) {
            if (fresh.isEmpty()) return
            val db = com.inboxiq.app.data.AppDatabase.get(context)
            for ((address, rows) in fresh.groupBy { it.address }) {
                val latest = rows.maxBy { it.timestamp }
                try {
                    val muted = db.threadStateDao().isMuted(address, System.currentTimeMillis())
                    val label = db.threadLabelDao().get(address)?.label ?: com.inboxiq.app.data.MessageLabel.UNLABELED
                    val text = latest.body.takeIf { it.isNotBlank() && !com.inboxiq.app.reply.ReplyGate.isBlankOrPlaceholder(it) }
                        ?: if (rows.size > 1) "${rows.size} picture messages" else "Picture message"
                    com.inboxiq.app.sms.MessageNotifier.notifyIncoming(
                        context, address, com.inboxiq.app.data.ContactResolver.displayNameFor(context, address),
                        text, label, latest.timestamp, muted,
                    )
                } catch (e: Exception) {
                    android.util.Log.w("SyncMmsWorker", "notify failed: ${e.javaClass.simpleName}")
                }
            }
        }

        private const val LOOKBACK_MS = 60 * 60 * 1000L
    }
}
