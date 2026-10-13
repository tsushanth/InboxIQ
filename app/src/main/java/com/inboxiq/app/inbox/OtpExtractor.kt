package com.inboxiq.app.inbox

/** A one-time code found in a message, with the moment it stops being worth offering to copy. */
data class OtpCode(val code: String, val expiresAt: Long) {
    fun isExpired(now: Long) = now >= expiresAt
}

object OtpExtractor {
    const val DEFAULT_VALIDITY_MS = 10L * 60_000

    private val keywords = Regex(
        """\b(code|otp|passcode|pass code|verification|verify|one[- ]time|security|login|log[- ]in|sign[- ]in|2fa|pin|authenticat\w*|confirmation)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val candidate = Regex("""(?<![\w$#.,+])(?<!\d-)(\d{3}[- ]\d{3}|\d{4,8})(?![\w%]|[.,]\d)""")
    private val validity = Regex(
        """(?:expires?|valid|good)\s*(?:in|for|within)?\s*(?:the next\s*)?(\d{1,3})\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(body: String, receivedAt: Long): OtpCode? {
        if (!keywords.containsMatchIn(body)) return null
        val kwRanges = keywords.findAll(body).map { it.range }.toList()

        var best: Pair<Int, String>? = null
        for (m in candidate.findAll(body)) {
            val raw = m.groupValues[1]
            val digits = raw.filter { it.isDigit() }
            if (digits.length !in 4..8) continue
            if (looksLikeYear(digits, body, m.range)) continue
            var score = 0
            // A keyword shortly before the number ("your code is 123456") is the strongest signal.
            if (kwRanges.any { m.range.first - it.last in 1..30 }) score += 3
            // "123456 is your verification code": keyword shortly after.
            if (kwRanges.any { it.first - m.range.last in 1..40 }) score += 2
            if (raw.contains('-') || raw.contains(' ')) score += 1
            if (digits.length == 6) score += 1
            if (score >= 2 && (best == null || score > best.first)) best = score to digits
        }
        val code = best?.second ?: return null
        return OtpCode(code, receivedAt + validityMs(body))
    }

    private fun looksLikeYear(digits: String, body: String, range: IntRange): Boolean {
        if (digits.length != 4) return false
        val n = digits.toInt()
        if (n !in 1900..2100) return false
        // A 4-digit "year" is only a year if nothing says it is a code.
        val before = body.substring(maxOf(0, range.first - 12), range.first).lowercase()
        return !(before.contains("code") || before.contains("otp") || before.contains("pin") || before.contains("is "))
    }

    fun validityMs(body: String): Long {
        val m = validity.find(body) ?: return DEFAULT_VALIDITY_MS
        val n = m.groupValues[1].toLongOrNull() ?: return DEFAULT_VALIDITY_MS
        val unit = m.groupValues[2].lowercase()
        val ms = when {
            unit.startsWith("sec") -> n * 1_000
            unit.startsWith("min") -> n * 60_000
            else -> n * 3_600_000
        }
        return ms.coerceIn(30_000L, 24L * 3_600_000)
    }
}
