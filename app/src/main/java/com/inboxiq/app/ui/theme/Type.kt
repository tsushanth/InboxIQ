package com.inboxiq.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.inboxiq.app.R

/**
 * Instrument Sans (variable, SIL OFL) carries everything you read: sender names, message text,
 * labels. Instrument Serif (SIL OFL) is used only for screen titles, the one place the app
 * lets itself have a voice. Licences: third_party/fonts.
 */
@OptIn(ExperimentalTextApi::class)
val InstrumentSans = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(
            R.font.instrument_sans,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    },
)

val InstrumentSerif = FontFamily(Font(R.font.instrument_serif))

private fun sans(size: Int, line: Int, weight: Int = 400, tracking: Float = 0f) = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun serif(size: Int, line: Int) = TextStyle(
    fontFamily = InstrumentSerif,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (-0.01).em,
)

val InboxTypography = Typography(
    displayLarge = serif(48, 52),
    displayMedium = serif(40, 44),
    displaySmall = serif(34, 38),
    headlineLarge = serif(32, 36),
    headlineMedium = serif(28, 32),
    headlineSmall = serif(24, 28),
    titleLarge = serif(22, 26),
    titleMedium = sans(17, 22, 600),
    titleSmall = sans(15, 20, 600),
    bodyLarge = sans(16, 24),
    bodyMedium = sans(14, 20),
    bodySmall = sans(13, 18),
    labelLarge = sans(14, 20, 600),
    labelMedium = sans(12, 16, 600, 0.2f),
    labelSmall = sans(12, 16, 500, 0.2f),
)

/** Tabular figures so one-time codes and counts line up. */
val CodeStyle = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    letterSpacing = 0.12.em,
    fontFeatureSettings = "tnum",
)
