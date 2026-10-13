package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression gates for the shipped pipeline, and a frozen record of how bad the old one was.
 * Also writes build/classification/eval.md, the source of the tables in docs/CLASSIFICATION.md.
 *
 * Read docs/CLASSIFICATION.md before trusting these numbers: the synthetic splits were written by the
 * same author as the rules, so they are an optimistic ceiling. Challenge set 3 and the UCI corpus are the
 * numbers to believe; challenge sets were hand-written, free-form, and evaluated once each.
 */
class ClassificationEvalTest {
    private val fp32 = EvalCorpus.cachedScorer("classification/spam_probs_fp32.tsv")
    private val q8 = EvalCorpus.cachedScorer("classification/spam_probs.tsv")
    private val legacy = ClassificationEvalReport.legacy(fp32)
    private val current = ClassificationEvalReport.current(q8)
    private val junk = setOf(MessageLabel.SPAM, MessageLabel.SCAM)
    private val human = ClassificationEvalReport.human

    private fun eval(rows: List<LabelledThread>, run: ClassificationEvalReport.Run) = evaluate(rows, run.predictor)

    @Test fun `writes the evaluation report`() {
        ClassificationEvalReport.write("eval.md", ClassificationEvalReport.render(listOf(legacy, current)))
    }

    @Test fun `old pipeline never produced WORK and almost never SCAM, as seen on the owner phone`() {
        val c = eval(EvalCorpus.synthetic("test"), legacy)
        assertEquals("old pipeline had no WORK output", 0, MessageLabel.entries.sumOf { c.count(it, MessageLabel.WORK) })
        assertTrue("old SCAM recall was ${c.recall(MessageLabel.SCAM)}", c.recall(MessageLabel.SCAM) < 0.15)
        assertTrue("old pipeline filed >10% of personal/work threads as spam", c.rate(human, setOf(MessageLabel.SPAM)) > 0.10)
        assertTrue(c.macroF1() in 0.35..0.5)
    }

    @Test fun `WORK and SCAM are produced and correct on every set`() {
        for ((name, rows) in allSets()) {
            val c = eval(rows, current)
            assertTrue("$name WORK predicted", c.predTotal(MessageLabel.WORK) > 0)
            assertTrue("$name SCAM predicted", c.predTotal(MessageLabel.SCAM) > 0)
            assertTrue("$name WORK precision ${c.precision(MessageLabel.WORK)}", c.precision(MessageLabel.WORK) >= 0.8)
            assertTrue("$name SCAM precision ${c.precision(MessageLabel.SCAM)}", c.precision(MessageLabel.SCAM) >= 0.85)
            assertEquals("$name nothing left unlabeled", 0, c.predTotal(MessageLabel.UNLABELED))
        }
    }

    @Test fun `synthetic test split floors`() {
        val c = eval(EvalCorpus.synthetic("test"), current)
        for (l in EVAL_LABELS) assertTrue("$l F1 ${c.f1(l)}", c.f1(l) >= 0.95)
        assertTrue(c.rate(human, junk) <= 0.005)
    }

    @Test fun `hand-written challenge set 3 floors`() {
        val base = eval(EvalCorpus.challenge("challenge3"), legacy)
        val c = eval(EvalCorpus.challenge("challenge3"), current)
        assertTrue("macro F1 ${c.macroF1()}", c.macroF1() >= 0.75)
        assertTrue("macro F1 must beat the old pipeline by 0.3, ${c.macroF1()} vs ${base.macroF1()}", c.macroF1() >= base.macroF1() + 0.3)
        assertTrue("WORK recall ${c.recall(MessageLabel.WORK)}", c.recall(MessageLabel.WORK) >= 0.5)
        assertTrue("SCAM recall ${c.recall(MessageLabel.SCAM)}", c.recall(MessageLabel.SCAM) >= 0.6)
        assertTrue("SPAM precision ${c.precision(MessageLabel.SPAM)}", c.precision(MessageLabel.SPAM) >= 0.75)
        assertTrue(c.rate(human, junk) <= 0.02)
    }

    @Test fun `hand-written challenge sets 1 and 2 floors (tuned against, so only a regression tripwire)`() {
        assertTrue(eval(EvalCorpus.challenge("challenge"), current).macroF1() >= 0.90)
        assertTrue(eval(EvalCorpus.challenge("challenge2"), current).macroF1() >= 0.80)
    }

    @Test fun `junk false positives on personal and work text went down on every set`() {
        for ((name, rows) in allSets()) {
            val before = eval(rows, legacy).rate(human, junk)
            val after = eval(rows, current).rate(human, junk)
            assertTrue("$name junk FPR $after vs old $before", after <= before)
        }
        assertTrue(eval(EvalCorpus.synthetic("test"), legacy).rate(human, junk) > 0.10)
    }

    @Test fun `UCI SMS Spam Collection - ham is not filed as junk and spam is still mostly caught`() {
        val all = EvalCorpus.uci()
        val c = eval(all, current)
        val before = eval(all, legacy)
        val hamJunk = c.rate(listOf(MessageLabel.PERSONAL), junk)
        val hamJunkBefore = before.rate(listOf(MessageLabel.PERSONAL), junk)
        assertTrue("ham filed as spam/scam $hamJunk, old $hamJunkBefore", hamJunk <= 0.001 && hamJunk < hamJunkBefore)
        val recall = c.rate(listOf(MessageLabel.SPAM), junk + MessageLabel.PROMO)
        val recallBefore = before.rate(listOf(MessageLabel.SPAM), junk + MessageLabel.PROMO)
        assertTrue("UCI spam recall $recall (old $recallBefore)", recall >= 0.75)
        // conversational ham must not be turned into codes, promos or work
        assertTrue(c.count(MessageLabel.PERSONAL, MessageLabel.OTP_2FA) == 0 && c.count(MessageLabel.PERSONAL, MessageLabel.PROMO) <= 2)
        assertTrue(c.count(MessageLabel.PERSONAL, MessageLabel.WORK) <= 5)
    }

    private fun allSets() = listOf(
        "synthetic dev" to EvalCorpus.synthetic("dev"),
        "synthetic test" to EvalCorpus.synthetic("test"),
        "challenge 1" to EvalCorpus.challenge("challenge"),
        "challenge 2" to EvalCorpus.challenge("challenge2"),
        "challenge 3" to EvalCorpus.challenge("challenge3"),
    )
}
