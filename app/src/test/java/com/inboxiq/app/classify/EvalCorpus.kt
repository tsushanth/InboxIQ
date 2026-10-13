package com.inboxiq.app.classify

import com.inboxiq.app.data.MessageLabel
import java.security.MessageDigest

/** One labelled conversation from the evaluation corpus. */
data class LabelledThread(
    val id: String,
    val category: String,
    val label: MessageLabel,
    val ctx: ThreadContext,
    val template: String,
)

/** Loads the test corpora from src/test/resources/classification. Everything here is public or synthetic data. */
object EvalCorpus {
    private fun lines(path: String): List<String> =
        EvalCorpus::class.java.classLoader!!.getResourceAsStream(path)!!.bufferedReader(Charsets.UTF_8).readLines()

    fun synthetic(split: String): List<LabelledThread> =
        lines("classification/$split.tsv").drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split('\t')
            LabelledThread(
                id = f[0], category = f[1], label = MessageLabel.valueOf(f[2]),
                ctx = ThreadContext(address = f[3], recentIncoming = f[5].split(" ||| "), replies = f[4].toInt()),
                template = f[0].substringBeforeLast('-'),
            )
        }

    /** Hand-written, free-form messages (not templated); evaluated once per rule change and never tuned against. */
    fun challenge(file: String = "challenge"): List<LabelledThread> =
        lines("classification/$file.tsv").drop(1).filter { it.isNotBlank() }.mapIndexed { i, line ->
            val f = line.split('\t')
            LabelledThread(
                id = "$file-$i", category = f[3], label = MessageLabel.valueOf(f[0]),
                ctx = ThreadContext(address = f[1], recentIncoming = listOf(f[4]), replies = f[2].toInt()),
                template = "$file-$i",
            )
        }

    /** UCI SMS Spam Collection. Ham is labelled PERSONAL, spam SPAM; there are no addresses, so the sender is unknown. */
    fun uci(): List<LabelledThread> =
        lines("classification/uci/SMSSpamCollection").filter { it.isNotBlank() }.mapIndexed { i, line ->
            val tab = line.indexOf('\t')
            LabelledThread(
                id = "uci-$i", category = "uci", label = if (line.substring(0, tab) == "spam") MessageLabel.SPAM else MessageLabel.PERSONAL,
                ctx = ThreadContext(address = "", recentIncoming = listOf(line.substring(tab + 1)), replies = 0),
                template = "uci",
            )
        }

    /** Fixed split of UCI: every 4th line is the dev half (used for calibration), the rest is held-out test. */
    fun uciDev(): List<LabelledThread> = uci().filterIndexed { i, _ -> i % 4 == 0 }
    fun uciTest(): List<LabelledThread> = uci().filterIndexed { i, _ -> i % 4 != 0 }

    /** bert-tiny output for every corpus text, produced offline by scripts/classification/score_spam_model.py. */
    fun cachedScorer(resource: String): SpamScorer {
        val table = HashMap<String, Float>(16000)
        lines(resource).forEach { l -> val t = l.indexOf('\t'); table[l.substring(0, t)] = l.substring(t + 1).toFloat() }
        return SpamScorer { text ->
            table[sha1(text)] ?: error("no cached spam score for text; rerun scripts/classification/build_eval_data.sh: $text")
        }
    }

    fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
