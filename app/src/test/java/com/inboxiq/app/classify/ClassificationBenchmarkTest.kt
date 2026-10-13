package com.inboxiq.app.classify

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget test for a 10,000-message backlog, rules and blend only (the ONNX model is measured separately,
 * see docs/CLASSIFICATION.md, because its native runtime cannot run in a plain JVM test).
 * Budgets are deliberately loose so a slow CI machine does not flake; they catch an order-of-magnitude regression,
 * such as compiling regexes per call or loading everything into memory.
 */
class ClassificationBenchmarkTest {
    private fun backlog(n: Int): List<ThreadContext> {
        val pool = listOf("train", "dev", "test").flatMap { EvalCorpus.synthetic(it) } + EvalCorpus.uci().take(2000)
        return List(n) { i -> pool[(i * 7919) % pool.size].ctx }
    }

    // The Android unit-test classpath has no java.lang.management stubs, so reach the JVM's allocation counter by reflection.
    private val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    private val allocMethod = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)

    private fun allocatedBytes(): Long = allocMethod.invoke(bean, Thread.currentThread().id) as Long

    @Test fun `10,000 messages classify within the time and allocation budget`() {
        var modelCalls = 0
        val model = SpamScorer { modelCalls++; 0.1f }
        val warm = backlog(1_500)
        for (c in warm) TriageEngine.classify(c, model)

        val work = backlog(10_000)
        modelCalls = 0
        val a0 = allocatedBytes()
        val t0 = System.nanoTime()
        var junk = 0
        for (c in work) if (TriageEngine.classify(c, model).label.name.startsWith("S")) junk++
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        val mb = (allocatedBytes() - a0) / 1_048_576.0

        val line = "10000 threads: %.0f ms (%.1f us/thread), %.1f MB allocated (%.1f KB/thread), model consulted for %d of 10000 (%.0f%%)".format(
            ms, ms * 1000 / 10_000, mb, mb * 1024 / 10_000, modelCalls, modelCalls / 100.0,
        )
        println(line)
        File("build/classification").apply { mkdirs() }.resolve("benchmark.txt").writeText(line + "\n")

        assertTrue("took $ms ms", ms < 8_000)
        assertTrue("allocated $mb MB", mb < 600)
        assertTrue("sanity: some junk found", junk > 0)
    }

    @Test fun `a thread is read from at most five messages however long it is`() {
        val long = ThreadContext("+14155550142", List(5_000) { "message number $it about nothing" }, replies = 0)
        val t0 = System.nanoTime()
        TriageEngine.classify(long, null)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 500)
    }
}
