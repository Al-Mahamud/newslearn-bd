package com.newslearn.bd.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newslearn.bd.R

/** Body face: designed for Bangla and Latin together, so mixed text sits on one baseline. */
val BodyFont = FontFamily(
    Font(R.font.hind_siliguri_regular, FontWeight.Normal),
    Font(R.font.hind_siliguri_medium, FontWeight.Medium),
    Font(R.font.hind_siliguri_semibold, FontWeight.SemiBold),
)

@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight) = Font(
    R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Display face for headlines, numbers and words being studied. */
val DisplayFont = FontFamily(
    bricolage(FontWeight.Medium),
    bricolage(FontWeight.SemiBold),
    bricolage(FontWeight.Bold),
)

/** Colours the design uses beyond Material's roles; they differ between light and dark. */
@Immutable
data class AppColors(
    /** The deep green panel behind the daily summary, audio player and review screen. */
    val panel: Color,
    val onPanel: Color,
    val onPanelMuted: Color,
    val panelTrack: Color,
    /** Amber: progress, the streak and the main action on a panel. */
    val highlight: Color,
    val onHighlight: Color,
    /** Background of a vocabulary word inside running text. */
    val wordMark: Color,
    val warning: Color,
)

private val LightAppColors = AppColors(
    panel = Color(0xFF0B3B2C),
    onPanel = Color.White,
    onPanelMuted = Color(0xFFB9D4C8),
    panelTrack = Color(0xFF1E5A47),
    highlight = Color(0xFFF2B544),
    onHighlight = Color(0xFF14201B),
    wordMark = Color(0xFFD6ECDF),
    warning = Color(0xFFB45309),
)

private val DarkAppColors = LightAppColors.copy(
    panel = Color(0xFF0F3327),
    wordMark = Color(0xFF1E5A47),
    warning = Color(0xFFF2B544),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6E4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4F2EA),
    onPrimaryContainer = Color(0xFF0B3B2C),
    secondary = Color(0xFF8A3D00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCE9C6),
    onSecondaryContainer = Color(0xFF4A2A03),
    tertiary = Color(0xFF14201B),
    error = Color(0xFFB3261E),
    background = Color(0xFFF4F6F2),
    onBackground = Color(0xFF14201B),
    surface = Color(0xFFF4F6F2),
    onSurface = Color(0xFF14201B),
    surfaceVariant = Color(0xFFE3E9E4),
    onSurfaceVariant = Color(0xFF4A5A52),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF4EF),
    surfaceContainerHighest = Color(0xFFE3E9E4),
    outline = Color(0xFFC9D2CB),
    outlineVariant = Color(0xFFD9E0DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD9B6),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF1E5A47),
    onPrimaryContainer = Color(0xFFCFEFE0),
    secondary = Color(0xFFF2B544),
    onSecondary = Color(0xFF3A2400),
    secondaryContainer = Color(0xFF5C3F00),
    onSecondaryContainer = Color(0xFFFCE9C6),
    tertiary = Color(0xFFE3E9E4),
    background = Color(0xFF101714),
    onBackground = Color(0xFFE3E9E4),
    surface = Color(0xFF101714),
    onSurface = Color(0xFFE3E9E4),
    surfaceVariant = Color(0xFF2A3630),
    onSurfaceVariant = Color(0xFFB4C2BA),
    surfaceContainerLowest = Color(0xFF18211D),
    surfaceContainerLow = Color(0xFF18211D),
    surfaceContainer = Color(0xFF18211D),
    surfaceContainerHigh = Color(0xFF222D28),
    surfaceContainerHighest = Color(0xFF2A3630),
    outline = Color(0xFF4A5A52),
    outlineVariant = Color(0xFF33423B),
)

private fun display(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Bold) =
    TextStyle(fontFamily = DisplayFont, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp)

private fun body(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = BodyFont, fontWeight = weight, fontSize = size.sp, lineHeight = lineHeight.sp)

private val AppTypography = Typography(
    displayLarge = display(48, 52),
    displayMedium = display(40, 44),
    displaySmall = display(34, 38),
    headlineLarge = display(30, 34),
    headlineMedium = display(28, 32),
    headlineSmall = display(24, 30),
    titleLarge = display(22, 27, FontWeight.SemiBold),
    titleMedium = display(18, 23, FontWeight.SemiBold),
    titleSmall = display(16, 21, FontWeight.SemiBold),
    bodyLarge = body(17, 27),
    bodyMedium = body(15, 23),
    bodySmall = body(13, 19),
    labelLarge = body(15, 20, FontWeight.SemiBold),
    labelMedium = body(13, 18, FontWeight.Medium),
    labelSmall = body(12, 16, FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NewsLearnTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Shorthand: `AppTheme.colors.panel`. */
object AppTheme {
    val colors: AppColors
        @Composable get() = LocalAppColors.current
}
