package com.stuassistant.app.nativeapp

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

internal object NativeMotion {
    const val PressCard: Float = 0.03f
    const val PressRow: Float = 0.02f
    const val PressIcon: Float = 0.12f
    const val PressCell: Float = 0.06f

    private val FastOutSlowIn: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    private val ExitCurve: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    fun <T> press(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 900.0f, visibilityThreshold = visibilityThreshold)

    fun <T> release(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 520.0f, visibilityThreshold = visibilityThreshold)

    fun <T> select(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 420.0f, visibilityThreshold = visibilityThreshold)

    fun <T> pop(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.65f, stiffness = 480.0f, visibilityThreshold = visibilityThreshold)

    fun <T> sheet(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.86f, stiffness = 360.0f, visibilityThreshold = visibilityThreshold)

    fun <T> page(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.9f, stiffness = 340.0f, visibilityThreshold = visibilityThreshold)

    fun <T> dialog(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 440.0f, visibilityThreshold = visibilityThreshold)

    fun expand(): SpringSpec<IntSize> =
        spring(dampingRatio = 0.88f, stiffness = 380.0f, visibilityThreshold = IntSize(1, 1))

    fun collapse(): SpringSpec<IntSize> =
        spring(dampingRatio = 1.0f, stiffness = 500.0f, visibilityThreshold = IntSize(1, 1))

    fun enterOffset(): SpringSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = 380.0f, visibilityThreshold = IntOffset(1, 1))

    fun fadeIn(durationMillis: Int = 180): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = durationMillis, easing = FastOutSlowIn))

    fun fadeOut(durationMillis: Int = 130): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = durationMillis, easing = ExitCurve))

    fun <T> exit(durationMillis: Int = 150): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = ExitCurve)

    fun dpSelect(): SpringSpec<Dp> =
        spring(dampingRatio = 0.8f, stiffness = 420.0f, visibilityThreshold = Dp.VisibilityThreshold)
}

@Composable
internal fun rememberPressScale(
    pressed: Boolean,
    depth: Float,
    enabled: Boolean = true,
): State<Float> {
    val target = if (enabled && pressed) (1f - depth).coerceIn(0.84f, 1f) else 1f
    val anim = remember { Animatable(1f, Float.VectorConverter, 0.001f) }
    LaunchedEffect(target) {
        val spec = if (target < anim.value) {
            NativeMotion.press(0.001f)
        } else {
            NativeMotion.release(0.001f)
        }
        anim.animateTo(target, spec)
    }
    return anim.asState()
}

internal fun Modifier.nativeScale(scale: State<Float>): Modifier =
    graphicsLayer {
        val v = scale.value
        scaleX = v
        scaleY = v
    }
