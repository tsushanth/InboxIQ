package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/** Heuristics-only fallback, used if the ONNX model/assets fail to load. */
class StubClassifier : MessageClassifier {
    override suspend fun classify(text: String): ClassificationResult =
        HeuristicRules.match(text) ?: ClassificationResult(MessageLabel.UNLABELED, 0f)

    override fun close() = Unit
}
