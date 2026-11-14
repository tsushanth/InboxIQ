package com.inboxiq.app.reactions

/**
 * The reaction vocabulary that is not tied to the screen: the quick set, how emoji compare, and which emoji
 * have an iPhone tapback word. Pure Kotlin.
 */
object ReactionEmoji {
    const val THUMBS_UP = "👍"
    const val HEART = "❤️"
    const val LAUGH = "😂"
    const val SURPRISED = "😮"
    const val SAD = "😢"
    const val ANGRY = "😡"
    const val THUMBS_DOWN = "👎"
    const val EXCLAMATION = "‼️"
    const val QUESTION = "❓"

    /** The strip shown first in the long-press menu, in this order. */
    val QUICK: List<String> = listOf(THUMBS_UP, HEART, LAUGH, SURPRISED, SAD, ANGRY)

    /** Emoji compare without presentation selectors, so "❤" and "❤️" are the same reaction. */
    fun canonical(emoji: String): String = emoji.replace("️", "").replace("︎", "")

    fun same(a: String, b: String): Boolean = canonical(a) == canonical(b)

    /** The iPhone tapback this emoji is sent as, or null when it goes out in the generic "emoji to ..." form. */
    fun tapback(emoji: String): Tapback? = when (canonical(emoji)) {
        canonical(HEART) -> Tapback.LOVED
        canonical(THUMBS_UP) -> Tapback.LIKED
        canonical(THUMBS_DOWN) -> Tapback.DISLIKED
        canonical(LAUGH) -> Tapback.LAUGHED
        // The surprised face is the closest of the six quick emoji to Apple's "!!"; "!!" itself maps there too.
        canonical(SURPRISED), canonical(EXCLAMATION), "❗" -> Tapback.EMPHASIZED
        canonical(QUESTION), "❔" -> Tapback.QUESTIONED
        else -> null
    }

    /** The six iPhone tapbacks: the words an iPhone writes when it reacts to a plain text message. */
    enum class Tapback(
        val addVerb: String,
        /** What follows "Removed " in the removal text ("a heart", "an exclamation"). */
        val removedNoun: String,
        /** The emoji a receiver shows for it. */
        val emoji: String,
    ) {
        LOVED("Loved", "a heart", HEART),
        LIKED("Liked", "a like", THUMBS_UP),
        DISLIKED("Disliked", "a dislike", THUMBS_DOWN),
        LAUGHED("Laughed at", "a laugh", LAUGH),
        EMPHASIZED("Emphasized", "an exclamation", EXCLAMATION),
        QUESTIONED("Questioned", "a question mark", QUESTION),
    }
}
