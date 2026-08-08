package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

data class ClassificationResult(val label: MessageLabel, val confidence: Float)

/**
 * Pluggable inference backend. v1 default implementation runs a fine-tuned
 * DistilBERT/MobileBERT-class encoder via ONNX Runtime Mobile (see
 * OnnxEncoderClassifier). Later tiers (Gemma 3 270M / 1-3B via LiteRT-LM)
 * implement the same interface so switching tiers is a settings toggle,
 * not a code change.
 */
interface MessageClassifier {
    suspend fun classify(text: String): ClassificationResult
    fun close()
}
