package com.inboxiq.app.worker

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inboxiq.app.classify.ClassifierFactory
import com.inboxiq.app.classify.MessageClassifier
import com.inboxiq.app.classify.ThreadContext
import com.inboxiq.app.classify.TriageClassifier
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.ThreadLabelEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Classification unit is the sender/address, not the individual message: a given address is
 * essentially always the same category, so the classifier reads a few recent messages plus what it
 * knows about the conversation (sender type, whether the user ever replied) once per address, and the
 * result is cached in thread_labels and bulk-applied by SQL to every incoming message from it. A full
 * backlog therefore costs one classification per distinct contact, and repeat senders never re-run it.
 *
 * Memory and speed: addresses are processed in pages of [PAGE_SIZE]; each page is written in one
 * transaction; only one pass runs at a time in the process (several workers are enqueued by receivers
 * and importers, and they would otherwise each load the model); the spam model is loaded on first need
 * and released when the pass ends or the system asks for memory back.
 */
class ClassifyMessagesWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = passLock.withLock {
        val db = AppDatabase.get(applicationContext)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = SystemClock.elapsedRealtime()

        val classifier: MessageClassifier = ClassifierFactory.create(applicationContext)
        val trimListener = TrimListener(classifier)
        applicationContext.registerComponentCallbacks(trimListener)

        var processed = 0
        var budgetExhausted = false
        try {
            // 1. Addresses that have never been classified.
            while (true) {
                val addresses = db.threadLabelDao().addressesNeedingClassification(PAGE_SIZE)
                if (addresses.isEmpty()) break
                if (SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS) { budgetExhausted = true; break }
                processed += classifyPage(db, classifier, addresses)
                currentCoroutineContext().ensureActive()
            }

            // 2. After an upgrade of the classifier, re-sort labels produced by the older pipeline (once).
            if (!budgetExhausted && prefs.getInt(KEY_PIPELINE_VERSION, 0) < PIPELINE_VERSION) {
                val cutoff = prefs.getLong(KEY_REFRESH_CUTOFF, 0L).takeIf { it != 0L }
                    ?: System.currentTimeMillis().also { prefs.edit().putLong(KEY_REFRESH_CUTOFF, it).apply() }
                while (true) {
                    val addresses = db.threadLabelDao().staleAddresses(cutoff, PAGE_SIZE)
                    if (addresses.isEmpty()) {
                        prefs.edit().putInt(KEY_PIPELINE_VERSION, PIPELINE_VERSION).remove(KEY_REFRESH_CUTOFF).apply()
                        break
                    }
                    if (SystemClock.elapsedRealtime() - started > TIME_BUDGET_MS) { budgetExhausted = true; break }
                    processed += classifyPage(db, classifier, addresses)
                    currentCoroutineContext().ensureActive()
                }
            }
        } finally {
            applicationContext.unregisterComponentCallbacks(trimListener)
            classifier.close() // releases the model; nothing stays resident between passes
        }

        Log.d(TAG, "Classified $processed thread(s) this run")

        val more = budgetExhausted || db.threadLabelDao().addressesNeedingClassification(1).isNotEmpty()
        if (more) {
            WorkManager.getInstance(applicationContext).enqueue(OneTimeWorkRequestBuilder<ClassifyMessagesWorker>().build())
        }
        Result.success()
    }

    /** Reads every address of a page, then writes all its results in one transaction. Returns the number classified. */
    private suspend fun classifyPage(db: AppDatabase, classifier: MessageClassifier, addresses: List<String>): Int {
        val dao = db.threadLabelDao()
        val messages = db.messageDao()
        data class Outcome(val address: String, val entity: ThreadLabelEntity?, val aiConfidence: Float?)

        val outcomes = ArrayList<Outcome>(addresses.size)
        val now = System.currentTimeMillis()
        for (address in addresses) {
            val bodies = dao.recentIncomingBodies(address, SAMPLE_MESSAGES)
            if (bodies.isEmpty()) { outcomes += Outcome(address, null, null); continue }
            val result = classifier.classifyThread(ThreadContext(address, bodies, dao.substantiveReplies(address)))
            outcomes += Outcome(address, ThreadLabelEntity(address, result.label, result.confidence, now), result.aiGeneratedConfidence)
        }
        db.withTransaction {
            for (o in outcomes) {
                val e = o.entity
                if (e == null) { dao.touch(o.address, now); continue }
                dao.upsert(e)
                messages.applyThreadLabel(o.address, e.label, e.confidence)
                // Per-message, not per-thread: only the exact sample the model saw gets an AI-text score.
                o.aiConfidence?.let { c -> dao.latestIncomingMessageId(o.address)?.let { id -> messages.updateAiGeneratedConfidence(id, c) } }
            }
        }
        return outcomes.count { it.entity != null }
    }

    /** Frees the spam model when the system asks for memory while a pass is running; it reloads only if needed again. */
    private class TrimListener(private val classifier: MessageClassifier) : ComponentCallbacks2 {
        @Suppress("DEPRECATION")
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) (classifier as? TriageClassifier)?.trim()
        }

        override fun onLowMemory() { (classifier as? TriageClassifier)?.trim() }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    companion object {
        private const val TAG = "ClassifyMessagesWorker"
        private const val PREFS = "inboxiq_prefs"
        private const val KEY_PIPELINE_VERSION = "classifier_pipeline_version"
        private const val KEY_REFRESH_CUTOFF = "classifier_refresh_cutoff"

        /** Bump when the rules or model change enough that old labels should be recomputed. 2 = TriageEngine. */
        const val PIPELINE_VERSION = 2

        const val PAGE_SIZE = 50
        const val SAMPLE_MESSAGES = 5
        private const val TIME_BUDGET_MS = 8 * 60 * 1000L

        /** One classification pass at a time per process. */
        private val passLock = Mutex()
    }
}
