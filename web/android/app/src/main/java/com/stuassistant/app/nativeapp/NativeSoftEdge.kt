package com.stuassistant.app.nativeapp

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged

/** Apply outside verticalScroll: coordinates belong to the viewport, not its moving content.
 * Only the top band changes. No bitmap capture, backdrop feedback or bottom treatment.
 */
@Composable
internal fun Modifier.nativeTopSoftEdge(scroll: ScrollState, depth: Dp = 36.dp): Modifier {
    val density = LocalDensity.current
    val activationPx = with(density) { 24.dp.toPx() }
    // Follow the gesture directly: no binary activation or delayed fade after one pixel.
    val amount by remember(scroll, activationPx) {
        derivedStateOf { (scroll.value / activationPx).coerceIn(0f, 1f) }
    }
    val depthPx = with(density) { depth.toPx() }
    val radiusPx = with(density) { 10.dp.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val band = depthPx.coerceAtMost(viewport.height * .25f)
    val renderer = if (Build.VERSION.SDK_INT >= 33) remember { TopEdgeRenderer() } else null
    val effect = if (Build.VERSION.SDK_INT >= 33 && amount > 0f && band > 0f) {
        remember(renderer, band, radiusPx, amount, viewport) { renderer!!.effect(band, radiusPx * amount, amount, viewport) }
    } else null
    // A long, low-strength tail avoids a visible seam. The extreme top dissolves into
    // the surrounding surface; readable content returns well before the band's end.
    val mask = remember(band, amount) {
        Brush.verticalGradient(*Array(17) { index ->
            val t = index / 16f
            val remaining = 1f - t
            t to Color.White.copy(alpha = 1f - .88f * remaining * remaining * remaining * amount)
        }, endY = band.coerceAtLeast(1f))
    }
    return this.onSizeChanged { viewport = it }.graphicsLayer {
        clip = true
        renderEffect = effect
        compositingStrategy = if (amount > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        // Older devices fade only. Isolate DstIn from the page/dialog behind this layer.
        if (Build.VERSION.SDK_INT < 33 && amount > 0f && band > 0f) {
            drawRect(mask, blendMode = BlendMode.DstIn)
        }
    }
}

@RequiresApi(33)
private class TopEdgeRenderer {
    // Compile once per viewport owner, not once per animation frame or scroll event.
    private val horizontal = RuntimeShader(TOP_EDGE_SHADER)
    private val vertical = RuntimeShader(TOP_EDGE_SHADER)
    fun effect(depth: Float, radius: Float, amount: Float, viewport: IntSize): androidx.compose.ui.graphics.RenderEffect {
        fun pass(shader: RuntimeShader, horizontal: Boolean): RenderEffect {
            shader.setFloatUniform("depth", depth)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("fade", amount)
            shader.setFloatUniform("bounds", viewport.width.toFloat(), viewport.height.toFloat())
            shader.setFloatUniform("axis", if (horizontal) 1f else 0f, if (horizontal) 0f else 1f)
            return RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
        return RenderEffect.createChainEffect(pass(vertical, false), pass(horizontal, true)).asComposeRenderEffect()
    }
}

// Dense Gaussian taps prevent repeated text contours at high display densities.
// Radius varies spatially, rather than blending a uniformly blurred panel over sharp text.
private const val TOP_EDGE_SHADER = """
uniform shader content;
uniform float depth;
uniform float radius;
uniform float fade;
uniform float2 axis;
uniform float2 bounds;
half4 sampleAt(float2 p) { return content.eval(clamp(p, float2(0.5), bounds - 0.5)); }
half4 main(float2 p) {
    float remaining = 1.0 - clamp(p.y / depth, 0.0, 1.0);
    float strength = remaining * remaining * remaining;
    float r = radius * strength;
    // Fade only in the final pass, exposing the actual page/glass surface instead
    // of painting an approximate opaque theme color across a transparent sheet.
    float opacity = 1.0 - 0.88 * strength * fade * axis.y;
    if (r < 0.01) return content.eval(p) * opacity;
    half4 c = half4(0.0);
    float weights = 0.0;
    for (int i = -16; i <= 16; i++) {
        float x = float(i) / 8.0;
        float w = exp(-0.5 * x * x);
        c += sampleAt(p + axis * r * 0.5 * x) * w;
        weights += w;
    }
    return c / weights * opacity;
}
"""
