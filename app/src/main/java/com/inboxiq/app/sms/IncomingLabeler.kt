package com.inboxiq.app.sms

import android.content.Context
import com.inboxiq.app.classify.ClassificationResult
import com.inboxiq.app.classify.ClassifierFactory
import com.inboxiq.app.classify.ClassifierTier
import com.inboxiq.app.classify.ClassifierTierPreference
import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.AppDatabase
import com.inboxiq.app.data.MessageLabel
import com.inboxiq.app.data.ThreadLabelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides the label an incoming text gets BEFORE its notification is posted, so the first text from
 * a brand-new sender lands in the right channel instead of the general one.
 *
 * Order: user-corrected thread (their choice stands) -> fast heuristic on this message -> cached
 * thread label -> a bounded model call for a sender we have never classified -> otherwise leave it
 * unlabeled and let the background worker do it (the old behaviour).
 *
 * The model call is the seam: [defaultModel] uses only the public MessageClassifier interface and
 * is skipped unless the default (small ONNX) tier is selected, because the larger tiers cannot load
 * inside a broadcast's time budget. Pass a different `model` lambda to change or disable it.
 */
object IncomingLabeler {

    /** A broadcast has ~10s; a small encoder answers in well under this, anything slower falls back. */
    const val MODEL_BUDGET_MS = 1_500L

    private val modelLock = Mutex()

    fun defaultModel(context: Context): suspend (String) -> ClassificationResult? = model@{ text ->
        if (ClassifierTierPreference.get(context) != ClassifierTier.DEFAULT) return@model null
        withTimeoutOrNull(MODEL_BUDGET_MS) {
            modelLock.withLock {
                withContext(Dispatchers.Default) {
                    val classifier = ClassifierFactory.create(context)
                    try {
                        classifier.classify(text)
                    } finally {
                        classifier.close()
                    }
                }
            }
        }
    }

    suspend fun resolve(
        db: AppDatabase,
        address: String,
        body: String,
        timestamp: Long,
        messageId: Long,
        model: suspend (String) -> ClassificationResult?,
        enqueueClassifier: () -> Unit,
    ): MessageLabel {
        val cached = db.threadLabelDao().get(address)
        // A high-precision heuristic (OTP code, "you've won" scam phrasing, etc.)
        // on THIS message overrides a stale/wrong cached thread label — e.g. a
        // spoofed sender number reusing an address we'd previously seen as benign.
        // Only this message is corrected, not the address's older history, since
        // we can't tell from one message whether the whole thread actually changed.
        // ...unless the user hand-filed this sender, in which case their choice stands.
        val userCorrected = db.threadStateDao().get(address)?.labelCorrected == true
        val heuristic = if (userCorrected) null else HeuristicRules.match(body)
        val label = heuristic?.label ?: cached?.label ?: MessageLabel.UNLABELED
        when {
            heuristic != null && heuristic.label != cached?.label -> {
                db.threadLabelDao().upsert(ThreadLabelEntity(address, heuristic.label, heuristic.confidence, timestamp))
                db.messageDao().updateLabel(messageId, heuristic.label, heuristic.confidence)
            }
            cached != null -> db.messageDao().updateLabel(messageId, cached.label, cached.confidence)
            else -> {
                // Brand-new sender, no rule fired: try the quick model before notifying.
                val quick = runCatching { model(body) }.getOrNull()
                if (quick != null && quick.label != MessageLabel.UNLABELED) {
                    db.threadLabelDao().upsert(ThreadLabelEntity(address, quick.label, quick.confidence, System.currentTimeMillis()))
                    db.messageDao().updateLabel(messageId, quick.label, quick.confidence)
                    quick.aiGeneratedConfidence?.let { db.messageDao().updateAiGeneratedConfidence(messageId, it) }
                    return quick.label
                }
                enqueueClassifier()
            }
        }
        return label
    }
}
