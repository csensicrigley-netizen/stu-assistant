package com.stuassistant.app.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import kotlin.math.roundToInt

/**
 * Compose integrates elapsed nanoseconds against the total duration. Lottie 6.7.1's
 * built-in driver truncates every frame delta to whole milliseconds, losing time at
 * 120/144Hz. Supply continuous progress directly to its vector renderer instead.
 */
internal class NativeAnnouncementPlayback {
    val progress = Animatable(0f)
    suspend fun play(durationMs: Float) {
        progress.animateTo(1f, tween(durationMs.roundToInt().coerceAtLeast(1), easing = LinearEasing))
    }
}
