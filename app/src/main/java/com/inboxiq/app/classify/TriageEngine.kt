package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

/** What the classifier knows about one conversation. [recentIncoming] is newest first. */
data class ThreadContext(
    val address: String,
    val recentIncoming: List<String>,
    /** The user's own substantive replies (bare STOP/YES do not count). */
    val replies: Int = 0,
) {
    val sender: SenderProfile get() = SenderProfile.of(address)
}

/** Which kind of evidence decided a verdict. */
enum class Basis { RULE, RULE_AND_MODEL, MODEL, DEFAULT }

data class Verdict(
    val label: MessageLabel,
    val confidence: Float,
    val basis: Basis,
    /** Plain-language reasons, true statements about the message. Shown in "Why was this labelled?". */
    val evidence: List<String>,
    /** Weak signals that were seen but did not decide. */
    val hints: List<String> = emptyList(),
    /** True when the spam model was consulted. */
    val usedModel: Boolean = false,
) {
    fun toResult() = ClassificationResult(label, confidence)
}

/** Probability (0..1) that [text] is spam. Implementations: OnnxSpamScorer on device, a cached table in tests. */
fun interface SpamScorer {
    fun spamProbability(text: String): Float
}

/**
 * The rule layer plus the blend with the spam model. Pure Kotlin so it runs in JVM tests.
 *
 * Order of decisions, per message (see docs/CLASSIFICATION.md for the reasoning):
 *   1. OTP/2FA code  2. SCAM  3. WORK / PROMO  4. transactional notice (kept as PERSONAL)
 *   5. SPAM (text cues blended with the model)  6. PERSONAL by default.
 * Relationship (did the user really talk to this number?) raises the bar for SCAM and SPAM and
 * switches the spam model off for known contacts.
 */
object TriageEngine {
    // Tuned on dev + UCI dev; see docs/CLASSIFICATION.md.
    internal const val SCAM_THRESHOLD = 2.0f
    internal const val SCAM_THRESHOLD_WEAK = 2.5f
    internal const val SCAM_THRESHOLD_STRONG = 3.0f
    internal const val PROMO_THRESHOLD = 1.7f
    internal const val WORK_THRESHOLD = 1.4f
    internal const val SPAM_MODEL_ALONE = 0.90f
    internal const val SPAM_MODEL_WITH_CUE = 0.5f

    private val weights = doubleArrayOf(1.0, 0.8, 0.6, 0.5, 0.4)

    /** Rules only (no model). Used by [HeuristicRules] and by the explainer. */
    fun rulesOnly(text: String, sender: SenderProfile = SenderProfile.UNKNOWN, replies: Int = 0): Verdict =
        classifyMessage(text, sender, replies, null)

    fun classify(ctx: ThreadContext, spam: SpamScorer?): Verdict {
        val bodies = ctx.recentIncoming.filter { it.isNotBlank() }.take(weights.size)
        if (bodies.isEmpty()) return Verdict(MessageLabel.PERSONAL, 0.4f, Basis.DEFAULT, listOf("There is no text to read, so it stays in Personal."))
        val sender = ctx.sender
        val verdicts = bodies.map { classifyMessage(it, sender, ctx.replies, spam) }
        if (verdicts.size == 1) return verdicts[0]
        // Recency-weighted vote; newest message counts most.
        val score = HashMap<MessageLabel, Double>()
        // SCAM is boosted so one clear scam message among older normal ones (a hijacked friend) is not outvoted.
        verdicts.forEachIndexed { i, v -> score.merge(v.label, weights[i] * v.confidence * (if (v.label == MessageLabel.SCAM) 1.6 else 1.0), Double::plus) }
        val winner = score.maxByOrNull { it.value }!!.key
        val pick = verdicts.filter { it.label == winner }.maxByOrNull { it.confidence }!!
        return pick.copy(usedModel = verdicts.any { it.usedModel })
    }

    fun classifyMessage(text: String, sender: SenderProfile, replies: Int, spam: SpamScorer?): Verdict {
        if (text.isBlank()) return Verdict(MessageLabel.PERSONAL, 0.4f, Basis.DEFAULT, listOf("There is no text to read, so it stays in Personal."))
        val rel = Relationship.of(sender, replies)
        val sig = TextSignals.analyze(text, sender)
        val senderNote = "It came from ${sender.description}."
        val contact = if (rel == Relationship.STRONG) "You have replied to this number $replies times, so InboxIQ treats it as a person you know." else null

        val scamScore = sig.scam.sumOf { it.weight.toDouble() }.toFloat() - if (sig.hasOptOut) 0.7f else 0f
        val scamThreshold = when (rel) {
            Relationship.NONE -> SCAM_THRESHOLD
            Relationship.WEAK -> SCAM_THRESHOLD_WEAK
            Relationship.STRONG -> SCAM_THRESHOLD_STRONG
        }
        val scamGroups = sig.scam.map { it.group }.toSet().size
        // Pressure wording, a shortened link and an odd sender are only supporting evidence: a scam needs some real content signal.
        val scamContent = sig.scam.filter { it.group != "urgent" && it.group != "sender" && !(it.group == "link" && it.weight < 1.3f) }.sumOf { it.weight.toDouble() }.toFloat()
        val scamHit = scamScore >= scamThreshold && scamContent >= 0.8f && (rel != Relationship.STRONG || scamGroups >= 2)

        // 1. One-time codes
        val otp = sig.otp
        if (otp != null && !(rel == Relationship.STRONG && !otp.strong) && !(scamHit && scamScore >= 3.5f)) {
            val why = "It has a ${otp.digits}-digit number and the wording \"${otp.word}\", which is how verification codes are written."
            return Verdict(MessageLabel.OTP_2FA, if (otp.strong) 0.95f else 0.85f, Basis.RULE, listOf(why))
        }

        // 2. Scam
        if (scamHit) {
            val ev = sig.scam.sortedByDescending { it.weight }.take(3).map { it.why } + listOfNotNull(senderNote.takeIf { !sender.isAutomated })
            val conf = (0.55f + 0.1f * (scamScore - scamThreshold)).coerceIn(0.55f, 0.95f)
            return Verdict(MessageLabel.SCAM, conf, Basis.RULE, ev.distinct())
        }
        val scamHints = if (scamScore >= 1.0f) sig.scam.sortedByDescending { it.weight }.take(2).map { it.why } else emptyList()

        // 3. Work / promo
        val workScore = sig.work.sumOf { it.weight.toDouble() }.toFloat() 
        var promoScore = sig.promo.sumOf { it.weight.toDouble() }.toFloat() + (if (sig.hasOptOut) 1.0f else 0f) +
            (if (sender.kind == SenderKind.SHORT_CODE) 0.5f else 0f)
        if (sig.promo.isEmpty()) promoScore = minOf(promoScore, PROMO_THRESHOLD - 0.1f) // opt-out alone is not marketing
        val promoHit = promoScore >= PROMO_THRESHOLD && rel != Relationship.STRONG
        val workHit = workScore >= WORK_THRESHOLD
        if (workHit && !(promoHit && promoScore >= workScore)) {
            val ev = sig.work.sortedByDescending { it.weight }.take(2).map { it.why } + listOfNotNull(contact)
            val conf = (0.6f + 0.1f * (workScore - WORK_THRESHOLD)).coerceIn(0.6f, 0.92f)
            return Verdict(MessageLabel.WORK, conf, Basis.RULE, ev)
        }
        if (promoHit) {
            val ev = mutableListOf<String>()
            ev += sig.promo.sortedByDescending { it.weight }.take(2).map { it.why }
            if (sig.hasOptOut) ev += "It includes opt-out wording (\"reply STOP\"), which bulk senders must add."
            if (sender.kind == SenderKind.SHORT_CODE) ev += senderNote
            return Verdict(MessageLabel.PROMO, (0.6f + 0.1f * (promoScore - PROMO_THRESHOLD)).coerceIn(0.6f, 0.9f), Basis.RULE, ev)
        }

        // 4. Account / delivery / appointment notices stay in Personal, and must never be called spam.
        val txnScore = sig.txn.sumOf { it.weight.toDouble() }.toFloat()
        val spamTextScore = sig.spam.sumOf { it.weight.toDouble() }.toFloat()
        if (txnScore >= 1.0f && spamTextScore < 1.5f && (sender.isAutomated || rel == Relationship.STRONG || spamTextScore == 0f)) {
            val ev = listOf(sig.txn.first().why + ", so it is kept in Personal and not treated as junk.") + listOfNotNull(senderNote.takeIf { sender.isAutomated })
            return Verdict(MessageLabel.PERSONAL, 0.75f, Basis.RULE, ev, scamHints)
        }

        // 4b. A short code or named sender that is neither a code nor an account notice, but has a link, an opt-out or a
        // marketing cue, is almost always a business broadcasting: file it as Promo rather than guess Personal.
        if ((sender.kind == SenderKind.SHORT_CODE || sender.kind == SenderKind.ALPHA_ID) && txnScore == 0f && spamTextScore < 1.5f &&
            (sig.links.isNotEmpty() || sig.promo.isNotEmpty() || sig.hasOptOut)
        ) {
            val ev = listOf("It came from ${sender.description} and reads like a broadcast (a link, an opt-out or a marketing phrase), not a personal or account message.") +
                sig.promo.take(1).map { it.why }
            return Verdict(MessageLabel.PROMO, 0.6f, Basis.RULE, ev, scamHints)
        }

        // 5. Spam: text cues blended with the model. Never for a number the user really talks to.
        if (rel == Relationship.STRONG) {
            return Verdict(MessageLabel.PERSONAL, 0.9f, Basis.RULE, listOfNotNull(contact), scamHints)
        }
        val p = spam?.spamProbability(text)?.takeUnless { it.isNaN() } ?: -1f
        val cueSpam = if (rel == Relationship.WEAK) {
            spamTextScore >= 2.0f || (spamTextScore >= 1.5f && p >= 0.5f)
        } else {
            spamTextScore >= 1.5f || (spamTextScore >= 0.5f && p >= SPAM_MODEL_WITH_CUE)
        }
        val modelSpam = p >= SPAM_MODEL_ALONE + (if (rel == Relationship.WEAK) 0.05f else 0f)
        val spamForAutomated = false
        if (cueSpam || modelSpam || spamForAutomated) {
            val ev = mutableListOf<String>()
            ev += sig.spam.sortedByDescending { it.weight }.take(2).map { it.why }
            if (p >= 0f) ev += "The on-device spam model rated it ${(p * 100).toInt()}% likely to be spam."
            if (!sender.isAutomated) ev += senderNote
            val basis = if (p >= 0f && sig.spam.isNotEmpty()) Basis.RULE_AND_MODEL else if (sig.spam.isNotEmpty()) Basis.RULE else Basis.MODEL
            val conf = if (p >= 0f) (0.5f + 0.5f * p).coerceIn(0.55f, 0.95f) else 0.65f
            return Verdict(MessageLabel.SPAM, conf, basis, ev, usedModel = p >= 0f)
        }

        // 6. Default
        val conf = if (p >= 0f) (1f - p).coerceIn(0.5f, 0.95f) else 0.6f
        val hints = scamHints + sig.spam.take(1).map { it.why }.filter { spamTextScore >= 0.5f }
        return Verdict(MessageLabel.PERSONAL, conf, if (p >= 0f) Basis.MODEL else Basis.DEFAULT, listOfNotNull(contact), hints, usedModel = p >= 0f)
    }
}
