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
 * ONNX Runtime Mobile (see OnnxSpamScorer and TriageEngine) — fast, fully bundled in the
 * APK, no setup. MID (Gemma 3 270M) and HIGH (Qwen 1.5B) run via LiteRT-LM (see
 * GemmaClassifier) — meaningfully better at subtle scam/deception language and
 * additionally score AI-generated-text likelihood, at the cost of an opt-in
 * ~125MB-1GB model download (see GemmaModelStore) and slower inference.
 * Switching tiers is a settings toggle, not a code change.
 */
interface MessageClassifier {
    suspend fun classify(text: String): ClassificationResult

    /**
     * Classifies a whole conversation: several recent messages, the sender type and whether the user
     * ever answered. The default (used by the Gemma tiers) lets the deterministic rules decide whatever
     * they are confident about, never lets a model call a known contact spam or scam, and otherwise
     * asks the model about the latest message, as before.
     */
    suspend fun classifyThread(ctx: ThreadContext): ClassificationResult {
        val rules = TriageEngine.classify(ctx, null)
        if (rules.basis == Basis.RULE) return rules.toResult()
        val latest = ctx.recentIncoming.firstOrNull() ?: return rules.toResult()
        val modelResult = classify(latest)
        val knownContact = Relationship.of(ctx.sender, ctx.replies) == Relationship.STRONG
        val junk = modelResult.label == com.inboxiq.app.data.MessageLabel.SPAM || modelResult.label == com.inboxiq.app.data.MessageLabel.SCAM
        return if (knownContact && junk) ClassificationResult(com.inboxiq.app.data.MessageLabel.PERSONAL, 0.6f) else modelResult
    }

    fun close()
}
