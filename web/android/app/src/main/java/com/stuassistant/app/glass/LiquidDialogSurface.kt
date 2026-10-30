/*
 * Source: Kyant0/AndroidLiquidGlass, licensed under Apache-2.0.
 * Upstream: https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/destinations/DialogContent.kt
 * Commit: 65ab177e90e5c1d8c62e70cf7755841982da65f6
 * Extracts only the dialog container; adaptations are recorded in UPSTREAM.md.
 */
package com.stuassistant.app.glass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.RoundedRectangularShape

/**
 * Official DialogContent surface, without its demo text, buttons, dim layer or positioning.
 * The caller owns dialog semantics, focus, dismissal and layout, and supplies a sibling source.
 */
@Composable
fun LiquidDialogSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: RoundedRectangularShape = RoundedRectangle(48.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val isLightTheme = !(LocalLiquidGlassDarkTheme.current ?: isSystemInDarkTheme())
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.6f)
        else Color(0xFF121212).copy(0.4f)

    Column(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                colorControls(
                    brightness = if (isLightTheme) 0.2f else 0f,
                    saturation = 1.5f,
                )
                blur(if (isLightTheme) 16f.dp.toPx() else 8f.dp.toPx())
                lens(24f.dp.toPx(), 48f.dp.toPx(), depthEffect = true)
            },
            highlight = { Highlight.Plain },
            onDrawSurface = { drawRect(containerColor) },
        ),
        content = content,
    )
}
