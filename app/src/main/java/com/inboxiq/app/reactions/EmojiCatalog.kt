package com.inboxiq.app.reactions

import android.content.res.Resources
import com.inboxiq.app.R

/**
 * The emoji the picker offers: a small curated set bundled as string arrays (res/values/emoji_catalog.xml), chosen from
 * emoji that every Android version the app supports can draw. No network, no extra library.
 */
object EmojiCatalog {
    data class Entry(val emoji: String, val name: String, val words: String)
    data class Category(val id: String, val icon: String, val title: String, val entries: List<Entry>)

    /** "emoji|name|search words", the format of the string arrays. */
    fun parse(line: String): Entry? {
        val parts = line.split('|')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Entry(parts[0].trim(), parts[1].trim(), parts.getOrElse(2) { "" }.trim())
    }

    private class Spec(val id: String, val icon: String, val array: Int, val title: Int)

    private val specs = listOf(
        Spec("smileys", "😀", R.array.emoji_smileys, R.string.emoji_category_smileys),
        Spec("people", "👍", R.array.emoji_people, R.string.emoji_category_people),
        Spec("hearts", "❤️", R.array.emoji_hearts, R.string.emoji_category_hearts),
        Spec("nature", "🐶", R.array.emoji_nature, R.string.emoji_category_nature),
        Spec("food", "🍎", R.array.emoji_food, R.string.emoji_category_food),
        Spec("activities", "⚽", R.array.emoji_activities, R.string.emoji_category_activities),
        Spec("objects", "🚗", R.array.emoji_objects, R.string.emoji_category_objects),
    )

    fun load(resources: Resources): List<Category> = specs.map { s ->
        Category(s.id, s.icon, resources.getString(s.title), resources.getStringArray(s.array).mapNotNull(::parse))
    }

    /** Every word of [query] must appear in an emoji's name or search words. Blank query matches nothing. */
    fun search(categories: List<Category>, query: String): List<Entry> {
        val tokens = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        return categories.flatMap { it.entries }.filter { e ->
            val hay = (e.name + " " + e.words).lowercase()
            tokens.all { it in hay }
        }
    }

    /** The spoken name of [emoji], or the emoji itself when it is not in the catalog (the screen reader reads it). */
    fun nameOf(categories: List<Category>, emoji: String): String {
        val key = ReactionEmoji.canonical(emoji)
        for (c in categories) for (e in c.entries) if (ReactionEmoji.canonical(e.emoji) == key) return e.name
        return emoji
    }

    /** One lookup table, built once per screen. */
    fun nameIndex(categories: List<Category>): Map<String, String> =
        categories.flatMap { it.entries }.associate { ReactionEmoji.canonical(it.emoji) to it.name }
}
