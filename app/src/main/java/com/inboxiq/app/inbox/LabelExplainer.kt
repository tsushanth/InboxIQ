package com.inboxiq.app.inbox

import com.inboxiq.app.classify.Basis
import com.inboxiq.app.classify.ThreadContext
import com.inboxiq.app.classify.TriageEngine
import com.inboxiq.app.data.MessageLabel

/**
 * Builds the plain-language answer to "why is this conversation filed here?".
 *
 * Truthfulness rule: every sentence shown must be a statement the classifier can back up. The rule
 * explanation re-runs [TriageEngine] on the same messages and sender information the classifier used,
 * and is only shown when that run lands on the stored label. When it does not (the label came from the
 * spam model, from an older version of the classifier, or from a different message), the sheet says so
 * instead of inventing a rule.
 */
object LabelExplainer {
    data class Explanation(val headline: String, val reasons: List<String>)

    fun explain(
        label: MessageLabel,
        confidence: Float,
        userCorrected: Boolean,
        latestIncomingBody: String?,
        labelName: String,
        /** The conversation's address, newest incoming messages (newest first) and the user's real replies, for the full rule check. */
        address: String? = null,
        recentIncoming: List<String> = listOfNotNull(latestIncomingBody),
        replies: Int = 0,
    ): Explanation {
        if (userCorrected) {
            return Explanation(
                "You filed this under $labelName",
                listOf("InboxIQ keeps your choice and files new texts from this number the same way."),
            )
        }
        if (label == MessageLabel.UNLABELED) {
            return Explanation(
                "Not sorted yet",
                listOf("Sorting runs in the background shortly after a message arrives. It happens on this phone, so it can take a moment."),
            )
        }
        val verdict = if (recentIncoming.isNotEmpty()) TriageEngine.classify(ThreadContext(address.orEmpty(), recentIncoming, replies), null) else null
        val pct = (confidence * 100).toInt().coerceIn(0, 100)

        if (verdict != null && verdict.basis == Basis.RULE && verdict.label == label) {
            val reasons = verdict.evidence.toMutableList()
            reasons += "This check runs on your phone and sends nothing anywhere."
            if (confidence < 0.6f) reasons += "That is a weak match. If it looks wrong, change the label and InboxIQ will remember."
            return Explanation("Filed as $labelName by a rule", reasons)
        }

        if (verdict != null && verdict.basis == Basis.RULE && verdict.label != label) {
            return Explanation(
                "Filed as $labelName by the on-device classifier",
                listOf(
                    "It was sorted earlier ($pct% sure). Checking the latest texts with today's rules would suggest ${verdict.label.name.lowercase().replace('_', ' ')} instead, so this label may be out of date.",
                    "If it looks wrong, change the label and InboxIQ will remember.",
                ),
            )
        }

        val reasons = mutableListOf<String>()
        if (label == MessageLabel.SPAM || label == MessageLabel.SCAM) {
            reasons += "No single rule was decisive; the spam model on your phone read the message and the overall score was $pct% for $labelName."
            verdict?.hints?.let { reasons += it }
        } else {
            reasons += "No rule matched and the on-device spam check did not flag it, so it stays with $labelName ($pct% sure)."
        }
        if (confidence < 0.6f) reasons += "That is a low score. If it looks wrong, change the label and InboxIQ will remember."
        reasons += "Labels apply to the whole sender, not one message."
        return Explanation("Filed as $labelName by the on-device model", reasons)
    }
}
