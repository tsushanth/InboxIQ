package com.inboxiq.app.classify

import org.junit.Test

class ClassificationEvalPrintTest {
    @Test fun print() {
        val fp32 = EvalCorpus.cachedScorer("classification/spam_probs_fp32.tsv")
        val q8 = EvalCorpus.cachedScorer("classification/spam_probs.tsv")
        val out = ClassificationEvalReport.render(listOf(ClassificationEvalReport.legacy(fp32), ClassificationEvalReport.current(q8)))
        ClassificationEvalReport.write("eval.md", out)
        println(out)
    }
}
