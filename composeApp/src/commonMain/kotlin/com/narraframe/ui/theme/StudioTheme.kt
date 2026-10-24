package com.narraframe.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Palette "Aurora": graphite surfaces, teal → lime accent ────────────────
val Ink           = Color(0xFF0C0F10)   // window background
val Surface1      = Color(0xFF141819)   // cards, panels
val Surface2      = Color(0xFF1C2123)   // inputs, raised elements
val Outline       = Color(0xFF2A3133)
val TextPrimary   = Color(0xFFEDF2F1)
val TextMuted     = Color(0xFF93A09E)
val Accent        = Color(0xFF2DD4BF)   // teal
val AccentEnd     = Color(0xFFA3E635)   // lime
val OnAccent      = Color(0xFF06201C)   // text and icons on the accent gradient
val SuccessGreen  = Color(0xFF4ADE80)
val WarningOrange = Color(0xFFFBBF24)   // amber
val ErrorRed      = Color(0xFFFB7185)   // coral

/** Signature teal → lime gradient used for the primary action and progress. */
val AccentGradient: Brush = Brush.horizontalGradient(listOf(Accent, AccentEnd))

// Names kept for screens that predate the redesign
val SurfaceDark = Ink
val SurfaceCard = Surface1
val AccentWarm  = AccentEnd

private val DarkColorScheme = darkColorScheme(
    primary              = Accent,
    onPrimary            = OnAccent,
    primaryContainer     = Accent.copy(alpha = 0.16f),
    onPrimaryContainer   = TextPrimary,
    secondary            = AccentEnd,
    onSecondary          = OnAccent,
    secondaryContainer   = Surface2,
    onSecondaryContainer = TextPrimary,
    tertiary             = SuccessGreen,
    background           = Ink,
    onBackground         = TextPrimary,
    surface              = Surface1,
    onSurface            = TextPrimary,
    surfaceVariant       = Surface2,
    onSurfaceVariant     = TextMuted,
    surfaceContainerLowest  = Ink,
    surfaceContainerLow     = Surface1,
    surfaceContainer        = Surface1,
    surfaceContainerHigh    = Surface2,
    surfaceContainerHighest = Surface2,
    inverseSurface       = TextPrimary,
    inverseOnSurface     = Ink,
    error                = ErrorRed,
    onError              = Color(0xFF2A0710),
    outline              = Outline,
    outlineVariant       = Outline,
    scrim                = Color(0xCC000000)
)

private val base = Typography()

private val StudioTypography = Typography(
    displaySmall   = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
    headlineSmall  = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge     = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium    = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall     = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge      = base.bodyLarge.copy(lineHeight = 24.sp),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
)

private val StudioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(10.dp),
    medium     = RoundedCornerShape(14.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = StudioTypography,
        shapes      = StudioShapes
    ) {
        // Screens are plain Columns/Boxes, not Surfaces, so set the default text and
        // icon colour here; otherwise unstyled Text falls back to black.
        CompositionLocalProvider(LocalContentColor provides TextPrimary, content = content)
    }
}
