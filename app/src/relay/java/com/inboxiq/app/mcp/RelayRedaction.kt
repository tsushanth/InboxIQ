package com.inboxiq.app.mcp

import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.MessageLabel

/**
 * Message text that leaves the phone over the Claude relay is passed through here first.
 * One-time passcodes are the one thing an AI agent (or a prompt-injected one) must never be
 * able to read back: with a code and a phone number, someone can take over an account. The
 * on-device inbox still shows the code to the user; only the relay copy is masked.
 */
object RelayRedaction {
    // 4-8 digits, or the common "123-456" / "123 456" grouping that OtpExtractor also treats as a code.
    private val code = Regex("\\b\\d{3}[- ]\\d{3}\\b|\\b\\d{4,8}\\b")
    const val MASK = "[code hidden]"

    fun redact(body: String): String =
        if (HeuristicRules.match(body)?.label == MessageLabel.OTP_2FA) code.replace(body, MASK) else body

    /**
     * True when [query] matches the text the agent is allowed to see. Searching the raw body would let an agent
     * confirm a hidden code one digit at a time (a hit means the guess was right), so a query is only
     * matched against the redacted text.
     */
    fun matchesVisible(body: String, query: String): Boolean = redact(body).contains(query, ignoreCase = true)
}
