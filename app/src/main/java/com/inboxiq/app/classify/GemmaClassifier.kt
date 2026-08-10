package com.inboxiq.app.classify

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MID-tier classifier — Gemma 3 270M running on-device via LiteRT-LM (see GemmaModelStore for
 * how the ~290MB model gets onto the device: an opt-in Play Asset Delivery pack, never a
 * Hugging Face runtime fetch). Meaningfully better than the bundled bert-tiny model at subtle
 * scam/deception language, and additionally scores AI-generated-text likelihood — bert-tiny
 * has no signal for that at all.
 *
 * One Engine/Conversation pair is created once and reused for every classify() call — cold
 * model load takes real time (multi-second), so this must not happen per-message.
 */
class GemmaClassifier(modelPath: String) : MessageClassifier {

    private val engine = Engine(
        EngineConfig(modelPath, Backend.CPU(), null, null, null, null, null),
    ).apply { initialize() }

    private val conversation: Conversation = engine.createConversation(
        ConversationConfig(systemInstruction = Contents.of(SYSTEM_PROMPT)),
    )

    override suspend fun classify(text: String): ClassificationResult = withContext(Dispatchers.Default) {
        val heuristic = HeuristicRules.match(text)
        if (heuristic != null) return@withContext heuristic

        val raw = try {
            val response = conversation.sendMessage(text)
            response.contents.contents.filterIsInstance<Content.Text>().joinToString(" ") { it.text }
        } catch (e: Exception) {
            android.util.Log.w("GemmaClassifier", "sendMessage threw", e)
            return@withContext ClassificationResult(MessageLabel.UNLABELED, 0f)
        }

        parseResponse(raw)
    }

    /**
     * Expects "LABEL|confidence|ai_generated_confidence" per SYSTEM_PROMPT, but a 270M model
     * doesn't reliably finish the full format (confirmed live: it sometimes stops right after
     * the label). A bare, real category is still a useful signal — worth keeping over falling
     * back to UNLABELED — just at a lower confidence than the model would have stated itself.
     */
    private fun parseResponse(raw: String): ClassificationResult {
        val line = raw.trim().lines().firstOrNull { line -> MessageLabel.entries.any { line.trim().uppercase().startsWith(it.name) } }
            ?: return fallback()
        val parts = line.split('|')

        val label = runCatching { MessageLabel.valueOf(parts[0].trim().uppercase()) }.getOrDefault(MessageLabel.UNLABELED)
        val confidence = parts.getOrNull(1)?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f) ?: BARE_LABEL_CONFIDENCE
        val aiGenerated = parts.getOrNull(2)?.trim()?.toFloatOrNull()?.coerceIn(0f, 1f)

        return ClassificationResult(label, confidence, aiGenerated)
    }

    private fun fallback() = ClassificationResult(MessageLabel.UNLABELED, 0f)

    override fun close() {
        conversation.close()
        engine.close()
    }

    companion object {
        private const val BARE_LABEL_CONFIDENCE = 0.6f

        // Gemma 3 270M is small and literal-minded — an earlier version of this prompt used
        // "LABEL|confidence|ai_generated_confidence" as the format line, and the model just
        // echoed those words back verbatim instead of substituting real values (confirmed live).
        // Worked examples of the exact output shape fix this far better than describing the format.
        private val SYSTEM_PROMPT = """
            Classify one SMS message. Reply with ONLY a line matching one of these exact examples
            (pick the category, then real numbers 0.00-1.00 for how confident you are, and how
            likely the text was AI-written) — never repeat the words "LABEL" or "confidence" themselves:

            SCAM|0.92|0.10
            SPAM|0.80|0.05
            OTP_2FA|0.99|0.02
            WORK|0.60|0.05
            PERSONAL|0.75|0.05
            PROMO|0.85|0.05

            SCAM = phishing, fake urgency, impersonation, requests for money/credentials.
            SPAM = unsolicited bulk/marketing that isn't deceptive.
            OTP_2FA = one-time passcodes/verification codes.
            The 3rd number is independent of the category: how likely the text itself reads as
            AI-generated (formulaic, unnaturally generic) rather than human-written — usually low.

            Message:
        """.trimIndent()
    }
}
