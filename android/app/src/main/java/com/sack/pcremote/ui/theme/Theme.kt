package com.sack.pcremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════
// Tokens de diseño espejo del panel web + Fase 5 mobile:
// paleta dark-first, teal accent, misma jerarquía tipográfica.
// ══════════════════════════════════════════════════════════════

val BgDark      = Color(0xFF0F172A)
val BgAltDark   = Color(0xFF111827)
val CardDark    = Color(0xFF1A2232)
val BorderDark  = Color(0xFF243043)
val TextDark    = Color(0xFFE6EDF3)
val DimDark     = Color(0xFF8B95A5)
val MutedDark   = Color(0xFF4A556B)
val Accent      = Color(0xFF60A5FA)
val AccentDim   = Color(0xFF3B82F6)
val Success     = Color(0xFF34D399)
val Warn        = Color(0xFFFBBF24)
val Danger      = Color(0xFFF87171)

private val AppColors = darkColorScheme(
    background       = BgDark,
    surface          = CardDark,
    surfaceVariant   = BgAltDark,
    onBackground     = TextDark,
    onSurface        = TextDark,
    onSurfaceVariant = DimDark,
    primary          = Accent,
    onPrimary        = Color(0xFF0B1224),
    secondary        = Success,
    error            = Danger,
    outline          = BorderDark,
)

@Composable
fun PcRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, content = content)
}
