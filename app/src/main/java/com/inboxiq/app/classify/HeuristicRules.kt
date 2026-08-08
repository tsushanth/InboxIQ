package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/**
 * Cheap keyword rules for categories the bundled binary spam/ham model
 * doesn't cover at all (OTP/2FA, promo, scam-vs-plain-spam). Checked before
 * the ONNX model runs since these are high-precision, near-zero-cost signals.
 *
 * All matching uses word boundaries (\b), not plain substring `contains` —
 * a real bug shipped in v1 where the SCAM keyword "irs" matched inside
 * "First Tech Federal Credit Union" and mislabeled a legitimate bank alert.
 */
object HeuristicRules {

    private fun containsAnyWord(text: String, words: List<String>): Boolean =
        words.any { word -> Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    private fun containsAnyPhrase(text: String, phrases: List<String>): Boolean =
        phrases.any { text.contains(it, ignoreCase = true) }

    private val otpWords = listOf("otp", "code", "verify", "verification")

    private val scamWords = listOf("irs")
    private val scamPhrases = listOf(
        "act now", "claim your", "you've won", "you have won", "urgent action", "arrest warrant",
    )

    private val promoPhrases = listOf("% off", "unsubscribe", "reply stop", "promo code", "limited time")

    fun match(text: String): ClassificationResult? {
        if (Regex("\\b\\d{4,8}\\b").containsMatchIn(text) && containsAnyWord(text, otpWords)) {
            return ClassificationResult(MessageLabel.OTP_2FA, 0.9f)
        }

        if (containsAnyWord(text, scamWords) || containsAnyPhrase(text, scamPhrases)) {
            return ClassificationResult(MessageLabel.SCAM, 0.7f)
        }

        if (containsAnyPhrase(text, promoPhrases)) {
            return ClassificationResult(MessageLabel.PROMO, 0.7f)
        }

        return null
    }
}
