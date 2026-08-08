package com.inboxiq.app.classify

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.inboxiq.app.data.MessageLabel
import java.nio.LongBuffer

/**
 * v1 default-tier classifier. Runs a fine-tuned bert-tiny SMS spam model
 * (mrm8488/bert-tiny-finetuned-sms-spam-detection, exported to ONNX — see
 * /model/README.md) for the spam/ham signal, layered with lightweight
 * keyword rules for the categories the bundled model wasn't trained on
 * (OTP/2FA, promo, work vs. personal, scam-vs-spam split).
 *
 * Known limitation: the bundled model is trained on the 2005-era UK SMS
 * Spam Collection dataset, so it's strong on classic spam phrasing
 * ("txt FA to 87121") and weaker on modern scam/phishing language — that
 * gap is exactly what the heuristic layer and future fine-tuning pass are for.
 */
class OnnxEncoderClassifier(context: Context) : MessageClassifier {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        context.assets.open(MODEL_ASSET).readBytes(),
        OrtSession.SessionOptions(),
    )
    private val tokenizer = WordPieceTokenizer(context, VOCAB_ASSET)

    override suspend fun classify(text: String): ClassificationResult {
        val heuristic = HeuristicRules.match(text)
        if (heuristic != null) return heuristic

        val spamProb = runSpamModel(text)
        return if (spamProb > SPAM_THRESHOLD) {
            ClassificationResult(MessageLabel.SPAM, spamProb)
        } else {
            ClassificationResult(MessageLabel.PERSONAL, 1f - spamProb)
        }
    }

    private fun runSpamModel(text: String): Float {
        val encoded = tokenizer.encode(text)
        val shape = longArrayOf(1, encoded.inputIds.size.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape).use { attentionMask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { tokenTypeIds ->
                    val inputs = mutableMapOf(
                        "input_ids" to inputIds,
                        "attention_mask" to attentionMask,
                    )
                    if (session.inputNames.contains("token_type_ids")) {
                        inputs["token_type_ids"] = tokenTypeIds
                    }
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
        val max = logits.max()
        val exps = logits.map { Math.exp((it - max).toDouble()) }
        val sum = exps.sum()
        return (exps[1] / sum).toFloat() // index 1 = spam, verified against the exported model's label order
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val MODEL_ASSET = "spam_classifier_v1.onnx"
        private const val VOCAB_ASSET = "spam_classifier_vocab.txt"
        private const val SPAM_THRESHOLD = 0.6f
    }
}
