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
    private val code = Regex("\\b\\d{4,8}\\b")
    const val MASK = "[code hidden]"

    fun redact(body: String): String =
        if (HeuristicRules.match(body)?.label == MessageLabel.OTP_2FA) code.replace(body, MASK) else body
}
