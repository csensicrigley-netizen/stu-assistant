package com.stuassistant.app.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion

private fun smoothEdge(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Fade the entire composed viewport, including its raw content. An overlay
 * alone leaves sharp, half-clipped glyphs visible underneath at the boundary.
 * Both ends reach zero with zero slope; the surrounding page is the background.
 */
internal fun Modifier.calendarViewportFeather(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    val edge = (size.height * .28f).coerceIn(56.dp.toPx(), 96.dp.toPx()).coerceAtMost(size.height / 2)
    val stops = Array(49) { i ->
        val p = i / 48f
        val distance = minOf(p, 1f - p) * size.height
        p to Color.Black.copy(alpha = smoothEdge(distance / (edge * .85f).coerceAtLeast(1f)))
    }
    drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
}

/** Two overlapping blur depths feather independently, without a tinted plate.
 * The source is the calendar's raw layer, never a layer containing this effect.
 */
@Composable internal fun BoxScope.CalendarEdgeMaterial(backdrop: Backdrop, edge: Dp) {
    val reduced = LocalLiquidGlassReducedMotion.current
    val shape = remember { RoundedRectangle(0.dp) }
    listOf(true, false).forEach { top ->
        listOf(1f to 4.dp, .64f to 12.dp).forEach { (coverage, radius) ->
            val stops = remember(top) { Array(17) { i ->
                val p = i / 16f
                val distance = if (top) p else 1f - p
                p to Color.Black.copy(alpha = 1f - smoothEdge(distance))
            } }
            Box(Modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth().height(edge * coverage)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(*stops), blendMode = BlendMode.DstIn)
                }
                .drawPlainBackdrop(backdrop = backdrop, shape = { shape }, effects = {
                    blur(radius.toPx())
                    if (!reduced && coverage < 1f) lens(4.dp.toPx(), 10.dp.toPx(), chromaticAberration = true)
                }))
        }
    }
}
