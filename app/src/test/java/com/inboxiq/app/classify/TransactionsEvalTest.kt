package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Transactions label on a synthetic set (src/test/resources/classification/transactions.tsv): India bank, UPI, card,
 * bill, EMI, delivery, ticket and appointment formats (English and Hinglish), US/UK transactional texts, and the tricky
 * negatives: bank OTPs, promos from the same senders, personal texts that say "paid" or "parcel", and look-alike scams.
 *
 * EVERY SAMPLE IS SYNTHETIC: written from public formats, with masked account numbers and made-up references. The rules and
 * the samples have the same author, so these numbers are a regression tripwire and an optimistic ceiling. They are NOT a
 * measure of real-world accuracy: real texts are messier, carriers and banks vary, and base rates differ.
 */
class TransactionsEvalTest {
    private val labels = EVAL_LABELS.take(0) + listOf(
        MessageLabel.TRANSACTION, MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.PROMO,
        MessageLabel.OTP_2FA, MessageLabel.SPAM, MessageLabel.SCAM,
    )
    private val rows = EvalCorpus.challenge("transactions")

    /** Misses that exist without this change (spam vs promo for loan pitches, "account" + "branch" read as work vocabulary). Kept in the report on purpose. */
    private val knownPreexisting = "known_preexisting"
    private val predict = ThreadPredictor { ctx -> TriageEngine.classify(ctx, null).label }

    private fun confusion(rs: List<LabelledThread>) = evaluate(rs, predict)

    @Test fun `resources load`() {
        assertTrue("transaction rules loaded: ${TextSignals.transactionRuleCount}", TextSignals.transactionRuleCount >= 30)
        assertTrue("india sender table loaded", IndiaSenders.loaded)
    }

    @Test fun `has at least 120 synthetic samples`() {
        assertTrue("n=${rows.size}", rows.size >= 120)
        assertTrue(rows.map { it.label }.toSet().containsAll(labels))
    }

    @Test fun `writes the report and prints every miss`() {
        val c = confusion(rows)
        val sb = StringBuilder()
        sb.appendLine(c.render("Transactions synthetic set (SYNTHETIC: not a measure of real-world accuracy)", labels))
        val misses = rows.filter { predict.predict(it.ctx) != it.label }
        sb.appendLine("Misses (${misses.size}):")
        for (m in misses) sb.appendLine("* [${m.category}] gold ${m.label} predicted ${predict.predict(m.ctx)}: ${m.ctx.address}: ${m.ctx.recentIncoming.first()}")
        ClassificationEvalReport.write("transactions-eval.md", sb.toString())
        println(sb)
    }

    /**
     * Written by a separate author (a second author who was given the label definitions and never saw the rules or
     * the code), also synthetic. The first run of the rules on it is recorded in docs/TRANSACTIONS.md before any rule was
     * changed in response; rules changed afterwards are noted there too.
     */
    @Test fun `blind set report`() {
        val blind = EvalCorpus.challenge("transactions_blind")
        val c = confusion(blind)
        val sb = StringBuilder()
        sb.appendLine(c.render("Blind synthetic set written without seeing the rules (SYNTHETIC: not a measure of real-world accuracy)", labels))
        val misses = blind.filter { predict.predict(it.ctx) != it.label }
        sb.appendLine("Misses (${misses.size}):")
        for (m in misses) sb.appendLine("* [${m.category}] gold ${m.label} predicted ${predict.predict(m.ctx)}: ${m.ctx.address}: ${m.ctx.recentIncoming.first()}")
        ClassificationEvalReport.write("transactions-blind-eval.md", sb.toString())
        println(sb)
        assertTrue("n=${blind.size}", blind.size >= 120)
    }

    @Test fun `transactions precision and recall floors`() {
        val c = confusion(rows)
        assertTrue("precision ${c.precision(MessageLabel.TRANSACTION)}", c.precision(MessageLabel.TRANSACTION) >= 0.95)
        assertTrue("recall ${c.recall(MessageLabel.TRANSACTION)}", c.recall(MessageLabel.TRANSACTION) >= 0.90)
    }

    @Test fun `bank OTP texts stay codes`() {
        for (r in rows.filter { it.category == "neg_otp" }) assertEquals(r.ctx.recentIncoming.first(), MessageLabel.OTP_2FA, predict.predict(r.ctx))
    }

    @Test fun `a bank alert that ends with a never-share warning is a transaction, not a code`() {
        for (r in rows.filter { it.category == "neg_warning" }) assertEquals(r.ctx.recentIncoming.first(), MessageLabel.TRANSACTION, predict.predict(r.ctx))
    }

    @Test fun `promos from the same senders stay promos`() {
        for (r in rows.filter { it.category == "neg_promo" }) assertEquals(r.ctx.recentIncoming.first(), MessageLabel.PROMO, predict.predict(r.ctx))
    }

    @Test fun `personal texts that mention paid, parcel or bill stay personal`() {
        for (r in rows.filter { it.category == "neg_personal" }) assertEquals(r.ctx.recentIncoming.first(), MessageLabel.PERSONAL, predict.predict(r.ctx))
    }

    @Test fun `look-alike scams and spam are never transactions`() {
        for (r in rows.filter { it.category == "neg_scam" || it.category == "neg_spam" }) {
            assertTrue(r.ctx.recentIncoming.first(), predict.predict(r.ctx) != MessageLabel.TRANSACTION)
        }
        val c = confusion(rows)
        assertEquals("scam recall", 1.0, c.recall(MessageLabel.SCAM), 0.0)
    }

    /** The older corpora, relabelled: automated-sender notices that were gold PERSONAL are now gold TRANSACTION. */
    @Test fun `old corpora - bank, delivery and appointment notices move to transactions and nothing else changes`() {
        val q8 = EvalCorpus.cachedScorer("classification/spam_probs.tsv")
        val sets = listOf(
            "synthetic dev" to EvalCorpus.synthetic("dev"), "synthetic test" to EvalCorpus.synthetic("test"),
            "challenge 1" to EvalCorpus.challenge("challenge"), "challenge 2" to EvalCorpus.challenge("challenge2"),
            "challenge 3" to EvalCorpus.challenge("challenge3"),
        )
        val failures = ArrayList<String>()
        val sb = StringBuilder("# Old corpora re-scored with TRANSACTION as a label (gold PERSONAL from an automated sender in a transactional category -> TRANSACTION)\n\n")
        for ((name, set) in sets) {
            val relabelled = set.map { t ->
                val txnCategory = t.category.startsWith("txn_")
                val automatedPersonal = name.startsWith("challenge") && t.label == MessageLabel.PERSONAL && t.ctx.sender.isAutomated
                if (t.label == MessageLabel.PERSONAL && (txnCategory || automatedPersonal)) t.copy(label = MessageLabel.TRANSACTION) else t
            }
            val c = evaluate(relabelled) { ctx -> TriageEngine.classify(ctx, q8).label }
            sb.appendLine(c.render(name, labels))
            // The notices must not leak into other labels: they are TRANSACTION or (thin evidence) PERSONAL, never junk/promo/code/work.
            val gold = relabelled.filter { it.label == MessageLabel.TRANSACTION }
            val stray = gold.filter { g -> TriageEngine.classify(g.ctx, q8).label !in setOf(MessageLabel.TRANSACTION, MessageLabel.PERSONAL) }
            val missed = gold.filter { TriageEngine.classify(it.ctx, q8).label != MessageLabel.TRANSACTION }
            sb.appendLine("$name: transaction gold ${gold.size}, missed ${missed.size}")
            missed.take(60).forEach { sb.appendLine("* ${it.ctx.address}: ${it.ctx.recentIncoming.first()}") }
            sb.appendLine()
            failures += listOfNotNull(
                "$name: ${stray.map { it.ctx.recentIncoming.first() to TriageEngine.classify(it.ctx, q8).label }}".takeIf { stray.isNotEmpty() },
                "$name transaction recall ${c.recall(MessageLabel.TRANSACTION)}".takeIf { gold.isNotEmpty() && c.recall(MessageLabel.TRANSACTION) < 0.85 },
            )
        }
        ClassificationEvalReport.write("transactions-old-corpora.md", sb.toString())
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `every OTP in the older corpora is still a code`() {
        val q8 = EvalCorpus.cachedScorer("classification/spam_probs.tsv")
        val bad = listOf("dev", "test", "train").flatMap { EvalCorpus.synthetic(it) }.filter { it.label == MessageLabel.OTP_2FA }
            .filter { TriageEngine.classify(it.ctx, q8).label != MessageLabel.OTP_2FA }
        assertTrue(bad.map { it.ctx.address + ": " + it.ctx.recentIncoming.first() + " -> " + TriageEngine.classify(it.ctx, q8).label }.distinct().joinToString("\n"), bad.isEmpty())
    }
}
