package com.stuassistant.app.nativeapp

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Apply outside verticalScroll: coordinates belong to the viewport, not its moving content.
 * Scroll offset is read exclusively inside RenderNode / draw phases (zero recomposition during scroll),
 * and the GPU shader uses a precomputed 11-tap separable Gaussian kernel with cached RenderEffect instances.
 */
@Composable
internal fun Modifier.nativeTopSoftEdge(scroll: ScrollState, depth: Dp = 36.dp): Modifier {
    val density = LocalDensity.current
    val activationPx = with(density) { 24.dp.toPx() }.coerceAtLeast(1f)
    val depthPx = with(density) { depth.toPx() }
    val radiusPx = with(density) { 10.dp.toPx() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val renderer = if (Build.VERSION.SDK_INT >= 33) {
        remember { runCatching { TopEdgeRenderer() }.getOrNull() }
    } else {
        null
    }
    val fallbackCache = remember { LegacyMaskCache() }

    return this
        .onSizeChanged { viewport = it }
        .graphicsLayer {
            val vp = viewport
            val band = depthPx.coerceAtMost(vp.height * 0.25f)
            val amount = (scroll.value / activationPx).coerceIn(0f, 1f)
            clip = true
            if (amount > 0f && band > 0f && vp.width > 0 && vp.height > 0) {
                compositingStrategy = CompositingStrategy.Offscreen
                renderEffect = if (Build.VERSION.SDK_INT >= 33 && renderer != null) {
                    runCatching { renderer.effect(band, radiusPx, amount, vp) }.getOrNull()
                } else {
                    null
                }
            } else {
                compositingStrategy = CompositingStrategy.Auto
                renderEffect = null
            }
        }
        .drawWithContent {
            drawContent()
            if (Build.VERSION.SDK_INT < 33 || renderer == null) {
                val band = depthPx.coerceAtMost(size.height * 0.25f)
                val amount = (scroll.value / activationPx).coerceIn(0f, 1f)
                if (amount > 0f && band > 0f) {
                    drawRect(fallbackCache.mask(band, amount), blendMode = BlendMode.DstIn)
                }
            }
        }
}

private class LegacyMaskCache {
    private var lastBand = -1f
    private var lastStep = -1
    private var cached: Brush? = null

    fun mask(band: Float, amount: Float): Brush {
        val step = (amount * 32f).toInt().coerceIn(1, 32)
        val qAmount = step / 32f
        if (cached != null && lastBand == band && lastStep == step) return cached!!
        val brush = Brush.verticalGradient(
            *Array(17) { index ->
                val t = index / 16f
                val remaining = 1f - t
                t to Color.White.copy(alpha = 1f - 0.88f * remaining * remaining * remaining * qAmount)
            },
            endY = band.coerceAtLeast(1f),
        )
        lastBand = band
        lastStep = step
        cached = brush
        return brush
    }
}

@RequiresApi(33)
private class TopEdgeRenderer {
    private val horizontal = RuntimeShader(TOP_EDGE_SHADER)
    private val vertical = RuntimeShader(TOP_EDGE_SHADER)
    private var cachedDepth = -1f
    private var cachedStep = -1
    private var cachedViewport = IntSize.Zero
    private var cachedEffect: androidx.compose.ui.graphics.RenderEffect? = null

    fun effect(
        depth: Float,
        maxRadius: Float,
        amount: Float,
        viewport: IntSize,
    ): androidx.compose.ui.graphics.RenderEffect {
        val step = (amount * 48f).toInt().coerceIn(1, 48)
        val qAmount = step / 48f
        val existing = cachedEffect
        if (existing != null && cachedDepth == depth && cachedStep == step && cachedViewport == viewport) {
            return existing
        }
        val radius = maxRadius * qAmount
        fun pass(shader: RuntimeShader, isHorizontal: Boolean): RenderEffect {
            shader.setFloatUniform("depth", depth)
            shader.setFloatUniform("radius", radius)
            shader.setFloatUniform("fade", qAmount)
            shader.setFloatUniform(
                "bounds",
                viewport.width.coerceAtLeast(1).toFloat(),
                viewport.height.coerceAtLeast(1).toFloat(),
            )
            shader.setFloatUniform("axis", if (isHorizontal) 1f else 0f, if (isHorizontal) 0f else 1f)
            return RenderEffect.createRuntimeShaderEffect(shader, "content")
        }
        val built = RenderEffect.createChainEffect(pass(vertical, false), pass(horizontal, true)).asComposeRenderEffect()
        cachedDepth = depth
        cachedStep = step
        cachedViewport = viewport
        cachedEffect = built
        return built
    }
}

// Precomputed normalized 11-tap separable Gaussian weights (sigma = 2.2), zero runtime exp() calls.
// Note: Android AGSL (SkSL 1.0) requires untyped decimal literals (no 'f' or 'h' suffixes).
internal const val TOP_EDGE_SHADER = """
uniform shader content;
uniform float depth;
uniform float radius;
uniform float fade;
uniform float2 axis;
uniform float2 bounds;

half4 sampleAt(float2 p) { return content.eval(clamp(p, float2(0.5), bounds - 0.5)); }

half4 main(float2 p) {
    if (p.y >= depth) return content.eval(p);
    float remaining = 1.0 - clamp(p.y / depth, 0.0, 1.0);
    float strength = remaining * remaining * remaining;
    float r = radius * strength;
    float opacity = 1.0 - 0.88 * strength * fade * axis.y;
    if (r < 0.01) return content.eval(p) * opacity;
    float2 unit = axis * (r * 0.25);
    half4 c = sampleAt(p) * 0.175714;
    c += (sampleAt(p - unit * 1.0) + sampleAt(p + unit * 1.0)) * 0.158585;
    c += (sampleAt(p - unit * 2.0) + sampleAt(p + unit * 2.0)) * 0.116606;
    c += (sampleAt(p - unit * 3.0) + sampleAt(p + unit * 3.0)) * 0.069835;
    c += (sampleAt(p - unit * 4.0) + sampleAt(p + unit * 4.0)) * 0.034072;
    c += (sampleAt(p - unit * 5.0) + sampleAt(p + unit * 5.0)) * 0.033045;
    return c * opacity;
}
"""
