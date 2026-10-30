package com.pandyabhavik.pedometer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.pandyabhavik.pedometer.data.ThemeMode

/** Colours Material doesn't name: the ring's track, the hour dial, a quiet line. */
@Immutable
data class Palette(
    val track: Color,
    val dialIdle: Color,
    val dialActive: Color,
    val highlight: Color,
    val hairline: Color,
    val miss: Color,
    val grayscale: Boolean,
    val dark: Boolean,
)

val LocalPalette = staticCompositionLocalOf {
    Palette(Color.LightGray, Color.LightGray, Color.DarkGray, Color.Yellow, Color.LightGray, Color.Gray, false, false)
}

private val Teal = Color(0xFF0E7C72)
private val TealLight = Color(0xFF5ED1C1)
private val Amber = Color(0xFFD9962A)
private val AmberLight = Color(0xFFF2B84B)

private val LightScheme = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EEEA),
    onPrimaryContainer = Color(0xFF06302C),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E6C6),
    onSecondaryContainer = Color(0xFF3A2805),
    background = Color(0xFFF6F5F1),
    onBackground = Color(0xFF15201E),
    surface = Color(0xFFF6F5F1),
    onSurface = Color(0xFF15201E),
    surfaceVariant = Color(0xFFECEAE4),
    onSurfaceVariant = Color(0xFF5E6B68),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF0EEE9),
    surfaceContainerHighest = Color(0xFFE9E7E1),
    outline = Color(0xFFCBC7BD),
    outlineVariant = Color(0xFFE2DFD7),
    error = Color(0xFFB3261E),
)

private val DarkScheme = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00201C),
    primaryContainer = Color(0xFF113D38),
    onPrimaryContainer = Color(0xFFBDEFE7),
    secondary = AmberLight,
    onSecondary = Color(0xFF2E1F00),
    secondaryContainer = Color(0xFF4A3510),
    onSecondaryContainer = Color(0xFFFBE3B5),
    background = Color(0xFF0D0F0F),
    onBackground = Color(0xFFEDEAE3),
    surface = Color(0xFF0D0F0F),
    onSurface = Color(0xFFEDEAE3),
    surfaceVariant = Color(0xFF1F2524),
    onSurfaceVariant = Color(0xFF9BA6A3),
    surfaceContainerLowest = Color(0xFF0A0C0C),
    surfaceContainerLow = Color(0xFF141817),
    surfaceContainer = Color(0xFF161A19),
    surfaceContainerHigh = Color(0xFF1D2221),
    surfaceContainerHighest = Color(0xFF252B2A),
    outline = Color(0xFF3A4341),
    outlineVariant = Color(0xFF283030),
    error = Color(0xFFF2B8B5),
)

private val GrayLight = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF444444),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFEAEAEA),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color.Black,
)

private val GrayDark = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF262626),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFBBBBBB),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFA0A0A0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0C0C),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    outline = Color(0xFF4A4A4A),
    outlineVariant = Color(0xFF2A2A2A),
    error = Color.White,
)

/** Numbers line up in columns and don't jump as they count. */
val Numerals = TextStyle(fontFeatureSettings = "tnum, lnum")

private fun typography(): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.04).em, fontFeatureSettings = "tnum, lnum"),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum, lnum"),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum, lnum"),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.06.em, fontSize = 11.sp),
    )
}

@Composable
fun PedometerTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM, ThemeMode.GRAYSCALE -> systemDark
    }
    val gray = mode == ThemeMode.GRAYSCALE
    val scheme: ColorScheme = when {
        gray && dark -> GrayDark
        gray -> GrayLight
        dark -> DarkScheme
        else -> LightScheme
    }
    val palette = when {
        gray && dark -> Palette(Color(0xFF262626), Color(0xFF2E2E2E), Color.White, Color.White, Color(0xFF222222), Color(0xFF555555), true, true)
        gray -> Palette(Color(0xFFE6E6E6), Color(0xFFDDDDDD), Color.Black, Color.Black, Color(0xFFEAEAEA), Color(0xFFAAAAAA), true, false)
        dark -> Palette(Color(0xFF232A29), Color(0xFF2B3332), TealLight, AmberLight, Color(0xFF222827), Color(0xFF4A5452), false, true)
        else -> Palette(Color(0xFFE3E6E2), Color(0xFFDCDAD3), Teal, Amber, Color(0xFFE6E3DC), Color(0xFFC4C0B6), false, false)
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
