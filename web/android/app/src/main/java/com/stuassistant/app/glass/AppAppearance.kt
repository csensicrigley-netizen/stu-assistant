package com.stuassistant.app.glass

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** App appearance overrides; the upstream renderer keeps its own defaults. */
internal val LocalLiquidGlassDarkTheme = staticCompositionLocalOf<Boolean?> { null }
internal val LocalLiquidGlassAccentColor = staticCompositionLocalOf<Color?> { null }
internal val LocalLiquidGlassReducedMotion = staticCompositionLocalOf { false }
