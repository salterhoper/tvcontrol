package com.codesky.tvcontrol.remote.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A curated Material 3 palette seeded from the product's mint accent (#5EE1C4),
 * so the remote feels of a piece with the TV app and dashboard. Full tonal roles
 * for both light and dark - no ad-hoc hardcoded colours in the UI.
 */

// Brand mint, used as the accent people tap.
val BrandMint = Color(0xFF5EE1C4)

val DarkColors = darkColorScheme(
    primary = Color(0xFF5EE1C4),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005140),
    onPrimaryContainer = Color(0xFF7DFEDD),
    secondary = Color(0xFFB2CCC2),
    onSecondary = Color(0xFF1D352F),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCEE9DE),
    tertiary = Color(0xFFA8C8E4),
    onTertiary = Color(0xFF0C3350),
    tertiaryContainer = Color(0xFF274A67),
    onTertiaryContainer = Color(0xFFCDE5FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF0B0D10),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C4),
    surfaceContainerLowest = Color(0xFF06080A),
    surfaceContainerLow = Color(0xFF111418),
    surfaceContainer = Color(0xFF14181D),
    surfaceContainerHigh = Color(0xFF1C2127),
    surfaceContainerHighest = Color(0xFF262C33),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    inverseSurface = Color(0xFFECEFF4),
    inverseOnSurface = Color(0xFF2E3135),
    inversePrimary = Color(0xFF006B57),
)

val LightColors = lightColorScheme(
    primary = Color(0xFF006B57),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF7DFEDD),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE9DE),
    onSecondaryContainer = Color(0xFF07201A),
    tertiary = Color(0xFF41617B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC6E7FF),
    onTertiaryContainer = Color(0xFF001E31),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF5FBF7),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDBE5DF),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F0),
    surfaceContainer = Color(0xFFE9EFEA),
    surfaceContainerHigh = Color(0xFFE3E9E5),
    surfaceContainerHighest = Color(0xFFDEE4DF),
    outline = Color(0xFF6F7975),
    outlineVariant = Color(0xFFBFC9C3),
    inverseSurface = Color(0xFF2B322F),
    inverseOnSurface = Color(0xFFECF2ED),
    inversePrimary = Color(0xFF5EE1C4),
)
