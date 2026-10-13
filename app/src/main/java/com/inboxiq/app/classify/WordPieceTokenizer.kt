package com.inboxiq.app.classify

import android.content.Context
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Minimal BERT-style WordPiece tokenizer (lowercase + basic punctuation
 * splitting + greedy longest-match wordpiece) — enough to match the
 * tokenizer the bundled bert-tiny spam model was trained/exported with,
 * without pulling in the full HuggingFace tokenizers native lib on Android.
 */
class WordPieceTokenizer(vocabStream: InputStream, private val maxLen: Int = 64) {

    constructor(context: Context, vocabAsset: String, maxLen: Int = 64) : this(context.assets.open(vocabAsset), maxLen)

    private val vocab: Map<String, Long>
    private val unkId: Long
    private val padId: Long

    init {
        val map = HashMap<String, Long>(40000)
        vocabStream.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).forEachLine { line ->
                if (line.isNotEmpty()) map[line] = map.size.toLong()
            }
        }
        vocab = map
        unkId = map["[UNK]"] ?: 100L
        padId = map["[PAD]"] ?: 0L
    }

    data class Encoded(val inputIds: LongArray, val attentionMask: LongArray, val tokenTypeIds: LongArray)

    fun encode(text: String): Encoded {
        val tokens = mutableListOf("[CLS]")
        tokens += wordpiece(basicTokenize(text))
        tokens.add("[SEP]")
        if (tokens.size > maxLen) {
            tokens.subList(maxLen - 1, tokens.size).clear()
            tokens.add("[SEP]")
        }

        val ids = LongArray(maxLen) { padId }
        val mask = LongArray(maxLen)
        for (i in tokens.indices) {
            ids[i] = vocab[tokens[i]] ?: unkId
            mask[i] = 1L
        }

        return Encoded(ids, mask, LongArray(maxLen))
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = PUNCT.replace(text.lowercase(), " $0 ")
        return cleaned.split(WHITESPACE).filter { it.isNotBlank() }
    }

    private fun wordpiece(words: List<String>): List<String> {
        val output = mutableListOf<String>()
        for (word in words) {
            if (word.length > 200) {
                output.add("[UNK]")
                continue
            }
            var start = 0
            var found = true
            val subTokens = mutableListOf<String>()
            while (start < word.length) {
                var end = word.length
                var current: String? = null
                while (start < end) {
                    var substr = word.substring(start, end)
                    if (start > 0) substr = "##$substr"
                    if (vocab.containsKey(substr)) {
                        current = substr
                        break
                    }
                    end--
                }
                if (current == null) {
                    found = false
                    break
                }
                subTokens.add(current)
                start = end
            }
            if (found) output.addAll(subTokens) else output.add("[UNK]")
        }
        return output
    }

    private companion object {
        val PUNCT = Regex("[\\p{Punct}]")
        val WHITESPACE = Regex("\\s+")
    }
}
