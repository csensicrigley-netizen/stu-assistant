package com.stuassistant.app.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** App appearance overrides; the upstream renderer keeps its own defaults. */
internal val LocalLiquidGlassDarkTheme = staticCompositionLocalOf<Boolean?> { null }
internal val LocalLiquidGlassAccentColor = staticCompositionLocalOf<Color?> { null }
internal val LocalLiquidGlassReducedMotion = staticCompositionLocalOf { false }
internal const val AppThemeTransitionDurationMillis = 220
internal val LocalLiquidGlassThemeProgress = staticCompositionLocalOf<Float?> { null }

/** Shared light (0) to dark (1) progress; standalone controls retain their theme defaults. */
@Composable
internal fun liquidGlassThemeProgress(): Float =
    LocalLiquidGlassThemeProgress.current
        ?: if (LocalLiquidGlassDarkTheme.current ?: isSystemInDarkTheme()) 1f else 0f
