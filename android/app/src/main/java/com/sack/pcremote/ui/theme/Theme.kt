package com.sack.pcremote.ui.theme

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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.sack.pcremote.data.ThemeMode

private val DarkScheme = darkColorScheme(
    primary = Teal80, onPrimary = Teal20, primaryContainer = Teal30, onPrimaryContainer = Teal90,
    inversePrimary = Teal40,
    secondary = Steel80, onSecondary = Steel20, secondaryContainer = Steel30, onSecondaryContainer = Steel90,
    tertiary = Violet80, onTertiary = Violet20, tertiaryContainer = Violet30, onTertiaryContainer = Violet90,
    error = Red80, onError = Red20, errorContainer = Red30, onErrorContainer = Red90,
    background = Dark1, onBackground = DarkOn,
    surface = Dark1, onSurface = DarkOn,
    surfaceVariant = Dark5, onSurfaceVariant = DarkOnVariant,
    surfaceTint = Teal80,
    inverseSurface = DarkOn, inverseOnSurface = Dark3,
    outline = DarkOutline, outlineVariant = DarkOutlineVariant,
    scrim = Color.Black,
    surfaceBright = DarkBright, surfaceDim = Dark1,
    surfaceContainerLowest = Dark0, surfaceContainerLow = Dark2, surfaceContainer = Dark3,
    surfaceContainerHigh = Dark4, surfaceContainerHighest = Dark5,
)

private val LightScheme = lightColorScheme(
    primary = Teal40, onPrimary = Color.White, primaryContainer = Teal90, onPrimaryContainer = Teal10,
    inversePrimary = Teal80,
    secondary = Steel40, onSecondary = Color.White, secondaryContainer = Steel90, onSecondaryContainer = Steel10,
    tertiary = Violet40, onTertiary = Color.White, tertiaryContainer = Violet90, onTertiaryContainer = Violet10,
    error = Red40, onError = Color.White, errorContainer = Red90, onErrorContainer = Red10,
    background = Light1, onBackground = LightOn,
    surface = Light1, onSurface = LightOn,
    surfaceVariant = Light5, onSurfaceVariant = LightOnVariant,
    surfaceTint = Teal40,
    inverseSurface = Color(0xFF2B3231), inverseOnSurface = Color(0xFFECF2F0),
    outline = LightOutline, outlineVariant = LightOutlineVariant,
    scrim = Color.Black,
    surfaceBright = Light0, surfaceDim = LightDim,
    surfaceContainerLowest = Light0, surfaceContainerLow = Light2, surfaceContainer = Light3,
    surfaceContainerHigh = Light4, surfaceContainerHighest = Light5,
)

/**
 * Colours Material 3 has no slot for: success / warning (states) and one colour
 * per chart series, so CPU is the same colour on every screen.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val cpu: Color,
    val ram: Color,
    val gpu: Color,
    val net: Color,
    /** Terminal: a surface a notch darker than the cards, in both themes. */
    val terminalBackground: Color,
    val terminalText: Color,
    val isDark: Boolean,
)

private val DarkExtended = ExtendedColors(
    success = GreenDark, successContainer = GreenDarkContainer, onSuccessContainer = Color(0xFFC9F5D2),
    warning = AmberDark, warningContainer = AmberDarkContainer, onWarningContainer = Color(0xFFFFE08F),
    cpu = ChartCpuDark, ram = ChartRamDark, gpu = ChartGpuDark, net = ChartNetDark,
    terminalBackground = Dark0, terminalText = Color(0xFFCFE0DC),
    isDark = true,
)

private val LightExtended = ExtendedColors(
    success = GreenLight, successContainer = GreenLightContainer, onSuccessContainer = Color(0xFF00210A),
    warning = AmberLight, warningContainer = AmberLightContainer, onWarningContainer = Color(0xFF261A00),
    cpu = ChartCpuLight, ram = ChartRamLight, gpu = ChartGpuLight, net = ChartNetLight,
    terminalBackground = Color(0xFF111719), terminalText = Color(0xFFD6E4E1),
    isDark = false,
)

private val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

object PcRemoteTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current
}

@Composable
fun PcRemoteTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
