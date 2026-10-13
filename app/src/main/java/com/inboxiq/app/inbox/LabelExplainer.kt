package com.inboxiq.app.inbox

import com.inboxiq.app.classify.HeuristicRules
import com.inboxiq.app.data.MessageLabel

/** Builds the plain-language answer to "why is this conversation filed here?". */
object LabelExplainer {
    data class Explanation(val headline: String, val reasons: List<String>)

    fun explain(
        label: MessageLabel,
        confidence: Float,
        userCorrected: Boolean,
        latestIncomingBody: String?,
        labelName: String,
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
        val rule = latestIncomingBody?.let { HeuristicRules.explain(it) }
        if (rule != null && rule.result.label == label) {
            return Explanation("Filed as $labelName by a keyword rule", listOf(rule.explanation, "This check runs on your phone and sends nothing anywhere."))
        }
        val pct = (confidence * 100).toInt().coerceIn(0, 100)
        val reasons = mutableListOf(
            "No keyword rule matched, so the model on your phone read the latest message and rated $labelName the most likely fit ($pct% sure).",
        )
        if (confidence < 0.6f) reasons += "That is a low score. If it looks wrong, change the label and InboxIQ will remember."
        reasons += "Labels apply to the whole sender, not one message."
        return Explanation("Filed as $labelName by the on-device model", reasons)
    }
}
