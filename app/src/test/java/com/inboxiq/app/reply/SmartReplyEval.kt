package com.inboxiq.app.reply

import com.inboxiq.app.data.MessageLabel

/** The suggester this replaced, frozen verbatim as the baseline (heuristic from the 0.4 line). */
object LegacySuggester {
    private val respondable = setOf(MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.UNLABELED)
    fun needsResponse(incoming: Boolean, label: MessageLabel) = incoming && label in respondable
    fun suggest(body: String): List<String> {
        val text = body.lowercase()
        return when {
            text.contains("?") && listOf("can you", "could you", "would you", "will you").any { text.contains(it) } -> listOf("Yes", "Not right now", "Let me check and get back to you")
            text.contains("?") -> listOf("Yes", "No", "Let me get back to you")
            listOf("thank", "thanks").any { text.contains(it) } -> listOf("You're welcome!", "No problem", "Anytime")
            listOf("when", "schedule", "available", "meet").any { text.contains(it) } -> listOf("Works for me", "Let me check my calendar", "Can we do another time?")
            else -> listOf("Got it", "Sounds good", "Thanks!")
        }
    }
}

enum class Expect { REPLY, OPTIONAL, NONE }

data class Case(
    val id: String, val intent: String, val expect: Expect, val tone: String, val address: String,
    val label: MessageLabel, val known: Boolean, val thread: List<ThreadMsg>, val set: String,
) {
    fun request() = ReplyRequest(address, label, thread, globalSent = emptyList(), isKnownContact = known)
    /** The legacy path: label of the last message, last incoming body. */
    val last get() = thread.last()
}

object SmartReplyCorpus {
    private fun lines(path: String) = SmartReplyCorpus::class.java.classLoader!!.getResourceAsStream(path)!!.bufferedReader(Charsets.UTF_8).readLines()

    fun load(file: String): List<Case> = lines("smartreply/$file").drop(1).filter { it.isNotBlank() }.map { line ->
        val f = line.split('\t')
        val label = MessageLabel.valueOf(f[5])
        var t = 1_000_000L
        val msgs = f[8].split(" ||| ").map { raw ->
            val incoming = raw.startsWith("<")
            val body = raw.drop(2).trim()
            t += 60_000
            when (body) {
                "[img]" -> ThreadMsg("", incoming, t, hasImage = true, label = label)
                "[mms]" -> ThreadMsg("[MMS message]", incoming, t, label = label)
                "[blank]" -> ThreadMsg("", incoming, t, label = label)
                else -> ThreadMsg(body, incoming, t, label = if (incoming) label else MessageLabel.PERSONAL)
            }
        }
        Case(f[0], f[1], Expect.valueOf(f[2]), f[3], f[4], label, f[6] == "1", msgs, file)
    }

    fun all() = load("corpus.tsv") + load("challenge.tsv")
}

class EvalNumbers(val name: String, val cases: List<Case>, val run: (Case) -> ReplyResult?) {
    val needing = cases.filter { it.expect == Expect.REPLY }
    val silent = cases.filter { it.expect == Expect.NONE }
    private val results = cases.associateWith { run(it) }

    /** Share of messages that deserve a reply and got at least one chip. */
    val coverage get() = needing.count { !results[it]?.suggestions.isNullOrEmpty() }.toDouble() / needing.size
    /** Share of must-be-silent messages that got a chip (target 0). */
    val inappropriate get() = silent.count { !results[it]?.suggestions.isNullOrEmpty() }.toDouble() / silent.size
    val inappropriateCount get() = silent.count { !results[it]?.suggestions.isNullOrEmpty() }
    /** Needs-a-reply mark: recall on REPLY cases, and false marks on NONE/OPTIONAL cases. */
    val markRecall get() = needing.count { results[it]?.needsReply == true }.toDouble() / needing.size
    val falseMarks get() = cases.filter { it.expect != Expect.REPLY }.count { results[it]?.needsReply == true }
    val intentAccuracy: Double? get() {
        val withIntent = cases.filter { it.intent != "NONE" && it.expect != Expect.NONE || it.intent in setOf("ACK", "REACTION", "STATEMENT") }
        val ok = withIntent.count { results[it]?.intent?.name == it.intent }
        return if (name == "old") null else ok.toDouble() / withIntent.size
    }
    fun results() = results
}

/** Rule-based relevance: does the offered set contain the kind of answer this intent calls for? */
object Relevance {
    fun relevant(intent: String, r: ReplyResult): Boolean {
        val stances = r.suggestions.mapNotNull { StanceMatcher.of(it) }.toSet()
        val s = r.suggestions
        return when (intent) {
            "YES_NO" -> Stance.AFFIRM in stances || s.any { Regex("^i'?m (ok|okay|fine|good)", RegexOption.IGNORE_CASE).containsMatchIn(it) } || s.any { Regex("^(yes|yeah|yep|sure|i'm in|i'd love|that works|works for me|sounds)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "EITHER_OR" -> s.any { Regex("either|pick|choice|call|would suit|works for me|for me|think", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "SCHEDULING" -> s.any { Regex("work|check|time|another|reschedule|tomorrow|week|confirm", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "THANKS" -> Stance.WELCOME in stances
            "APOLOGY" -> Stance.NOWORRIES in stances || Stance.THANKS in stances
            "GOOD_NEWS" -> Stance.CONGRATS in stances
            "BAD_NEWS" -> Stance.SYMPATHY in stances && s.none { it.contains('!') }
            "ETA_ASK" -> Stance.ETA in stances
            "GREETING" -> Stance.GREET in stances || s.any { it.contains("how", true) }
            "CHECK_IN" -> s.any { Regex("you\\?|how|good|well", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "REMINDER" -> s.any { Regex("thank|confirm|reschedule|be there", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "CONGRATS_RECEIVED" -> Stance.THANKS in stances
            "AFFECTION" -> Stance.LOVE in stances
            "FAREWELL" -> Stance.BYE in stances
            else -> s.isNotEmpty()
        }
    }
}
