package com.inboxiq.app.classify

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Minimal BERT-style WordPiece tokenizer (lowercase + basic punctuation
 * splitting + greedy longest-match wordpiece) — enough to match the
 * tokenizer the bundled bert-tiny spam model was trained/exported with,
 * without pulling in the full HuggingFace tokenizers native lib on Android.
 */
class WordPieceTokenizer(context: Context, vocabAsset: String, private val maxLen: Int = 64) {

    private val vocab: Map<String, Long>

    init {
        val map = HashMap<String, Long>()
        context.assets.open(vocabAsset).use { stream ->
            BufferedReader(InputStreamReader(stream)).forEachLine { line ->
                if (line.isNotEmpty()) map[line] = map.size.toLong()
            }
        }
        vocab = map
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

        val ids = tokens.map { vocab[it] ?: vocab["[UNK]"] ?: 100L }.toMutableList()
        val mask = MutableList(ids.size) { 1L }
        while (ids.size < maxLen) {
            ids.add(vocab["[PAD]"] ?: 0L)
            mask.add(0L)
        }

        return Encoded(ids.toLongArray(), mask.toLongArray(), LongArray(maxLen))
    }

    private fun basicTokenize(text: String): List<String> {
        val cleaned = text.lowercase()
            .replace(Regex("[\\p{Punct}]"), " $0 ")
        return cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
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
}
