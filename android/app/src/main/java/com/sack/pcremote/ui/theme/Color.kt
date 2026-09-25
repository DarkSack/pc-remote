package com.sack.pcremote.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════
// Paleta "núcleo". Primary: un teal técnico (el del logo). Secondary:
// acero, para información complementaria. Tertiary: ámbar, para
// estados especiales y avisos. Las superficies escalan en profundidad
// (background → surface → containers) en vez de ser todo negro.
//
// Estas constantes solo se usan aquí: las pantallas leen
// MaterialTheme.colorScheme y MaterialTheme.extendedColors.
// El panel web del agente usa los mismos valores.
// ══════════════════════════════════════════════════════════════

internal val DarkColors = darkColorScheme(
    primary = Color(0xFF57D9C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0E4F45),
    onPrimaryContainer = Color(0xFF9FF2E2),
    inversePrimary = Color(0xFF006B5E),
    secondary = Color(0xFFA9BCD6),
    onSecondary = Color(0xFF13293F),
    secondaryContainer = Color(0xFF2A3A4E),
    onSecondaryContainer = Color(0xFFD3E2F7),
    tertiary = Color(0xFFF4B860),
    onTertiary = Color(0xFF432C00),
    tertiaryContainer = Color(0xFF5E4100),
    onTertiaryContainer = Color(0xFFFFDDAE),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B1015),
    onBackground = Color(0xFFE1E6EC),
    surface = Color(0xFF0B1015),
    onSurface = Color(0xFFE1E6EC),
    surfaceVariant = Color(0xFF25303B),
    onSurfaceVariant = Color(0xFFA3AEBA),
    surfaceTint = Color(0xFF57D9C3),
    inverseSurface = Color(0xFFE1E6EC),
    inverseOnSurface = Color(0xFF1B232B),
    outline = Color(0xFF3A4652),
    outlineVariant = Color(0xFF26303A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A333D),
    surfaceDim = Color(0xFF0B1015),
    surfaceContainerLowest = Color(0xFF080C10),
    surfaceContainerLow = Color(0xFF11171E),
    surfaceContainer = Color(0xFF151C24),
    surfaceContainerHigh = Color(0xFF1C2530),
    surfaceContainerHighest = Color(0xFF25303B),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF2E0),
    onPrimaryContainer = Color(0xFF00201B),
    inversePrimary = Color(0xFF57D9C3),
    secondary = Color(0xFF4A5F78),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3FB),
    onSecondaryContainer = Color(0xFF041C31),
    tertiary = Color(0xFF7C5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA9),
    onTertiaryContainer = Color(0xFF271900),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3F6F8),
    onBackground = Color(0xFF161C22),
    surface = Color(0xFFF3F6F8),
    onSurface = Color(0xFF161C22),
    surfaceVariant = Color(0xFFDDE3E9),
    onSurfaceVariant = Color(0xFF414A53),
    surfaceTint = Color(0xFF006B5E),
    inverseSurface = Color(0xFF2B3137),
    inverseOnSurface = Color(0xFFEDF1F5),
    outline = Color(0xFF6F7A85),
    outlineVariant = Color(0xFFC3CAD2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7F9FB),
    surfaceDim = Color(0xFFD6DCE1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBEFF3),
    surfaceContainerHighest = Color(0xFFE2E7EC),
)

/**
 * Colours Material 3 does not have a role for. Success is derived from the
 * scheme's own tones (same lightness as primary), so it fits both themes.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    /** Background of the terminal: always dark, in both themes. */
    val terminalBackground: Color,
    val terminalText: Color,
    val terminalDim: Color,
    val terminalError: Color,
    val terminalPrompt: Color,
)

internal val DarkExtended = ExtendedColors(
    success = Color(0xFF6FDC8C),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF0F4F2A),
    onSuccessContainer = Color(0xFF8BF8A6),
    warning = Color(0xFFF4B860),
    warningContainer = Color(0xFF5E4100),
    onWarningContainer = Color(0xFFFFDDAE),
    terminalBackground = Color(0xFF070B0F),
    terminalText = Color(0xFFD5DEE7),
    terminalDim = Color(0xFF6B7682),
    terminalError = Color(0xFFFF8A80),
    terminalPrompt = Color(0xFF57D9C3),
)

internal val LightExtended = ExtendedColors(
    success = Color(0xFF1B6D3A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA6F4B8),
    onSuccessContainer = Color(0xFF00210C),
    warning = Color(0xFF7C5800),
    warningContainer = Color(0xFFFFDEA9),
    onWarningContainer = Color(0xFF271900),
    terminalBackground = Color(0xFF0F151B),
    terminalText = Color(0xFFD5DEE7),
    terminalDim = Color(0xFF7C8792),
    terminalError = Color(0xFFFF8A80),
    terminalPrompt = Color(0xFF57D9C3),
)
