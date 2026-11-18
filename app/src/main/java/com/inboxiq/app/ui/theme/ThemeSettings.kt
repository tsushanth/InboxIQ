package com.inboxiq.app.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import com.inboxiq.app.R

enum class ThemeMode(@StringRes val labelRes: Int) {
    SYSTEM(R.string.theme_match_system), LIGHT(R.string.theme_light), DARK(R.string.theme_dark), TRUE_BLACK(R.string.theme_true_black)
}
enum class Density(@StringRes val labelRes: Int) { COMFORTABLE(R.string.density_comfortable), COMPACT(R.string.density_compact) }

/** How the app looks, all stored locally. Font scale multiplies the system font size, it never replaces it. */
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You: take colours from the wallpaper (Android 12+). Off by default; label colours stay fixed. */
    val dynamicColor: Boolean = false,
    val density: Density = Density.COMFORTABLE,
    val fontScale: Float = 1f,
) {
    companion object {
        val FONT_SCALES = listOf(0.9f, 1f, 1.15f, 1.3f)
        @StringRes
        fun fontScaleLabelRes(scale: Float): Int = when (scale) {
            0.9f -> R.string.text_size_small
            1f -> R.string.text_size_default
            1.15f -> R.string.text_size_large
            else -> R.string.text_size_largest
        }
    }
}

object ThemePreferences {
    private const val PREFS = "inboxiq_prefs"

    fun get(context: Context): ThemeSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ThemeSettings(
            mode = ThemeMode.entries.find { it.name == p.getString("theme_mode", null) } ?: ThemeMode.SYSTEM,
            dynamicColor = p.getBoolean("theme_dynamic", false),
            density = Density.entries.find { it.name == p.getString("theme_density", null) } ?: Density.COMFORTABLE,
            fontScale = p.getFloat("theme_font_scale", 1f).takeIf { it in ThemeSettings.FONT_SCALES } ?: 1f,
        )
    }

    fun set(context: Context, s: ThemeSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("theme_mode", s.mode.name)
            .putBoolean("theme_dynamic", s.dynamicColor)
            .putString("theme_density", s.density.name)
            .putFloat("theme_font_scale", s.fontScale)
            .apply()
    }
}
