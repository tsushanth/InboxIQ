package com.inboxiq.app.classify

import android.content.Context

enum class ClassifierTier(val label: String, val available: Boolean) {
    DEFAULT("Default (bert-tiny, on-device)", available = true),
    MID("Balanced (Gemma 3 270M, on-device — needs a one-time ~290MB download)", available = true),
    HIGH("Best (Gemma/Qwen 1-3B) — coming soon", available = false),
}

/**
 * Only DEFAULT does anything right now — MID/HIGH have no model wired up
 * yet (see README "Next steps"). The setting is stored so the UI and
 * future tiers have somewhere real to read from once they're implemented,
 * rather than adding this plumbing later.
 */
object ClassifierTierPreference {
    private const val PREFS = "inboxiq_prefs"
    private const val KEY_TIER = "classifier_tier"

    fun get(context: Context): ClassifierTier {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TIER, null)
        return ClassifierTier.entries.find { it.name == stored } ?: ClassifierTier.DEFAULT
    }

    fun set(context: Context, tier: ClassifierTier) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TIER, tier.name)
            .apply()
    }
}
