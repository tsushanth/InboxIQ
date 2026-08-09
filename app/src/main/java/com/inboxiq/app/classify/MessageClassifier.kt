package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

data class ClassificationResult(
    val label: MessageLabel,
    val confidence: Float,
    /** 0-1 confidence the message text was AI-generated — only ever set by MID/HIGH tiers (see GemmaClassifier). */
    val aiGeneratedConfidence: Float? = null,
)

/**
 * Pluggable inference backend. DEFAULT runs a fine-tuned bert-tiny encoder via
 * ONNX Runtime Mobile (see OnnxEncoderClassifier) — fast, fully bundled in the
 * APK, no setup. MID (Gemma 3 270M) and HIGH (Qwen 1.5B) run via LiteRT-LM (see
 * GemmaClassifier) — meaningfully better at subtle scam/deception language and
 * additionally score AI-generated-text likelihood, at the cost of an opt-in
 * ~125MB-1GB model download (see GemmaModelStore) and slower inference.
 * Switching tiers is a settings toggle, not a code change.
 */
interface MessageClassifier {
    suspend fun classify(text: String): ClassificationResult
    fun close()
}
