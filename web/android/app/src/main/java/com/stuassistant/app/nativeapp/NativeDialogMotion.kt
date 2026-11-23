package com.stuassistant.app.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal val LocalNativeDialogStart = staticCompositionLocalOf<State<Long?>?> { null }

/** All channels use Compose's display frame clock, never a fixed-FPS timer. */
internal class NativeDialogMotion {
    val startedAtNanos = mutableStateOf<Long?>(null)
    val arrival = Animatable(0f)
    val departure = Animatable(0f)
    val opacity = Animatable(0f)
    val scrim = Animatable(0f)
    val isRunning get() = arrival.isRunning || departure.isRunning || opacity.isRunning || scrim.isRunning

    suspend fun enter(reduced: Boolean) {
        // Record the glass and its actual page backdrop before beginning the reveal.
        withFrameNanos { }
        startedAtNanos.value = withFrameNanos { it }
        coroutineScope {
            launch { arrival.animateTo(1f, tween(if (reduced) 0 else 560, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) }
            launch { opacity.animateTo(1f, tween(if (reduced) 140 else 260, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) }
            launch { scrim.animateTo(1f, tween(if (reduced) 140 else 320, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))) }
        }
    }

    suspend fun exit(reduced: Boolean) = coroutineScope {
        // Continue from current values if dismissed during entrance; no snap to 1.
        launch { departure.animateTo(1f, tween(if (reduced) 0 else 260, easing = CubicBezierEasing(.4f, 0f, 1f, 1f))) }
        launch { opacity.animateTo(0f, tween(if (reduced) 140 else 240, easing = CubicBezierEasing(.4f, 0f, 1f, 1f))) }
        launch { scrim.animateTo(0f, tween(if (reduced) 140 else 260, easing = CubicBezierEasing(.4f, 0f, 1f, 1f))) }
    }
}
