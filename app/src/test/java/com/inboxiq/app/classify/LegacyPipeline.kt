package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/**
 * Frozen copy of the classification logic as shipped in integrate-0.5.0 (3bd45fb): the old keyword
 * rules, then the bert-tiny model with a 0.6 threshold, applied to the single latest message of a
 * thread (what ClassifyMessagesWorker did). Kept only so the baseline numbers in
 * docs/CLASSIFICATION.md stay reproducible; do not use it for anything else.
 */
object LegacyPipeline {
    private val otpWords = listOf("otp", "code", "verify", "verification")
    private val scamWords = listOf("irs")
    private val scamPhrases = listOf("act now", "claim your", "you've won", "you have won", "urgent action", "arrest warrant")
    private val promoPhrases = listOf("% off", "unsubscribe", "reply stop", "promo code", "limited time")
    private const val SPAM_THRESHOLD = 0.6f

    private fun firstWord(text: String, words: List<String>) =
        words.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    private fun firstPhrase(text: String, phrases: List<String>) = phrases.firstOrNull { text.contains(it, ignoreCase = true) }

    fun rules(text: String): ClassificationResult? {
        val number = Regex("\\b\\d{4,8}\\b").find(text)
        if (number != null && firstWord(text, otpWords) != null) return ClassificationResult(MessageLabel.OTP_2FA, 0.9f)
        if ((firstWord(text, scamWords) ?: firstPhrase(text, scamPhrases)) != null) return ClassificationResult(MessageLabel.SCAM, 0.7f)
        if (firstPhrase(text, promoPhrases) != null) return ClassificationResult(MessageLabel.PROMO, 0.7f)
        return null
    }

    fun classifyText(text: String, spam: SpamScorer): ClassificationResult {
        rules(text)?.let { return it }
        val p = spam.spamProbability(text)
        return if (p > SPAM_THRESHOLD) ClassificationResult(MessageLabel.SPAM, p) else ClassificationResult(MessageLabel.PERSONAL, 1f - p)
    }

    fun classifyThread(ctx: ThreadContext, spam: SpamScorer): ClassificationResult =
        classifyText(ctx.recentIncoming.first(), spam)
}
