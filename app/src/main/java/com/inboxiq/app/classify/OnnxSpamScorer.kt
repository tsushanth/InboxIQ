package com.inboxiq.app.classify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * The bundled bert-tiny SMS spam model (mrm8488/bert-tiny-finetuned-sms-spam-detection, exported to
 * ONNX, word-embedding table quantised to int8 per row; see scripts/classification/quantize_embeddings.py).
 * It only answers "does this look like classic spam?"; every other decision is made by [TriageEngine].
 *
 * Small on purpose: single-threaded, no memory arena, no memory-pattern planning. A 64-token input
 * through a 2-layer encoder does not need more, and a messaging app should not keep a thread pool or
 * an inference arena alive. Call [close] when the backlog is done.
 */
class OnnxSpamScorer(modelBytes: ByteArray, vocab: java.io.InputStream) : SpamScorer, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelBytes,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        },
    )
    private val tokenizer = WordPieceTokenizer(vocab)
    private val hasTokenTypes = session.inputNames.contains("token_type_ids")

    override fun spamProbability(text: String): Float {
        val encoded = tokenizer.encode(text)
        val shape = longArrayOf(1, encoded.inputIds.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape).use { attentionMask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { tokenTypeIds ->
                    val inputs = HashMap<String, OnnxTensor>(3)
                    inputs["input_ids"] = inputIds
                    inputs["attention_mask"] = attentionMask
                    if (hasTokenTypes) inputs["token_type_ids"] = tokenTypeIds
                    session.run(inputs).use { results ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (results[0].value as Array<FloatArray>)[0]
                        return softmaxSpamProb(logits)
                    }
                }
            }
        }
    }

    private fun softmaxSpamProb(logits: FloatArray): Float {
        val max = maxOf(logits[0], logits[1])
        val e0 = Math.exp((logits[0] - max).toDouble())
        val e1 = Math.exp((logits[1] - max).toDouble())
        return (e1 / (e0 + e1)).toFloat() // index 1 = spam, verified against the exported model's label order
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_ASSET = "spam_classifier_v1_q8.onnx"
        const val VOCAB_ASSET = "spam_classifier_vocab.txt"

        fun load(context: Context): OnnxSpamScorer =
            OnnxSpamScorer(context.assets.open(MODEL_ASSET).use { it.readBytes() }, context.assets.open(VOCAB_ASSET))
    }
}

/**
 * Loads the model on first use and can drop it again ([trim]) without invalidating the scorer, so a
 * backlog made of messages the rules can settle never pays for the model, and the process can give
 * the memory back on a trim-memory callback and reload it only if more work arrives.
 */
class LazySpamScorer(private val loader: () -> SpamScorer) : SpamScorer, AutoCloseable {
    private val lock = Any()
    private var delegate: SpamScorer? = null

    /** True once the model is resident. */
    val isLoaded: Boolean get() = synchronized(lock) { delegate != null }

    override fun spamProbability(text: String): Float = synchronized(lock) {
        val d = delegate ?: loader().also { delegate = it }
        d.spamProbability(text)
    }

    fun trim() = synchronized(lock) {
        (delegate as? AutoCloseable)?.close()
        delegate = null
    }

    override fun close() = trim()
}
