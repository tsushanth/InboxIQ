package com.inboxiq.app.reply

import com.inboxiq.app.classify.BareReplies
import com.inboxiq.app.classify.Relationship
import com.inboxiq.app.classify.SenderProfile
import com.inboxiq.app.data.MessageLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One message of a conversation, oldest first in [ReplyRequest.messages]. No Android types. */
data class ThreadMsg(
    val body: String,
    val isIncoming: Boolean,
    val timestamp: Long = 0,
    val hasImage: Boolean = false,
    val label: MessageLabel = MessageLabel.UNLABELED,
)

data class ReplyRequest(
    val address: String,
    /** The thread's cached label, if any (the last incoming message carries its own too). */
    val threadLabel: MessageLabel = MessageLabel.UNLABELED,
    val messages: List<ThreadMsg>,
    /** Bodies of texts the user sent to anyone, recent first or last (order does not matter). Local only. */
    val globalSent: List<String> = emptyList(),
    /** The number is a saved contact. */
    val isKnownContact: Boolean = false,
)

data class ReplyResult(
    val intent: ReplyIntent,
    val sub: ReplySub = ReplySub.NONE,
    val suggestions: List<String> = emptyList(),
    val needsReply: Boolean = false,
    val suppressed: Suppression? = null,
    /** Suggestions that are the user's own words. */
    val personalized: Int = 0,
)

interface ReplySuggester {
    suspend fun suggest(req: ReplyRequest): ReplyResult
}

/** The default tier: rules only, off the main thread, a few milliseconds. */
object RulesReplySuggester : ReplySuggester {
    override suspend fun suggest(req: ReplyRequest): ReplyResult = withContext(Dispatchers.Default) { SmartReply.analyze(req) }
}

object SmartReply {

    /** Latency budget for the rules tier on a normal thread (asserted in tests). */
    const val RULES_BUDGET_MS = 50L

    const val MAX_SUGGESTIONS = 3
    private const val TAIL = 4

    /** What the thread screen and the inbox row call. */
    fun analyze(req: ReplyRequest, withSuggestions: Boolean = true): ReplyResult {
        val msgs = req.messages
        val last = msgs.lastOrNull() ?: return ReplyResult(ReplyIntent.EMPTY, suppressed = Suppression.NOT_INCOMING)
        if (!last.isIncoming) return ReplyResult(ReplyIntent.EMPTY, suppressed = Suppression.NOT_INCOMING)

        val replies = msgs.count { !it.isIncoming && it.body.isNotBlank() && !BareReplies.isBare(it.body) }
        val known = req.isKnownContact
        val labels = listOf(last.label, req.threadLabel)

        ReplyGate.senderCheck(req.address, labels)?.let { return ReplyResult(ReplyIntent.EMPTY, suppressed = it) }

        // The unanswered tail: what they sent since the user last wrote, newest last.
        val lastOut = msgs.indexOfLast { !it.isIncoming }
        val tail = msgs.subList(lastOut + 1, msgs.size).takeLast(TAIL)

        val chosen: IntentClassifier.Result
        if (ReplyGate.isBlankOrPlaceholder(last.body)) {
            if (!last.hasImage) return ReplyResult(ReplyIntent.EMPTY, suppressed = Suppression.ATTACHMENT_ONLY)
            val relationship = Relationship.of(SenderProfile.of(req.address), replies)
            // A photo from someone you have never talked to is not worth a "Nice!".
            if (relationship == Relationship.NONE && !known) return ReplyResult(ReplyIntent.EMPTY, suppressed = Suppression.ATTACHMENT_ONLY)
            chosen = IntentClassifier.Result(ReplyIntent.SHARE, ReplySub.PHOTO)
        } else {
            ReplyGate.textCheck(last.body, req.address, replies, known)?.let { return ReplyResult(ReplyIntent.EMPTY, suppressed = it) }
            val usable = tail.filter { m ->
                !ReplyGate.isBlankOrPlaceholder(m.body) && (m === last || ReplyGate.textCheck(m.body, req.address, replies, known) == null)
            }
            val classified = usable.map { IntentClassifier.classify(it.body) }
            // The newest text that needs an answer, else the newest text.
            // A bare nudge ("ok?", "hello??") does not outrank the real ask above it.
            val demanding = usable.zip(classified).filter { it.second.intent.needsReply }
            val real = demanding.filter { (m, _) -> m.body.trim().length > 8 }
            chosen = (real.lastOrNull() ?: demanding.lastOrNull())?.second ?: classified.lastOrNull() ?: IntentClassifier.classify(last.body)
        }

        val intent = chosen.intent
        if (!intent.offersReplies && !intent.needsReply) {
            return ReplyResult(intent, chosen.sub, needsReply = false, suppressed = Suppression.NO_REPLY_NEEDED)
        }
        if (!withSuggestions) return ReplyResult(intent, chosen.sub, needsReply = intent.needsReply)

        val outgoing = msgs.filter { !it.isIncoming }.map { it.body }
        val incomingRecent = msgs.filter { it.isIncoming }.takeLast(6).map { it.body }
        val style = UserStyle(outgoing, req.globalSent, incomingRecent)
        val seed = last.body.lowercase().hashCode()
        val built = Composer.compose(chosen, style, seed)
        return ReplyResult(
            intent = intent,
            sub = chosen.sub,
            suggestions = built.texts,
            needsReply = intent.needsReply,
            personalized = built.personalized,
        )
    }

    /** Cheap version for the inbox list: no wording, just the decision. */
    fun needsReply(req: ReplyRequest): Boolean = analyze(req, withSuggestions = false).needsReply
}

/** Turns an intent into up to three short replies in the user's voice. */
internal object Composer {
    class Built(val texts: List<String>, val personalized: Int)

    private val personalisable = setOf(
        Stance.AFFIRM, Stance.DECLINE, Stance.DEFER, Stance.ACK, Stance.THANKS, Stance.WELCOME, Stance.NOWORRIES,
        Stance.CONGRATS, Stance.SYMPATHY, Stance.LOVE, Stance.GREET, Stance.BYE, Stance.ETA,
    )

    fun compose(r: IntentClassifier.Result, style: UserStyle, seed: Int): Built {
        val opts = if (r.intent == ReplyIntent.EITHER_OR) eitherOr(r.options) else ReplyBank.options(r.intent, r.sub)
        val serious = r.intent == ReplyIntent.BAD_NEWS || r.sub == ReplySub.MONEY
        val out = ArrayList<String>(3)
        val usedStance = HashSet<Stance>()
        var personalized = 0
        var emojiBudget = if (style.emojiRate >= 0.5) 3 else 1

        for ((i, o) in opts.withIndex()) {
            val own = if (o.stance in personalisable && o.stance !in usedStance && !serious) style.phrase(o.stance) else null
            var text: String
            if (own != null) {
                text = own
                usedStance += o.stance
                personalized++
            } else {
                text = applyStyle(o.pick(style.tone, seed + i * 7), style, o.stance)
                if (o.warm && !serious && style.usesEmoji && emojiBudget > 0 && !UserStyle.EMOJI.containsMatchIn(text)) {
                    style.favouriteEmoji?.let { text = "$text $it"; emojiBudget-- }
                }
            }
            if (!ReplySafety.ok(text, r.intent, r.sub)) continue
            if (out.none { it.equals(text, ignoreCase = true) }) out += text
            if (out.size == SmartReply.MAX_SUGGESTIONS) break
        }
        return Built(out, personalized)
    }

    private fun eitherOr(pair: Pair<String, String>?): List<Opt> {
        val either = Opt(Stance.OTHER, listOf("Either is fine with me."), listOf("Either works for me", "Either one is fine with me"), listOf("Either works for me!", "I'm good with either"))
        if (pair == null) return listOf(
            either,
            Opt(Stance.OTHER, listOf("I will leave it to you."), listOf("You pick", "I'll leave it up to you"), listOf("You pick!", "Your call")),
            Opt(Stance.DEFER, listOf("Let me think about it."), listOf("Let me think about it"), listOf("Lemme think about it")),
        )
        val a = pair.first.replaceFirstChar { it.uppercase() }
        val b = pair.second.replaceFirstChar { it.uppercase() }
        return listOf(
            Opt(Stance.OTHER, listOf("$a would suit me."), listOf("$a works for me"), listOf("$a for me!", "$a works for me")),
            Opt(Stance.OTHER, listOf("$b would suit me."), listOf("$b works for me"), listOf("$b for me!", "$b works for me")),
            either,
        )
    }

    private val shouted = Regex("!+")
    private val standaloneI = Regex("\\bI(?=['\\s])")

    fun applyStyle(text: String, style: UserStyle, stance: Stance): String {
        var t = text
        if (style.avoidsExclamation && stance != Stance.CONGRATS && stance != Stance.GREET && stance != Stance.LOVE) {
            t = t.replace(shouted, "")
        }
        if (style.dropsFinalPeriod) t = t.trimEnd('.')
        if (style.startsLowercase) {
            if (style.lowercaseI) t = t.replace(standaloneI, "i")
            val startsWithI = t.startsWith("I ") || t.startsWith("I'")
            if (!startsWithI || style.lowercaseI) t = t.replaceFirstChar { it.lowercase() }
        }
        return t.trim()
    }
}

/** Last line of defence for anything shown as a one-tap reply, whatever produced it. */
object ReplySafety {
    private val url = Regex("https?://|\\bwww\\.|\\b[a-z0-9-]+\\.(com|org|net|io|co|app|me|ly|link|gl|page)\\b", RegexOption.IGNORE_CASE)
    private val digits = Regex("\\d{3,}")
    private val codeWords = Regex("\\b(code|otp|pin|passcode|password|verification|ssn|social security|cvv|routing|account number|card number)\\b", RegexOption.IGNORE_CASE)
    private val money = Regex("[$€£]\\s?\\d|\\b(venmo|zelle|paypal|cash ?app|gift ?card|wire)\\b", RegexOption.IGNORE_CASE)
    private val emoji = UserStyle.EMOJI

    const val MAX_LEN = 80

    fun ok(text: String, intent: ReplyIntent = ReplyIntent.STATEMENT, sub: ReplySub = ReplySub.NONE): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return false
        if (t.contains('\n') || t.contains('@') || url.containsMatchIn(t) || digits.containsMatchIn(t)) return false
        if (codeWords.containsMatchIn(t) || money.containsMatchIn(t)) return false
        if (intent == ReplyIntent.BAD_NEWS && (t.contains('!') || emoji.containsMatchIn(t))) return false
        if (sub == ReplySub.MONEY && StanceMatcher.of(t) == Stance.AFFIRM) return false
        return true
    }
}

/**
 * Optional on-device generative tier. [ReplyModel] is whatever small model is installed; null or a
 * model that is slow, throws or returns nonsense simply falls back to the rules. The model is only asked
 * about messages the rules already decided deserve a reply, so it can never be handed an OTP, a promo, a
 * scam or an automated sender, and every line it returns goes through [ReplySafety].
 *
 * No production [ReplyModel] is wired in this build (see docs/SMART_REPLY.md): the interface and its
 * guarantees are tested with a fake.
 */
fun interface ReplyModel {
    suspend fun generate(prompt: String): String?
}

class GenerativeReplySuggester(
    private val model: ReplyModel?,
    private val rules: ReplySuggester = RulesReplySuggester,
    private val timeoutMs: Long = 600,
) : ReplySuggester {

    override suspend fun suggest(req: ReplyRequest): ReplyResult {
        val base = rules.suggest(req)
        val m = model ?: return base
        if (base.suppressed != null || base.suggestions.isEmpty() || !base.intent.needsReply) return base
        val generated = try {
            withTimeoutOrNull(timeoutMs) { m.generate(prompt(req, base)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        } ?: return base

        val lines = generated.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"', '“', '”', ' ') }
            .filter { ReplySafety.ok(it, base.intent, base.sub) }
            .distinctBy { it.lowercase() }
            .take(2)
        if (lines.isEmpty()) return base
        val merged = (lines + base.suggestions).distinctBy { it.lowercase() }.take(SmartReply.MAX_SUGGESTIONS)
        return base.copy(suggestions = merged)
    }

    /** Only the last few turns; the model sees nothing else and nothing leaves the phone. */
    internal fun prompt(req: ReplyRequest, base: ReplyResult): String = buildString {
        appendLine("Write up to 3 short SMS replies (under 8 words each, one per line, no links, no numbers, no codes).")
        appendLine("The other person's message is a ${base.intent.name.lowercase().replace('_', ' ')}.")
        for (m in req.messages.takeLast(4)) appendLine((if (m.isIncoming) "Them: " else "Me: ") + m.body.take(240))
        append("Me:")
    }
}
