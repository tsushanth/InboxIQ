package com.inboxiq.app.reactions

/**
 * The text a reaction travels as over plain SMS, in both directions. Pure Kotlin.
 *
 * SMS has no reaction object, so a reaction is an ordinary text that quotes the original:
 *
 *  - the six iPhone tapbacks use the iPhone wording: `Loved “…”`, `Liked “…”`, `Disliked “…”`,
 *    `Laughed at “…”`, `Emphasized “…”`, `Questioned “…”`; removal is `Removed a heart from “…”` and so on.
 *  - every other emoji uses the form Google Messages writes on SMS: a zero-width space (U+200B) either
 *    side of the emoji, ` to `, curly quotes, and a hair space (U+200A) inside each quote.
 *    Removal is `Removed ` + the emoji wrapped in zero-width non-joiners (U+200C) + ` from “…”`.
 *
 * Curly quotes, emoji and the invisible characters force UCS-2, where one SMS segment holds 70 UTF-16
 * units. [compose] and [composeRemoval] therefore size the quote so the WHOLE text, invisible characters
 * included, stays inside one segment. The quote never exceeds [MAX_QUOTE_UNITS], never splits a
 * character a person sees as one (surrogate pairs, skin tones, ZWJ sequences, flags), and ends in `…`
 * when it was cut.
 *
 * [parse] reads the same forms back (and the iOS 18 `Reacted 😂 to “…”`), which is the seam incoming
 * reaction handling builds on. It is also what keeps a reaction text from being offered a reaction.
 */
object ReactionText {
    /** One UCS-2 SMS segment. */
    const val SEGMENT_UNITS = 70

    /** Upper bound for the quoted original, in UTF-16 units, including a trailing ellipsis. */
    const val MAX_QUOTE_UNITS = 50

    const val ELLIPSIS = "…"
    private const val LQ = "“"
    private const val RQ = "”"
    private const val ZWSP = "​"
    private const val ZWNJ = "‌"
    private const val HAIR = " "

    /** Shown instead of a quote when the message has no text to quote. */
    private const val NO_TEXT = "a message"

    /** The text that adds [emoji] as a reaction to a message whose text is [targetBody]. */
    fun compose(emoji: String, targetBody: String): String {
        val tapback = ReactionEmoji.tapback(emoji)
        val prefix: String
        val suffix: String
        if (tapback != null) {
            prefix = "${tapback.addVerb} $LQ"
            suffix = RQ
        } else {
            prefix = "$ZWSP$emoji$ZWSP to $LQ$HAIR"
            suffix = "$HAIR$RQ"
        }
        return build(prefix, suffix, targetBody, noQuote = if (tapback != null) "${tapback.addVerb} $NO_TEXT" else "$ZWSP$emoji$ZWSP to $NO_TEXT")
    }

    /** The text that takes [emoji] back off a message. It cannot unsend the earlier text; it only tells apps that read it. */
    fun composeRemoval(emoji: String, targetBody: String): String {
        val tapback = ReactionEmoji.tapback(emoji)
        val prefix: String
        val suffix: String
        if (tapback != null) {
            prefix = "Removed ${tapback.removedNoun} from $LQ"
            suffix = RQ
        } else {
            prefix = "Removed $ZWNJ$emoji$ZWNJ from $LQ$HAIR"
            suffix = "$HAIR$RQ"
        }
        val noQuote = if (tapback != null) "Removed ${tapback.removedNoun} from $NO_TEXT" else "Removed $ZWNJ$emoji$ZWNJ from $NO_TEXT"
        return build(prefix, suffix, targetBody, noQuote)
    }

    /** How many UTF-16 units of quote fit after [prefix] and before [suffix] in one segment. Never below one. */
    internal fun quoteBudget(prefix: String, suffix: String): Int =
        (SEGMENT_UNITS - prefix.length - suffix.length).coerceIn(1, MAX_QUOTE_UNITS)

    private fun build(prefix: String, suffix: String, targetBody: String, noQuote: String): String {
        val quote = normalize(targetBody)
        if (quote.isEmpty()) return noQuote
        return prefix + truncate(quote, quoteBudget(prefix, suffix)) + suffix
    }

    /** Collapses whitespace and newlines to single spaces and drops the invisible markers this format uses itself. */
    internal fun normalize(body: String): String =
        body.replace(INVISIBLE, "").replace(WHITESPACE, " ").trim()

    /** Cuts [text] to at most [maxUnits] UTF-16 units on a character boundary, ending in an ellipsis when it was cut. */
    internal fun truncate(text: String, maxUnits: Int): String {
        if (text.length <= maxUnits) return text
        val room = maxUnits - ELLIPSIS.length
        val sb = StringBuilder()
        for (cluster in GraphemeClusters.split(text)) {
            if (sb.length + cluster.length > room) break
            sb.append(cluster)
        }
        return sb.toString().trimEnd() + ELLIPSIS
    }

    private val WHITESPACE = Regex("[\\s\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+")
    private val INVISIBLE = Regex("[\\u200A\\u200B\\u200C\\uFEFF]")

    // ------------------------------------------------------------------------------ reading

    /** Which wording a reaction text used. */
    enum class Style { IPHONE_TAPBACK, IPHONE_EMOJI, GOOGLE, PLAIN }

    /**
     * One reaction text, read back. [emoji] is the reaction as an emoji (an iPhone tapback maps to its emoji),
     * [quoted] is the quoted original without the quote marks, hair spaces or a trailing ellipsis marker
     * ([truncated] says it was there), and [removed] is true for the "Removed ..." forms.
     */
    data class Parsed(
        val emoji: String,
        val quoted: String,
        val removed: Boolean,
        val style: Style,
        val truncated: Boolean,
    )

    private val OPEN = "[“\"„]"
    private val CLOSE = "[”\"“]"
    private val opts = setOf(RegexOption.DOT_MATCHES_ALL)

    private val appleAdd = Regex("^(Loved|Liked|Disliked|Laughed at|Emphasi[sz]ed|Questioned) $OPEN(.*)$CLOSE$", opts)
    private val appleRemove = Regex("^Removed (a|an) (heart|like|dislike|laugh|exclamation mark|exclamation|question mark) from $OPEN(.*)$CLOSE$", opts)
    private val iosEmojiAdd = Regex("^Reacted (\\S+) to $OPEN(.*)$CLOSE$", opts)
    private val googleAdd = Regex("^​?([^\\s​‌]+?)​? to $OPEN(.*)$CLOSE$", opts)
    private val googleRemove = Regex("^Removed ‌?([^\\s​‌]+?)‌? from $OPEN(.*)$CLOSE$", opts)

    /** Null when [body] is not, as a whole, a reaction text. Text that merely contains such words stays ordinary text. */
    fun parse(body: String): Parsed? {
        val text = body.trim()
        if (text.length < 8) return null
        appleAdd.matchEntire(text)?.let { m ->
            val tb = tapbackFor(m.groupValues[1]) ?: return null
            return parsed(tb.emoji, m.groupValues[2], removed = false, style = Style.IPHONE_TAPBACK)
        }
        appleRemove.matchEntire(text)?.let { m ->
            val tb = tapbackForNoun(m.groupValues[2]) ?: return null
            return parsed(tb.emoji, m.groupValues[3], removed = true, style = Style.IPHONE_TAPBACK)
        }
        iosEmojiAdd.matchEntire(text)?.let { m ->
            if (isEmojiToken(m.groupValues[1])) return parsed(m.groupValues[1], m.groupValues[2], removed = false, style = Style.IPHONE_EMOJI)
        }
        // Google marks removals with U+200C, so the generic "Removed X from" is read before the add forms.
        googleRemove.matchEntire(text)?.let { m ->
            if (isEmojiToken(m.groupValues[1])) {
                val style = if (text.contains(ZWNJ)) Style.GOOGLE else Style.IPHONE_EMOJI
                return parsed(m.groupValues[1], m.groupValues[2], removed = true, style = style)
            }
        }
        googleAdd.matchEntire(text)?.let { m ->
            if (isEmojiToken(m.groupValues[1])) {
                val style = if (text.startsWith(ZWSP)) Style.GOOGLE else Style.PLAIN
                return parsed(m.groupValues[1], m.groupValues[2], removed = false, style = style)
            }
        }
        return null
    }

    fun isReactionText(body: String): Boolean = parse(body) != null

    private fun tapbackFor(word: String): ReactionEmoji.Tapback? = when (word) {
        "Emphasised" -> ReactionEmoji.Tapback.EMPHASIZED
        else -> ReactionEmoji.Tapback.entries.firstOrNull { it.addVerb == word }
    }

    private fun tapbackForNoun(noun: String): ReactionEmoji.Tapback? = when (noun) {
        "heart" -> ReactionEmoji.Tapback.LOVED
        "like" -> ReactionEmoji.Tapback.LIKED
        "dislike" -> ReactionEmoji.Tapback.DISLIKED
        "laugh" -> ReactionEmoji.Tapback.LAUGHED
        "exclamation", "exclamation mark" -> ReactionEmoji.Tapback.EMPHASIZED
        "question mark" -> ReactionEmoji.Tapback.QUESTIONED
        else -> null
    }

    private fun parsed(emoji: String, rawQuote: String, removed: Boolean, style: Style): Parsed {
        var q = rawQuote.replace(HAIR, " ").trim()
        val truncated = q.endsWith(ELLIPSIS) || q.endsWith("...")
        if (q.endsWith(ELLIPSIS)) q = q.dropLast(1) else if (q.endsWith("...")) q = q.dropLast(3)
        return Parsed(emoji, q.trimEnd(), removed, style, truncated)
    }

    /** True for one emoji (or one emoji sequence): no spaces, no letters, at least one emoji-range character. */
    internal fun isEmojiToken(s: String): Boolean {
        if (s.isEmpty() || s.length > 24) return false
        var sawEmoji = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isEmojiRange(cp) -> sawEmoji = true
                cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3 -> Unit
                cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F -> Unit
                cp in '0'.code..'9'.code || cp == '#'.code || cp == '*'.code -> Unit // keycaps
                else -> return false
            }
        }
        return sawEmoji || s.codePoints().anyMatch { it == 0x20E3 }
    }

    private fun isEmojiRange(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp in 0x231A..0x23FF ||
            cp == 0x203C || cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x00A9 || cp == 0x00AE ||
            cp in 0x2194..0x21AA || cp in 0x2934..0x2935 || cp == 0x24C2 || cp in 0x25AA..0x25FE || cp in 0x3030..0x303D
}
