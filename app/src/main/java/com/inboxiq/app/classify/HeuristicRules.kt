package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/**
 * Keyword and structure rules, checked before (and often instead of) the ONNX spam model. This is a
 * thin, sender-agnostic view of [TriageEngine]; the full pipeline also uses the sender type and the
 * user's reply history, which a bare message text does not carry.
 *
 * All matching uses word boundaries, never substring `contains`: a real v1 bug matched the SCAM
 * keyword "irs" inside "First Tech Federal Credit Union" and mislabelled a legitimate bank alert.
 *
 * [match] only returns a label when a rule decided on the text alone (code, scam, work, promo),
 * not for the model-based or default outcomes. SmsDeliverReceiver relies on that: it lets this
 * override a cached thread label for a single incoming message.
 */
object HeuristicRules {

    /** The rule that fired, in words the user can check against the message. */
    data class Reason(val result: ClassificationResult, val explanation: String)

    fun match(text: String): ClassificationResult? = evaluate(text)?.result

    /** Same decision as [match], plus why. */
    fun explain(text: String): Reason? = evaluate(text)

    private fun evaluate(text: String): Reason? {
        val v = TriageEngine.rulesOnly(text)
        if (v.basis != Basis.RULE) return null
        // A text-only view cannot tell a transactional notice from an ordinary message: leave that to the full pipeline.
        if (v.label == MessageLabel.PERSONAL || v.label == MessageLabel.SPAM) return null
        return Reason(v.toResult(), v.evidence.joinToString(" "))
    }
}
