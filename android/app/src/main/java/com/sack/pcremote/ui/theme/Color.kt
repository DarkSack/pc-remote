package com.sack.pcremote.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════
// Paleta "Signal": un teal técnico como color de marca (el del logo),
// superficies grafito con un matiz frío, y acentos sobrios. Nada de
// neón: los contrastes son suaves y la profundidad sale de las capas de
// superficie (surfaceContainer*), no de brillos.
//
// El tema oscuro es el principal; el claro es una adaptación real (mismas
// capas y jerarquía, con superficies claras y sombras), no una inversión.
// Estos valores solo se usan en Theme.kt: las pantallas leen siempre
// MaterialTheme.colorScheme / PcRemoteTheme.extended.
// ══════════════════════════════════════════════════════════════

// Brand
internal val Teal10 = Color(0xFF00201C)
internal val Teal20 = Color(0xFF003731)
internal val Teal30 = Color(0xFF005048)
internal val Teal40 = Color(0xFF00695F)
internal val Teal80 = Color(0xFF4FDBC8)
internal val Teal90 = Color(0xFF9CF2E3)
internal val BrandTeal = Color(0xFF2DD4BF)

// Secondary: steel blue, for complementary information (links, info chips).
internal val Steel10 = Color(0xFF001E30)
internal val Steel20 = Color(0xFF15334A)
internal val Steel30 = Color(0xFF2D4A62)
internal val Steel40 = Color(0xFF45627B)
internal val Steel80 = Color(0xFFABCAE6)
internal val Steel90 = Color(0xFFCDE5FF)

// Tertiary: soft violet, for special states (plugins, advanced, charts).
internal val Violet10 = Color(0xFF1E1147)
internal val Violet20 = Color(0xFF33275E)
internal val Violet30 = Color(0xFF4A3E76)
internal val Violet40 = Color(0xFF625590)
internal val Violet80 = Color(0xFFCBBEFF)
internal val Violet90 = Color(0xFFE7DEFF)

// Error
internal val Red10 = Color(0xFF410002)
internal val Red20 = Color(0xFF690005)
internal val Red30 = Color(0xFF93000A)
internal val Red40 = Color(0xFFBA1A1A)
internal val Red80 = Color(0xFFFFB4AB)
internal val Red90 = Color(0xFFFFDAD6)

// Dark surfaces (graphite with a cold tint), from deepest to highest.
internal val Dark0 = Color(0xFF070B0D)    // surfaceContainerLowest
internal val Dark1 = Color(0xFF0B1013)    // background / surface
internal val Dark2 = Color(0xFF12181B)    // surfaceContainerLow
internal val Dark3 = Color(0xFF161D21)    // surfaceContainer (cards)
internal val Dark4 = Color(0xFF1D2529)    // surfaceContainerHigh (elevated cards)
internal val Dark5 = Color(0xFF252E33)    // surfaceContainerHighest (dialogs, inputs)
internal val DarkBright = Color(0xFF2F393E)
internal val DarkOn = Color(0xFFDDE5E3)
internal val DarkOnVariant = Color(0xFFA0ADAA)
internal val DarkOutline = Color(0xFF6C7976)
internal val DarkOutlineVariant = Color(0xFF2A3437)

// Light surfaces (cool paper).
internal val Light0 = Color(0xFFFFFFFF)
internal val Light1 = Color(0xFFF5F8F8)
internal val Light2 = Color(0xFFEFF3F3)
internal val Light3 = Color(0xFFE9EEEE)
internal val Light4 = Color(0xFFE3E9E8)
internal val Light5 = Color(0xFFDDE3E2)
internal val LightDim = Color(0xFFD5DBDA)
internal val LightOn = Color(0xFF161D1C)
internal val LightOnVariant = Color(0xFF3F4947)
internal val LightOutline = Color(0xFF6F7977)
internal val LightOutlineVariant = Color(0xFFBEC9C6)

// Extended: status and chart series.
internal val GreenDark = Color(0xFF7BDA95)
internal val GreenDarkContainer = Color(0xFF14391F)
internal val GreenLight = Color(0xFF1E7D3A)
internal val GreenLightContainer = Color(0xFFBDF2C8)
internal val AmberDark = Color(0xFFF2C25C)
internal val AmberDarkContainer = Color(0xFF3D2E00)
internal val AmberLight = Color(0xFF8A6100)
internal val AmberLightContainer = Color(0xFFFFE08F)

internal val ChartCpuDark = Teal80
internal val ChartRamDark = Violet80
internal val ChartGpuDark = Color(0xFFF2B880)
internal val ChartNetDark = Steel80
internal val ChartCpuLight = Teal40
internal val ChartRamLight = Violet40
internal val ChartGpuLight = Color(0xFF9A5A1C)
internal val ChartNetLight = Steel40
