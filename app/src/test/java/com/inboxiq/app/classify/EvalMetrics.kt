package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel

val EVAL_LABELS = listOf(
    MessageLabel.PERSONAL, MessageLabel.WORK, MessageLabel.PROMO, MessageLabel.OTP_2FA, MessageLabel.SPAM, MessageLabel.SCAM,
)

/** Confusion matrix of gold (rows) against predicted (columns, plus UNLABELED). */
class Confusion {
    private val cells = HashMap<Pair<MessageLabel, MessageLabel>, Int>()
    var total = 0
        private set

    fun add(gold: MessageLabel, pred: MessageLabel) {
        cells.merge(gold to pred, 1, Int::plus); total++
    }

    fun count(gold: MessageLabel, pred: MessageLabel) = cells[gold to pred] ?: 0
    fun goldTotal(gold: MessageLabel) = MessageLabel.entries.sumOf { count(gold, it) }
    fun predTotal(pred: MessageLabel) = MessageLabel.entries.sumOf { count(it, pred) }

    fun precision(l: MessageLabel) = predTotal(l).let { if (it == 0) 0.0 else count(l, l).toDouble() / it }
    fun recall(l: MessageLabel) = goldTotal(l).let { if (it == 0) 0.0 else count(l, l).toDouble() / it }
    fun f1(l: MessageLabel): Double {
        val p = precision(l); val r = recall(l)
        return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
    }

    fun macroF1(labels: List<MessageLabel> = EVAL_LABELS) = labels.map { f1(it) }.average()
    fun accuracy() = EVAL_LABELS.sumOf { count(it, it) }.toDouble() / total.coerceAtLeast(1)

    /** Share of gold-[gold] rows predicted as any of [preds]. */
    fun rate(gold: List<MessageLabel>, preds: Set<MessageLabel>): Double {
        val n = gold.sumOf { goldTotal(it) }
        if (n == 0) return 0.0
        return gold.sumOf { g -> preds.sumOf { count(g, it) } }.toDouble() / n
    }

    fun unlabeledRate() = predTotal(MessageLabel.UNLABELED).toDouble() / total.coerceAtLeast(1)

    fun render(title: String): String {
        val sb = StringBuilder()
        sb.appendLine("### $title (n=$total)")
        sb.appendLine()
        sb.appendLine("| label | precision | recall | F1 | support |")
        sb.appendLine("|---|---|---|---|---|")
        for (l in EVAL_LABELS) {
            if (goldTotal(l) == 0 && predTotal(l) == 0) continue
            sb.appendLine("| ${l.name} | ${"%.3f".format(precision(l))} | ${"%.3f".format(recall(l))} | ${"%.3f".format(f1(l))} | ${goldTotal(l)} |")
        }
        sb.appendLine("| macro F1 | | | ${"%.3f".format(macroF1(EVAL_LABELS.filter { goldTotal(it) > 0 }))} | |")
        sb.appendLine()
        val cols = EVAL_LABELS + MessageLabel.UNLABELED
        sb.appendLine("| gold \\ predicted | " + cols.joinToString(" | ") { it.name } + " |")
        sb.appendLine("|---|" + cols.joinToString("|") { "---" } + "|")
        for (g in EVAL_LABELS) {
            if (goldTotal(g) == 0) continue
            sb.appendLine("| ${g.name} | " + cols.joinToString(" | ") { count(g, it).toString() } + " |")
        }
        sb.appendLine()
        return sb.toString()
    }
}

fun interface ThreadPredictor {
    fun predict(ctx: ThreadContext): MessageLabel
}

fun evaluate(rows: List<LabelledThread>, p: ThreadPredictor): Confusion {
    val c = Confusion()
    for (r in rows) c.add(r.label, p.predict(r.ctx))
    return c
}
