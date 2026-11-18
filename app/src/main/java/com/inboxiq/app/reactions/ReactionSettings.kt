package com.inboxiq.app.reactions

import android.content.Context

/** What happens to a reaction besides saving it on this phone. */
enum class ReactionMode {
    /** Ask before each reaction that could be sent (the default until the user has read the explainer). */
    ASK,
    SEND_AS_TEXT,
    KEEP_PRIVATE,
}

/** How a single reaction is delivered. */
enum class ReactionDelivery { PRIVATE, TEXT }

data class ReactionSettings(
    val mode: ReactionMode = ReactionMode.ASK,
    /** True once the user has seen the one-time explainer (or picked a mode in Settings, which explains it). */
    val explained: Boolean = false,
    /** Also send a removal text when the user takes back a reaction that went out as text. Off by default. */
    val sendRemovalText: Boolean = false,
)

/** Local preferences for reactions; same file as the other send settings. */
object ReactionPreferences {
    private const val PREFS = "inboxiq_prefs"
    private const val MODE = "reaction_mode"
    private const val EXPLAINED = "reaction_explained"
    private const val REMOVAL = "reaction_send_removal"
    private const val RECENT = "reaction_recent"
    const val MAX_RECENT = 16

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): ReactionSettings {
        val p = prefs(context)
        val mode = ReactionMode.entries.firstOrNull { it.name == p.getString(MODE, null) } ?: ReactionMode.ASK
        return ReactionSettings(mode, p.getBoolean(EXPLAINED, false), p.getBoolean(REMOVAL, false))
    }

    /** Choosing a mode, in the explainer or in Settings, counts as having read the explanation. */
    fun setMode(context: Context, mode: ReactionMode) {
        prefs(context).edit().putString(MODE, mode.name).putBoolean(EXPLAINED, true).apply()
    }

    fun setSendRemovalText(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(REMOVAL, on).apply()
    }

    fun recent(context: Context): List<String> =
        prefs(context).getString(RECENT, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun pushRecent(context: Context, emoji: String) {
        prefs(context).edit().putString(RECENT, withRecent(recent(context), emoji).joinToString("\n")).apply()
    }

    /** [emoji] moved to the front of [current], without a duplicate, capped. Pure, for tests. */
    fun withRecent(current: List<String>, emoji: String): List<String> =
        (listOf(emoji) + current.filterNot { ReactionEmoji.same(it, emoji) }).take(MAX_RECENT)
}
