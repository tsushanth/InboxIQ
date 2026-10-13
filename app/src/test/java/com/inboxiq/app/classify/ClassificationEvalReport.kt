package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import java.io.File

/** Runs the pipelines over the corpora and renders the markdown that docs/CLASSIFICATION.md quotes. */
object ClassificationEvalReport {
    val junk = setOf(MessageLabel.SPAM, MessageLabel.SCAM)
    val human = listOf(MessageLabel.PERSONAL, MessageLabel.WORK)

    class Run(val name: String, val predictor: ThreadPredictor)

    fun legacy(spam: SpamScorer) = Run("baseline (integrate-0.5.0)") { ctx -> LegacyPipeline.classifyThread(ctx, spam).label }
    fun current(spam: SpamScorer) = Run("current") { ctx -> TriageEngine.classify(ctx, spam).label }

    fun uciBinary(rows: List<LabelledThread>, p: ThreadPredictor): String {
        val c = evaluate(rows, p)
        val hamOther = EVAL_LABELS.filter { it != MessageLabel.PERSONAL }.joinToString(", ") { "${it.name} ${c.count(MessageLabel.PERSONAL, it)}" }
        val hamFpJunk = c.rate(listOf(MessageLabel.PERSONAL), junk)
        val hamFpPromo = c.rate(listOf(MessageLabel.PERSONAL), setOf(MessageLabel.PROMO))
        val spamRecall = c.rate(listOf(MessageLabel.SPAM), junk + MessageLabel.PROMO)
        return "ham n=${c.goldTotal(MessageLabel.PERSONAL)}, spam n=${c.goldTotal(MessageLabel.SPAM)}: ham filed SPAM/SCAM ${"%.2f".format(hamFpJunk * 100)}%, " +
            "ham filed PROMO ${"%.2f".format(hamFpPromo * 100)}%, ham counts [$hamOther], spam caught as SPAM/SCAM/PROMO ${"%.1f".format(spamRecall * 100)}%"
    }

    fun render(runs: List<Run>, splits: List<String> = listOf("dev", "test")): String {
        val sb = StringBuilder()
        for (run in runs) {
            sb.appendLine("## ${run.name}")
            sb.appendLine()
            for (split in splits) {
                val rows = EvalCorpus.synthetic(split)
                val c = evaluate(rows, run.predictor)
                sb.append(c.render("synthetic $split"))
                sb.appendLine("Junk false-positive rate on PERSONAL+WORK threads: SPAM ${"%.2f".format(c.rate(human, setOf(MessageLabel.SPAM)) * 100)}%, " +
                    "SCAM ${"%.2f".format(c.rate(human, setOf(MessageLabel.SCAM)) * 100)}%. Unlabeled: ${"%.1f".format(c.unlabeledRate() * 100)}%. " +
                    "Distinct templates in split: ${rows.map { it.template }.toSet().size}.")
                sb.appendLine()
            }
            sb.append(evaluate(EvalCorpus.challenge(), run.predictor).render("hand-written challenge set 1 (rules were tuned after seeing it)"))
            val c2 = evaluate(EvalCorpus.challenge("challenge2"), run.predictor)
            sb.append(c2.render("hand-written challenge set 2 (first pass before the sales-pitch rule: macro F1 0.794; this run is after it, so it was tuned against)"))
            sb.appendLine("Junk false-positive rate on PERSONAL+WORK, challenge 2: SPAM ${"%.2f".format(c2.rate(human, setOf(MessageLabel.SPAM)) * 100)}%, SCAM ${"%.2f".format(c2.rate(human, setOf(MessageLabel.SCAM)) * 100)}%.")
            sb.appendLine()
            val c3 = evaluate(EvalCorpus.challenge("challenge3"), run.predictor)
            sb.append(c3.render("hand-written challenge set 3 (written last, evaluated once, never tuned against)"))
            sb.appendLine("Junk false-positive rate on PERSONAL+WORK, challenge 3: SPAM ${"%.2f".format(c3.rate(human, setOf(MessageLabel.SPAM)) * 100)}%, SCAM ${"%.2f".format(c3.rate(human, setOf(MessageLabel.SCAM)) * 100)}%.")
            sb.appendLine()
            sb.appendLine("UCI dev half: " + uciBinary(EvalCorpus.uciDev(), run.predictor))
            sb.appendLine()
            sb.appendLine("UCI held-out test (3/4): " + uciBinary(EvalCorpus.uciTest(), run.predictor))
            sb.appendLine()
            sb.appendLine("UCI all 5,574: " + uciBinary(EvalCorpus.uci(), run.predictor))
            sb.appendLine()
        }
        return sb.toString()
    }

    fun write(name: String, text: String) {
        val dir = File("build/classification").apply { mkdirs() }
        File(dir, name).writeText(text)
    }
}
