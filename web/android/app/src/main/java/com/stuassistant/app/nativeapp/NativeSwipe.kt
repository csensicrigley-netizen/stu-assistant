package com.stuassistant.app.nativeapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Ergonomic horizontal swipe modifier designed for mobile thumb navigation:
 * - Angle-locks strictly when horizontal intent exceeds 1.4x vertical travel, so vertical
 *   scrolling and inner horizontal chip strips never suffer gesture interference.
 * - Updates drag offset synchronously without per-frame coroutine allocations, reading it
 *   exclusively inside RenderNode (`graphicsLayer`) for zero layout recomposition.
 * - Settles via critically damped spring physics and plays light selection haptics on commit.
 */
internal fun Modifier.ergonomicHorizontalSwipe(
    enabled: Boolean = true,
    canSwipePrevious: Boolean = true,
    canSwipeNext: Boolean = true,
    onSwipePrevious: () -> Unit,
    onSwipeNext: () -> Unit,
): Modifier = composed {
    if (!enabled) return@composed Modifier
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reducedMotion = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    val settleJob = remember { arrayOfNulls<Job>(1) }

    val latestCanPrev = rememberUpdatedState(canSwipePrevious)
    val latestCanNext = rememberUpdatedState(canSwipeNext)
    val latestPrev = rememberUpdatedState(onSwipePrevious)
    val latestNext = rememberUpdatedState(onSwipeNext)
    val latestReduced = rememberUpdatedState(reducedMotion)

    val distanceThresholdPx = with(density) { 52.dp.toPx() }
    val velocityThresholdPx = with(density) { 440.dp.toPx() }
    val maxFollowPx = with(density) { 32.dp.toPx() }

    this
        .graphicsLayer {
            val x = dragOffsetPx
            translationX = x
            val progress = (abs(x) / (maxFollowPx * 3.2f)).coerceIn(0f, 1f)
            val s = 1f - 0.010f * progress
            scaleX = s
            scaleY = s
        }
        .pointerInput(distanceThresholdPx, velocityThresholdPx) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val pointerId = down.id
                val velocityTracker = VelocityTracker()
                velocityTracker.addPosition(down.uptimeMillis, down.position)

                var totalDx = 0f
                var totalDy = 0f
                var lockedHorizontal = false
                var rejected = false

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!change.pressed) {
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        break
                    }
                    if (!lockedHorizontal && change.isConsumed) {
                        rejected = true
                        break
                    }
                    val delta = change.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y
                    velocityTracker.addPosition(change.uptimeMillis, change.position)

                    if (!lockedHorizontal) {
                        val absX = abs(totalDx)
                        val absY = abs(totalDy)
                        if (absY > slop && absY >= absX * 0.85f) {
                            rejected = true
                            break
                        }
                        if (absX > slop && absX > absY * 1.4f) {
                            lockedHorizontal = true
                            settleJob[0]?.cancel()
                            change.consume()
                        }
                    } else {
                        change.consume()
                        if (!latestReduced.value) {
                            val allowed = (totalDx > 0f && latestCanPrev.value) || (totalDx < 0f && latestCanNext.value)
                            val factor = if (allowed) 0.26f else 0.07f
                            dragOffsetPx = (totalDx * factor).coerceIn(-maxFollowPx, maxFollowPx)
                        }
                    }
                }

                if (lockedHorizontal && !rejected) {
                    val vx = velocityTracker.calculateVelocity().x
                    val triggerPrev = latestCanPrev.value &&
                        (totalDx > distanceThresholdPx || (totalDx > slop * 1.5f && vx > velocityThresholdPx))
                    val triggerNext = latestCanNext.value &&
                        (totalDx < -distanceThresholdPx || (totalDx < -slop * 1.5f && vx < -velocityThresholdPx))

                    if (triggerPrev) {
                        tick()
                        latestPrev.value()
                    } else if (triggerNext) {
                        tick()
                        latestNext.value()
                    }
                }

                if (dragOffsetPx != 0f) {
                    settleJob[0]?.cancel()
                    if (latestReduced.value) {
                        dragOffsetPx = 0f
                    } else {
                        val startOffset = dragOffsetPx
                        settleJob[0] = scope.launch {
                            animate(
                                initialValue = startOffset,
                                targetValue = 0f,
                                animationSpec = spring(dampingRatio = 0.82f, stiffness = 560f),
                            ) { value, _ -> dragOffsetPx = value }
                        }
                    }
                }
            }
        }
}

private class DirectionalTransitionHistory<T>(
    var committedState: T,
    var committedOrder: Comparable<*>,
    var lastDirection: Float = 1f,
)

/**
 * Single-subtree Telegram-silky directional spring container for date/week/segment stepping.
 * Avoids dual-subtree composition and layout thrashing on large scroll lists while delivering
 * responsive GPU-only (`graphicsLayer`) directional glide and fade.
 *
 * Keys `Animatable` directly by `targetState` and unconditionally reads `progress.value` inside
 * `graphicsLayer` on every frame so Compose's `SnapshotStateObserver` always tracks the animation.
 */
@Composable
internal fun <T> NativeDirectionalContent(
    targetState: T,
    orderOf: (T) -> Comparable<*>,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") label: String = "directional-content",
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalLiquidGlassReducedMotion.current
    val density = LocalDensity.current
    val slideDistancePx = with(density) { 18.dp.toPx() }
    val targetOrder = orderOf(targetState)
    val history = remember { DirectionalTransitionHistory(targetState, targetOrder) }
    val isNewTarget = history.committedState != targetState
    val activeDirection = if (isNewTarget) {
        @Suppress("UNCHECKED_CAST")
        val prev = history.committedOrder as Comparable<Any>
        if (prev.compareTo(targetOrder as Any) <= 0) 1f else -1f
    } else {
        history.lastDirection
    }
    val progress = remember(targetState) {
        Animatable(if (!isNewTarget || reduced) 1f else 0f)
    }

    SideEffect {
        if (history.committedState != targetState) {
            history.committedState = targetState
            history.committedOrder = targetOrder
            history.lastDirection = activeDirection
        }
    }

    LaunchedEffect(targetState, reduced) {
        if (reduced) {
            progress.snapTo(1f)
        } else if (progress.value < 1f) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 520f, visibilityThreshold = 0.002f),
            )
        }
    }

    Box(
        modifier = modifier.graphicsLayer {
            // Unconditionally read progress.value first so SnapshotStateObserver always registers it.
            val rawProgress = progress.value
            val p = if (reduced) 1f else rawProgress
            val clamped = p.coerceIn(0f, 1f)
            translationX = activeDirection * slideDistancePx * (1f - p)
            alpha = 0.38f + 0.62f * clamped
            val scale = 0.992f + 0.008f * clamped
            scaleX = scale
            scaleY = scale
        },
    ) {
        key(targetState) {
            content(targetState)
        }
    }
}
