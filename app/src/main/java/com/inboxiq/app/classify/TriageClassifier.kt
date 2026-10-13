package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/**
 * Default-tier classifier: [TriageEngine] with the (lazily loaded) bert-tiny spam model as one input.
 * With [spam] = null it is the rules-only fallback used when the model cannot be loaded.
 */
class TriageClassifier(private val spam: SpamScorer?) : MessageClassifier {
    override suspend fun classify(text: String): ClassificationResult =
        TriageEngine.classifyMessage(text, SenderProfile.UNKNOWN, 0, spam).toResult()

    override suspend fun classifyThread(ctx: ThreadContext): ClassificationResult =
        TriageEngine.classify(ctx, spam).toResult()

    /** Frees the model now; it reloads on demand if the classifier is used again. */
    fun trim() = (spam as? LazySpamScorer)?.trim()

    override fun close() {
        (spam as? AutoCloseable)?.close()
    }
}

/** Heuristics-only fallback, used if the ONNX model/assets fail to load. */
class StubClassifier : MessageClassifier by TriageClassifier(null)

