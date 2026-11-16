package com.stuassistant.app.nativeapp

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext

/**
 * Compose integrates elapsed nanoseconds against the total duration. Lottie 6.7.1's
 * built-in driver truncates every frame delta to whole milliseconds, losing time at
 * 120/144Hz. Supply continuous progress directly to its vector renderer instead.
 */
internal class NativeAnnouncementPlayback {
    val progress = mutableFloatStateOf(0f)
    suspend fun play(durationMs: Float, startedAtNanos: Long? = null) {
        val scale = coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
        if (scale <= 0f) { progress.floatValue = 1f; return }
        val durationNanos = durationMs.coerceAtLeast(1f).toDouble() * 1_000_000 * scale
        val start = startedAtNanos ?: withFrameNanos { it }
        do {
            withFrameNanos { frame ->
                // A composition loaded after presentation catches up to the same
                // timeline instead of holding frame zero and starting a late burst.
                progress.floatValue = ((frame - start) / durationNanos).toFloat().coerceIn(0f, 1f)
            }
        } while (progress.floatValue < 1f)
    }
}
