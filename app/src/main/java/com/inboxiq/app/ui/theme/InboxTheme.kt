package com.inboxiq.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density as UiDensity

/** Paper-tray palette: cool grey-green paper, ink-blue text, and label colours that each have their own hue. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF24465F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6EC), onPrimaryContainer = Color(0xFF10283A),
    secondary = Color(0xFF55646E), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9EC), onSecondaryContainer = Color(0xFF1B2A33),
    tertiary = Color(0xFF1B6B4A), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDEFE6), onTertiaryContainer = Color(0xFF0A3622),
    background = Color(0xFFF1F3F1), onBackground = Color(0xFF17222B),
    surface = Color(0xFFF8F9F8), onSurface = Color(0xFF17222B),
    surfaceVariant = Color(0xFFE4E8E6), onSurfaceVariant = Color(0xFF4D5B64),
    surfaceTint = Color(0xFF24465F),
    outline = Color(0xFF6F7D86), outlineVariant = Color(0xFFD3D9D6),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DDDA), onErrorContainer = Color(0xFF410E0B),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F8F6),
    surfaceContainer = Color(0xFFEDF0EE), surfaceContainerHigh = Color(0xFFE7EBE9),
    surfaceContainerHighest = Color(0xFFE1E6E3),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9DC4DF), onPrimary = Color(0xFF0E2433),
    primaryContainer = Color(0xFF27394A), onPrimaryContainer = Color(0xFFD6E6F2),
    secondary = Color(0xFFB0BEC6), onSecondary = Color(0xFF1A262D),
    secondaryContainer = Color(0xFF2B373E), onSecondaryContainer = Color(0xFFD9E3E8),
    tertiary = Color(0xFF8FD8B5), onTertiary = Color(0xFF06301F),
    tertiaryContainer = Color(0xFF16402D), onTertiaryContainer = Color(0xFFCDEEDD),
    background = Color(0xFF111619), onBackground = Color(0xFFE4EAED),
    surface = Color(0xFF181E22), onSurface = Color(0xFFE4EAED),
    surfaceVariant = Color(0xFF28323A), onSurfaceVariant = Color(0xFFB3C0C8),
    surfaceTint = Color(0xFF9DC4DF),
    outline = Color(0xFF8A99A3), outlineVariant = Color(0xFF36424A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
    surfaceContainerLowest = Color(0xFF0C1013), surfaceContainerLow = Color(0xFF151A1E),
    surfaceContainer = Color(0xFF1B2125), surfaceContainerHigh = Color(0xFF242B30),
    surfaceContainerHighest = Color(0xFF2E363B),
)

/** True when the active scheme is a dark one (label colours switch tints on this). */
val LocalDarkTheme: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }
val LocalDensityMode: ProvidableCompositionLocal<Density> = staticCompositionLocalOf { Density.COMFORTABLE }

fun colorSchemeFor(settings: ThemeSettings, systemDark: Boolean, context: android.content.Context?): Pair<ColorScheme, Boolean> {
    val dark = when (settings.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.TRUE_BLACK -> true
    }
    val dynamic = settings.dynamicColor && context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context!!)
        dynamic -> dynamicLightColorScheme(context!!)
        dark -> DarkScheme
        else -> LightScheme
    }
    if (settings.mode == ThemeMode.TRUE_BLACK) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color(0xFF080B0C),
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF060808),
            surfaceContainer = Color(0xFF0D1113), surfaceContainerHigh = Color(0xFF14191C),
        )
    }
    return scheme to dark
}

@Composable
fun InboxTheme(settings: ThemeSettings = ThemeSettings(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val (scheme, dark) = colorSchemeFor(settings, isSystemInDarkTheme(), context)
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDarkTheme provides dark,
        LocalDensityMode provides settings.density,
        LocalDensity provides UiDensity(base.density, base.fontScale * settings.fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = InboxTypography, content = content)
    }
}
