package com.inboxiq.app.classify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The JVM evaluation reads model scores computed offline with a Python port of the tokenizer; this pins the two to each other. */
class WordPieceTokenizerGoldenTest {
    @Test fun `kotlin tokenizer matches the python port used to score the corpora`() {
        val tok = WordPieceTokenizer(File("src/main/assets/spam_classifier_vocab.txt").inputStream())
        val golden = EvalGolden.lines()
        assertEquals(9, golden.size)
        for ((text, ids) in golden) {
            assertEquals(text, ids, tok.encode(text).inputIds.toList())
        }
    }

    private object EvalGolden {
        fun lines(): List<Pair<String, List<Long>>> =
            EvalCorpus::class.java.classLoader!!.getResourceAsStream("classification/tokenizer_golden.tsv")!!
                .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }.map { l ->
                    val t = l.lastIndexOf('\t')
                    l.substring(0, t).replace("\\t", "\t").replace("\\n", "\n") to l.substring(t + 1).split(',').map { it.toLong() }
                }
    }
}
