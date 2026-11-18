package com.stuassistant.app.nativeapp

import android.graphics.Color as AndroidColor
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.stuassistant.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

internal val LaunchPorcelainColor = Color(0xFFE8E8EA)
internal val LaunchObsidianColor = Color(0xFF0E0E0F)

internal fun launchStageColor(dark: Boolean): Color =
    if (dark) LaunchObsidianColor else LaunchPorcelainColor

internal fun smoothstepScalar(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0 + 1e-7f)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun evalCubicBezier(u: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val clamped = u.coerceIn(0f, 1f)
    if (clamped <= 0f) return 0f
    if (clamped >= 1f) return 1f
    var s = clamped
    repeat(14) {
        val mt = 1f - s
        val bx = 3f * mt * mt * s * x1 + 3f * mt * s * s * x2 + s * s * s - clamped
        val dbx = 3f * mt * mt * x1 + 6f * mt * s * (x2 - x1) + 3f * s * s * (1f - x2)
        s = (s - bx / max(1e-6f, dbx)).coerceIn(0f, 1f)
    }
    val mt = 1f - s
    return (3f * mt * mt * s * y1 + 3f * mt * s * s * y2 + s * s * s).coerceIn(0f, 1f)
}

/**
 * Asymmetric Ease-In-Out S-curve (`CubicBezier(0.45, 0.00, 0.16, 1.00)`):
 * - 0.00..0.25: Gentle zero-velocity Ease-In start (organic birth from center, zero flash pop)
 * - 0.25..0.50: Peak velocity mid-sweep opening the main card area smoothly
 * - 0.50..1.00: Long viscous Ease-Out deceleration settling into all 4 screen corners
 */
internal fun scurveRevealCurve(u: Float): Float =
    evalCubicBezier(u, 0.45f, 0.00f, 0.16f, 1.00f)

@Stable
internal class SundialLaunchState(activeInitially: Boolean) {
    var active by mutableStateOf(activeInitially)
    var virtualElapsedMs by mutableFloatStateOf(0f)
    var irisElapsedMs by mutableFloatStateOf(-1f)
    var revealTriggerMs by mutableFloatStateOf(-1f)
    var scaleAtTrigger by mutableFloatStateOf(0.964f)

    val irisActive: Boolean
        get() = active && irisElapsedMs >= 0f

    val irisNormalizedU: Float
        get() = if (irisElapsedMs < 0f) 0f else (irisElapsedMs / IRIS_DURATION_MS).coerceIn(0f, 1f)

    val irisSCurveProgress: Float
        get() = if (irisElapsedMs < 0f) 0f else scurveRevealCurve(irisNormalizedU)

    internal fun currentBreathScale(tMs: Float): Float {
        return if (tMs <= HARMONIC_BREATH_MS) {
            val preU = (tMs / HARMONIC_BREATH_MS).coerceIn(0f, 1f)
            1f - 0.036f * sin((0.5 * PI * preU).toFloat())
        } else {
            // Calm ambient breath if storage/migration takes longer than the initial 380ms beat
            val holdPhase = ((tMs - HARMONIC_BREATH_MS) / 900f) * (2f * PI.toFloat())
            0.972f - 0.008f * cos(holdPhase)
        }
    }

    /**
     * Frame-delta-clamped choreographer step (`dt <= 24ms`):
     * 1. Prevents first-frame layout spikes from ever skipping animation frames (eliminating flash cuts);
     * 2. Ensures the calm 350ms inward harmonic breath beat completes gracefully before releasing
     *    seamlessly into the 820ms S-curve frosted iris reveal.
     */
    fun stepFrame(rawFrameDeltaMs: Float, isReady: Boolean, reducedMotion: Boolean): Boolean {
        if (!active) return true
        if (reducedMotion && isReady) {
            irisElapsedMs = IRIS_DURATION_MS
            return true
        }
        val clampedDt = rawFrameDeltaMs.coerceIn(0f, MAX_FRAME_STEP_MS)
        val curMs = virtualElapsedMs + clampedDt
        virtualElapsedMs = curMs

        if (revealTriggerMs < 0f && isReady && curMs >= HARMONIC_RELEASE_MS) {
            revealTriggerMs = curMs
            scaleAtTrigger = currentBreathScale(curMs)
        }

        return if (revealTriggerMs >= 0f) {
            val revMs = (curMs - revealTriggerMs).coerceAtLeast(0f)
            irisElapsedMs = revMs
            revMs >= IRIS_DURATION_MS
        } else {
            false
        }
    }

    companion object {
        const val MAX_FRAME_STEP_MS = 24f
        const val HARMONIC_BREATH_MS = 380f
        const val HARMONIC_RELEASE_MS = 350f
        const val LOGO_EXIT_DURATION_MS = 440f
        const val IRIS_DURATION_MS = 820f
    }
}

/**
 * Applies the frame-clock-driven S-curve frosted iris reveal on GPU (API33+)
 * in Skia's native premultiplied color space (zero gamma shift, zero dark-mode end snap,
 * zero grey mud on Cherry Pink / Pine Light UIs), detaching completely once launch completes.
 */
@Composable
internal fun Modifier.launchIrisRevealEffect(
    state: SundialLaunchState,
    dark: Boolean,
): Modifier {
    if (!state.active) return this

    val density = LocalDensity.current.density
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val holder = remember { runCatching { SamePolarityIrisShaderHolder() }.getOrNull() }
        if (holder != null) {
            val uIris = state.irisNormalizedU
            val sProg = state.irisSCurveProgress
            val stageColorInt = if (dark) {
                AndroidColor.rgb(0x0E, 0x0E, 0x0F)
            } else {
                AndroidColor.rgb(0xE8, 0xE8, 0xEA)
            }
            return this.graphicsLayer {
                if (!state.irisActive) {
                    alpha = 0f
                    renderEffect = null
                } else {
                    alpha = 1f
                    renderEffect = holder.updateAndBuildEffect(
                        width = size.width.coerceAtLeast(1f),
                        height = size.height.coerceAtLeast(1f),
                        uIris = uIris,
                        sProg = sProg,
                        density = density,
                        stageColorInt = stageColorInt,
                    )
                }
            }
        }
    }

    // API 24..32 fallback: S-curve subtle depth settle paired with Offscreen concentric iris curtain
    return this.graphicsLayer {
        if (!state.irisActive) {
            alpha = 0f
        } else {
            val sp = state.irisSCurveProgress
            val sc = 0.980f + 0.020f * sp
            alpha = 1f
            scaleX = sc
            scaleY = sc
        }
    }
}

/**
 * Scheme 1 ("钟鸣微蓄 · 同色系 S 曲线柔雾涟漪"):
 * - Adaptive Light Porcelain (`#E8E8EA` + `truth_bell_bare_light`) & Dark Obsidian (`#0E0E0F` + `truth_bell_bare_dark`).
 * - Complete stationary sculpture from Frame 0 (zero curve-stroking), seamless 1:1 handoff from StartingWindow.
 * - Calm inward harmonic breath (`1.000 -> 0.964`) releasing smoothly along the asymmetric S-curve
 *   (`0.964 -> 1.118` + `0 -> 22.dp` Gaussian bokeh dissolve) over the 820ms S-curve frosted iris reveal.
 */
@Composable
internal fun SundialLaunchOverlay(
    state: SundialLaunchState,
    ready: Boolean,
    reducedMotion: Boolean,
    dark: Boolean,
    onFinished: () -> Unit,
) {
    if (!state.active) return

    val currentReady by rememberUpdatedState(ready)
    val currentReduced by rememberUpdatedState(reducedMotion)
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        var prevNanos = -1L
        while (state.active) {
            val done = withFrameNanos { frameNanos ->
                val rawDtMs = if (prevNanos < 0L) {
                    0f
                } else {
                    ((frameNanos - prevNanos) / 1_000_000.0).toFloat().coerceAtLeast(0f)
                }
                prevNanos = frameNanos
                state.stepFrame(
                    rawFrameDeltaMs = rawDtMs,
                    isReady = currentReady,
                    reducedMotion = currentReduced,
                )
            }
            if (done) {
                state.active = false
                currentOnFinished()
                break
            }
        }
    }

    val stageBg = launchStageColor(dark)
    val useGpuIris = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val tMs = state.virtualElapsedMs
    val irisMs = state.irisElapsedMs
    val exitU = if (irisMs >= 0f) (irisMs / SundialLaunchState.LOGO_EXIT_DURATION_MS).coerceIn(0f, 1f) else 0f

    val barePainter = painterResource(
        id = if (dark) R.drawable.truth_bell_bare_dark else R.drawable.truth_bell_bare_light,
    )

    // Deliberately draw-only: the launch animation must not delay interaction with the ready UI.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics {}
            .then(
                if (!state.irisActive) {
                    Modifier.drawWithContent {
                        drawRect(color = stageBg)
                        drawContent()
                    }
                } else if (!useGpuIris) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            val uIris = state.irisNormalizedU
                            val sp = state.irisSCurveProgress
                            val halfDiag = hypot(size.width * 0.5f, size.height * 0.5f)
                            val rampOn = smoothstepScalar(0f, 0.08f, uIris)
                            val frostPx = 82.dp.toPx() * rampOn
                            val veilPx = max(2f, 56.dp.toPx() * rampOn)
                            val rWave = sp * (halfDiag + 30.dp.toPx())
                            val rClear = max(0f, rWave - frostPx * (1f - 0.65f * uIris * uIris))
                            val rOuter = (rWave + veilPx).coerceAtLeast(4f)
                            val curtainAlpha = (1f - smoothstepScalar(0.74f, 1f, uIris)).coerceIn(0f, 1f)

                            if (curtainAlpha > 0.002f) {
                                drawRect(color = stageBg.copy(alpha = curtainAlpha))
                                val sClear = (rClear / rOuter).coerceIn(0.001f, 0.96f)
                                val sWave = (rWave / rOuter).coerceIn(sClear + 0.01f, 0.99f)
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.Black,
                                            sClear to Color.Black,
                                            sWave to Color.Black.copy(alpha = 0.48f),
                                            1.0f to Color.Transparent,
                                        ),
                                        center = center,
                                        radius = rOuter,
                                    ),
                                    radius = rOuter,
                                    center = center,
                                    blendMode = BlendMode.DstOut,
                                )
                            }
                            drawContent()
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (exitU < 0.998f) {
            val scaleVal: Float
            val blurDp: Float
            val alphaVal: Float

            if (irisMs < 0f) {
                scaleVal = state.currentBreathScale(tMs)
                blurDp = 0f
                alphaVal = 1f
            } else {
                val es = scurveRevealCurve(exitU)
                scaleVal = state.scaleAtTrigger + (1.118f - state.scaleAtTrigger) * es
                blurDp = 22f * exitU.pow(1.15f)
                alphaVal = (1f - smoothstepScalar(0.05f, 0.96f, exitU)).pow(1.45f)
            }

            Image(
                painter = barePainter,
                contentDescription = null,
                modifier = Modifier
                    .size(176.dp)
                    .graphicsLayer {
                        clip = false
                        scaleX = scaleVal
                        scaleY = scaleVal
                        alpha = alphaVal.coerceIn(0f, 1f)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.15f) {
                            val bPx = blurDp.dp.toPx()
                            renderEffect = BlurEffect(bPx, bPx, TileMode.Decal)
                        }
                    },
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class SamePolarityIrisShaderHolder {
    private val runtimeShader = RuntimeShader(IRIS_SAME_POLARITY_AGSL)

    fun updateAndBuildEffect(
        width: Float,
        height: Float,
        uIris: Float,
        sProg: Float,
        density: Float,
        stageColorInt: Int,
    ): androidx.compose.ui.graphics.RenderEffect {
        runtimeShader.setFloatUniform("resolution", width, height)
        runtimeShader.setFloatUniform("uIris", uIris)
        runtimeShader.setFloatUniform("sProg", sProg)
        runtimeShader.setFloatUniform("density", density)
        runtimeShader.setColorUniform("stageColor", stageColorInt)
        return AndroidRenderEffect
            .createRuntimeShaderEffect(runtimeShader, "uiContent")
            .asComposeRenderEffect()
    }
}

private const val IRIS_SAME_POLARITY_AGSL = """
uniform shader uiContent;
uniform float2 resolution;
uniform float uIris;
uniform float sProg;
uniform float density;
layout(color) uniform half4 stageColor;

half4 main(float2 fragCoord) {
    if (uIris >= 0.9995) {
        return uiContent.eval(fragCoord);
    }

    float2 center = resolution * 0.5;
    float halfDiag = length(center);
    float dist = length(fragCoord - center);

    float rampOn = smoothstep(0.0, 0.08, uIris);
    float frostMax = 82.0 * density;
    float veilMax = 55.0 * density;

    float frostBand = frostMax * rampOn;
    float veilBand = max(1.5, veilMax * rampOn);

    float rWave = sProg * (halfDiag + 30.0 * density);
    float rClear = max(0.0, rWave - frostBand * (1.0 - 0.65 * uIris * uIris));
    float rOuter = rWave + veilBand;

    float tFrost = smoothstep(rClear, max(rClear + 1.5, rWave), dist);
    float tCurtain = smoothstep(rWave - 6.0 * density * rampOn, rOuter, dist);
    float curtainOp = tCurtain * (1.0 - smoothstep(0.74, 1.0, uIris));

    if (curtainOp >= 0.999) {
        return half4(stageColor.rgb, 1.0);
    }

    float uiScale = 0.980 + 0.020 * sProg;
    float2 baseUv = clamp((fragCoord - center) / uiScale + center, float2(0.5), resolution - float2(0.5));

    half4 innerUi = uiContent.eval(baseUv);

    if (tFrost > 0.003) {
        float ign = fract(52.9829189 * fract(0.06711056 * fragCoord.x + 0.00583715 * fragCoord.y));
        float blurRad = 11.0 * density * tFrost;
        float baseAng = ign * 6.2831853;
        half4 frostAcc = half4(0.0);
        for (int i = 0; i < 12; ++i) {
            float fi = float(i);
            float r = sqrt((fi + 0.5) / 12.0) * blurRad;
            float ang = baseAng + fi * 2.3999632;
            float2 tapUv = clamp(baseUv + float2(cos(ang), sin(ang)) * r, float2(0.5), resolution - float2(0.5));
            frostAcc += uiContent.eval(tapUv);
        }
        half4 frostUi = frostAcc * half(1.0 / 12.0);
        innerUi = mix(innerUi, frostUi, half(tFrost));
    }

    return mix(innerUi, half4(stageColor.rgb, 1.0), half(curtainOp));
}
"""
