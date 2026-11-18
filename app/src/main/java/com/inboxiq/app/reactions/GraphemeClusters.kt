package com.inboxiq.app.reactions

/**
 * Splits text into user-perceived characters, so a cut never lands inside a surrogate pair, an emoji
 * with a skin tone or variation selector, a ZWJ sequence (family, profession), a flag or a keycap.
 *
 * This is the small subset of the Unicode grapheme rules that matters for emoji, written without
 * `BreakIterator` because the JDK's version of that does not join ZWJ sequences until JDK 20 while
 * Android's does, and the unit tests run on a JDK.
 */
internal object GraphemeClusters {
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        var prev = -1
        var regionalRun = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val next = i + Character.charCount(cp)
            val joins = i > 0 && when {
                prev == '\r'.code && cp == '\n'.code -> true
                isExtender(cp) -> true
                prev == ZWJ -> true
                isRegionalIndicator(cp) && isRegionalIndicator(prev) && regionalRun % 2 == 1 -> true
                else -> false
            }
            if (!joins && i > 0) {
                out += text.substring(start, i)
                start = i
            }
            regionalRun = if (isRegionalIndicator(cp)) (if (joins || isRegionalIndicator(prev)) regionalRun + 1 else 1) else 0
            prev = cp
            i = next
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    private const val ZWJ = 0x200D

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF

    private fun isExtender(cp: Int): Boolean {
        if (cp == ZWJ || cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3) return true
        if (cp in 0x1F3FB..0x1F3FF) return true // skin tone modifiers
        if (cp in 0xE0020..0xE007F) return true // tag characters (subdivision flags)
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }
}
