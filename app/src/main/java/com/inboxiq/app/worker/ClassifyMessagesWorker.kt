package com.inboxiq.app.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.classify.ClassifierFactory
import com.inboxiq.app.classify.MessageClassifier
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ThreadLabelEntity

/**
 * Classification unit is the sender/address, not the individual message —
 * a given address is essentially always the same category, so inference
 * runs once per new address (using its most recent message as the sample)
 * and the result is cached + bulk-applied via SQL to every message from
 * that address. This means a full SMS history backfill costs one inference
 * call per distinct contact, not one per message, and repeat senders never
 * re-trigger the model at all.
 */
class ClassifyMessagesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.get(applicationContext)
        val messageDao = db.messageDao()
        val threadDao = db.threadLabelDao()

        val classifier: MessageClassifier = ClassifierFactory.create(applicationContext)

        var processed = 0
        try {
            while (processed < BATCH_CAP) {
                val addresses = threadDao.addressesNeedingClassification(PAGE_SIZE)
                if (addresses.isEmpty()) break

                for (address in addresses) {
                    val sample = threadDao.latestBody(address) ?: continue
                    val result = classifier.classify(sample)
                    threadDao.upsert(
                        ThreadLabelEntity(
                            address = address,
                            label = result.label,
                            confidence = result.confidence,
                            classifiedAt = java.lang.System.currentTimeMillis(),
                        ),
                    )
                    messageDao.applyThreadLabel(address, result.label, result.confidence)
                    // Per-message, not per-thread — a sender being generally legitimate doesn't
                    // mean every message from them is human-written, so this only applies to
                    // the exact sample the model actually saw, unlike the label above.
                    result.aiGeneratedConfidence?.let { confidence ->
                        threadDao.latestMessageId(address)?.let { id -> messageDao.updateAiGeneratedConfidence(id, confidence) }
                    }
                }
                processed += addresses.size
            }
        } finally {
            classifier.close()
        }

        Log.d(TAG, "Classified $processed thread(s) this run")

        if (threadDao.addressesNeedingClassification(1).isNotEmpty()) {
            WorkManager.getInstance(applicationContext).enqueue(
                OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build(),
            )
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "ClassifyMessagesWorker"
        private const val PAGE_SIZE = 50
        private const val BATCH_CAP = 500
    }
}
