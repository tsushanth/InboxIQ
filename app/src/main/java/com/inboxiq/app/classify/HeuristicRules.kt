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

    private val otpWords = listOf("otp", "code", "verify", "verification")

    private val scamWords = listOf("irs")
    private val scamPhrases = listOf(
        "act now", "claim your", "you've won", "you have won", "urgent action", "arrest warrant",
    )

    private val promoPhrases = listOf("% off", "unsubscribe", "reply stop", "promo code", "limited time")

    /** The rule that fired, in words the user can check against the message. */
    data class Reason(val result: ClassificationResult, val explanation: String)

    fun match(text: String): ClassificationResult? = evaluate(text)?.result

    /** Same decision as [match], plus why. Used by the "Why was this labelled?" sheet. */
    fun explain(text: String): Reason? = evaluate(text)

    private fun firstWord(text: String, words: List<String>): String? =
        words.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    private fun firstPhrase(text: String, phrases: List<String>): String? =
        phrases.firstOrNull { text.contains(it, ignoreCase = true) }

    private fun evaluate(text: String): Reason? {
        val number = Regex("\\b\\d{4,8}\\b").find(text)
        val otpWord = firstWord(text, otpWords)
        if (number != null && otpWord != null) {
            return Reason(
                ClassificationResult(MessageLabel.OTP_2FA, 0.9f),
                "It has a ${number.value.length}-digit number and the word \"$otpWord\", which is how verification codes are written.",
            )
        }

        val scamHit = firstWord(text, scamWords) ?: firstPhrase(text, scamPhrases)
        if (scamHit != null) {
            return Reason(
                ClassificationResult(MessageLabel.SCAM, 0.7f),
                "It uses the pressure wording \"$scamHit\", common in scam texts.",
            )
        }

        val promoHit = firstPhrase(text, promoPhrases)
        if (promoHit != null) {
            return Reason(
                ClassificationResult(MessageLabel.PROMO, 0.7f),
                "It contains \"$promoHit\", typical of marketing messages.",
            )
        }

        return null
    }
}
