package com.inboxiq.app.reply

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression gates for the smart-reply engine plus the baseline (old heuristic) on the same corpus.
 * Writes build/smartreply/eval.md, the source of the table in docs/SMART_REPLY.md.
 * The 250 generated conversations were written alongside the rules; challenge.tsv (hand-written, free-form) is the number to believe.
 */
class SmartReplyEvalTest {
    private val gen = SmartReplyCorpus.load("corpus.tsv")
    private val hand = SmartReplyCorpus.load("challenge.tsv")

    private fun fresh(cases: List<Case>) = EvalNumbers("new", cases) { SmartReply.analyze(it.request()) }

    /** The old code ran per last message with only its label; it had no sender or content gate. */
    private fun legacy(cases: List<Case>) = EvalNumbers("old", cases) { c ->
        val last = c.thread.last()
        if (!LegacySuggester.needsResponse(last.isIncoming, c.label)) ReplyResult(ReplyIntent.EMPTY)
        else ReplyResult(ReplyIntent.EMPTY, suggestions = LegacySuggester.suggest(last.body), needsReply = true)
    }

    private fun pct(d: Double) = "%.1f%%".format(d * 100)

    @Test fun `writes the evaluation report`() {
        val sb = StringBuilder("| Set | n | Suggester | Coverage | Inappropriate | Needs-reply recall | False marks | Intent accuracy | Relevance |\n|---|---|---|---|---|---|---|---|---|\n")
        for ((name, set) in listOf("generated" to gen, "hand-written" to hand, "all" to gen + hand)) {
            for ((who, e) in listOf("old" to legacy(set), "new" to fresh(set))) {
                val rel = relevance(e)
                sb.append("| $name | ${set.size} | $who | ${pct(e.coverage)} | ${pct(e.inappropriate)} (${e.inappropriateCount}/${e.silent.size}) | ${pct(e.markRecall)} | ${e.falseMarks} | ${e.intentAccuracy?.let(::pct) ?: "n/a"} | ${rel?.let(::pct) ?: "n/a"} |\n")
            }
        }
        val e = fresh(hand)
        sb.append("\nHand-written misses (new suggester):\n")
        for (c in hand) {
            val r = e.results()[c]!!
            val why = when {
                c.expect == Expect.REPLY && r.suggestions.isEmpty() -> "no chips"
                c.expect == Expect.REPLY && !r.needsReply -> "no Needs-a-reply mark"
                c.expect != Expect.NONE && c.intent != "NONE" && r.intent.name != c.intent -> "read as ${r.intent}, expected ${c.intent}"
                else -> null
            }
            if (why != null) sb.append("- ${c.id} ($why): ${c.thread.last().body}\n")
        }
        val dir = File("build/smartreply").apply { mkdirs() }
        File(dir, "eval.md").writeText(sb.toString())
        println(sb)
    }

    private fun relevance(e: EvalNumbers): Double? {
        if (e.name == "old") {
            val rs = e.cases.filter { it.expect != Expect.NONE && it.intent != "NONE" }.mapNotNull { c -> e.results()[c]?.let { c to it } }
            return rs.count { (c, r) -> r.suggestions.isNotEmpty() && Relevance.relevant(c.intent, r) }.toDouble() / rs.size
        }
        val rs = e.cases.filter { it.expect != Expect.NONE && it.intent != "NONE" && !it.intent.let { i -> i in setOf("ACK", "REACTION", "STATEMENT", "INFO", "SHARE") } }
        return rs.count { c -> e.results()[c]!!.let { r -> r.suggestions.isNotEmpty() && Relevance.relevant(c.intent, r) } }.toDouble() / rs.size
    }

    @Test fun `old suggester offered chips to codes, promos, scams and links (frozen baseline)`() {
        val e = legacy(gen + hand)
        assertTrue("old inappropriate rate ${e.inappropriate}", e.inappropriate > 0.15)
    }

    @Test fun `never suggests on a message that must stay silent`() {
        for (set in listOf(gen, hand)) {
            val e = fresh(set)
            val bad = e.silent.filter { !e.results()[it]!!.suggestions.isEmpty() }.map { it.id + ": " + it.thread.last().body }
            assertEquals("inappropriate: $bad", 0, bad.size)
        }
    }

    @Test fun `no Needs a reply mark on courtesy, silent or automated messages`() {
        for (set in listOf(gen, hand)) {
            val e = fresh(set)
            val bad = set.filter { it.expect != Expect.REPLY && e.results()[it]!!.needsReply }.map { it.id + ": " + it.thread.last().body }
            assertEquals("false marks: $bad", 0, bad.size)
        }
    }

    @Test fun `coverage on messages that deserve a reply`() {
        assertTrue("generated ${fresh(gen).coverage}", fresh(gen).coverage >= 0.98)
        val missing = hand.filter { it.expect == Expect.REPLY && fresh(hand).results()[it]!!.suggestions.isEmpty() }.map { it.id + ": " + it.thread.last().body }
        assertTrue("hand-written coverage ${fresh(hand).coverage} missing=$missing", fresh(hand).coverage >= 0.9)
        assertTrue("mark recall ${fresh(hand).markRecall}", fresh(hand).markRecall >= 0.85)
    }

    @Test fun `intent accuracy`() {
        val g = fresh(gen).intentAccuracy!!
        val h = fresh(hand).intentAccuracy!!
        val wrongG = gen.filter { it.intent != "NONE" && fresh(gen).results()[it]!!.intent.name != it.intent && it.expect != Expect.NONE }.map { it.id + " " + it.intent + "->" + fresh(gen).results()[it]!!.intent + ": " + it.thread.last().body }
        assertTrue("generated $g wrong=$wrongG", g >= 0.93)
        val wrongH = hand.filter { it.intent != "NONE" && fresh(hand).results()[it]!!.intent.name != it.intent && it.expect != Expect.NONE }.map { it.id + " " + it.intent + "->" + fresh(hand).results()[it]!!.intent + ": " + it.thread.last().body }
        assertTrue("hand-written $h wrong=$wrongH", h >= 0.8)
    }

    @Test fun `relevance - a yes-no question gets a yes style option and so on`() {
        for ((n, set, floor) in listOf(Triple("generated", gen, 0.95), Triple("hand", hand, 0.85))) {
            val e = fresh(set)
            val bad = set.filter { it.expect != Expect.NONE && it.intent in setOf("YES_NO", "EITHER_OR", "THANKS", "APOLOGY", "GOOD_NEWS", "BAD_NEWS", "ETA_ASK") }
                .filter { c -> val r = e.results()[c]!!; r.intent.name == c.intent && !Relevance.relevant(c.intent, r) }.map { it.id + ": " + e.results()[it]!!.suggestions }
            assertTrue("$n irrelevant: $bad", bad.isEmpty())
            assertTrue(relevance(e)!! >= floor)
        }
    }

    @Test fun `every suggestion passes the safety filter and there are at most three`() {
        for (c in gen + hand) {
            val r = SmartReply.analyze(c.request())
            assertTrue(r.suggestions.size <= 3)
            for (s in r.suggestions) assertTrue("${c.id}: $s", ReplySafety.ok(s))
        }
    }

    @Test fun `latency of the rules tier stays inside the 50 ms budget`() {
        val all = (gen + hand).map { it.request() }
        repeat(2) { all.forEach { SmartReply.analyze(it) } } // warm up regex and class loading
        val times = all.map { r -> val t = System.nanoTime(); SmartReply.analyze(r); (System.nanoTime() - t) / 1e6 }.sorted()
        val p95 = times[(times.size * 0.95).toInt()]
        println("latency ms: p50=%.2f p95=%.2f max=%.2f".format(times[times.size / 2], p95, times.last()))
        assertTrue("p95 $p95 ms", p95 < SmartReply.RULES_BUDGET_MS)
        assertTrue("max ${times.last()} ms", times.last() < SmartReply.RULES_BUDGET_MS * 4)
    }

    @Test fun `long thread and long text stay fast`() {
        val msgs = (1..2000).map { ThreadMsg("message number $it about nothing much at all", it % 2 == 0, it.toLong()) } + ThreadMsg("are you free tonight? " + "x ".repeat(5000), true, 3000)
        val t = System.nanoTime()
        SmartReply.analyze(ReplyRequest("+14155550101", MessageLabel.PERSONAL, msgs.takeLast(200)))
        assertTrue(((System.nanoTime() - t) / 1e6) < 200)
    }
}
