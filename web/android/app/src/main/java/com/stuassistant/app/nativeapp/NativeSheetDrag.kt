package com.stuassistant.app.nativeapp

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class NativeSheetDrag(
    private val scope: CoroutineScope,
    private val dismissDistance: Float,
    private val dismissVelocity: Float,
    private val active: State<Boolean>,
    private val reducedMotion: State<Boolean>,
    private val onDismiss: State<() -> Unit>,
) {
    private var distance by mutableFloatStateOf(0f)
    private var settling: Job? = null
    private var contentReleased = false
    val offsetPx: Float get() = distance * 0.6f
    val scrimFraction: Float get() = 1f - (distance / (dismissDistance * 2f)).coerceIn(0f, 1f)

    val contentScroll = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || distance <= 0f || available.y >= 0f) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The content gets the downward delta first. The panel gets what remains at the top.
            if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
            return Offset(0f, dragBy(available.y))
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (!active.value || distance <= 0f) return Velocity.Zero
            if (contentReleased) release(available.y) else settle()
            return Velocity(0f, available.y)
        }
    }

    fun startContentGesture() {
        contentReleased = false
    }

    fun finishContentGesture(released: Boolean) {
        contentReleased = released
        if (!released) settle()
    }

    fun stopSettling() {
        settling?.cancel()
        settling = null
    }

    fun reset() {
        stopSettling()
        distance = 0f
    }

    fun dragBy(delta: Float): Float {
        if (!active.value || delta == 0f) return 0f
        stopSettling()
        val previous = distance
        distance = (previous + delta).coerceAtLeast(0f)
        return distance - previous
    }

    fun release(velocity: Float) {
        if (!active.value || distance <= 0f) return
        if (distance >= dismissDistance || velocity >= dismissVelocity) onDismiss.value()
        else settle(velocity)
    }

    fun settle(velocity: Float = 0f) {
        stopSettling()
        if (!active.value || distance <= 0f) return
        if (reducedMotion.value) {
            distance = 0f
        } else {
            settling = scope.launch {
                animate(
                    initialValue = distance,
                    targetValue = 0f,
                    initialVelocity = velocity,
                    animationSpec = spring(dampingRatio = 0.8333333f, stiffness = 400f),
                ) { value, _ -> distance = value.coerceAtLeast(0f) }
            }
        }
    }
}

@Composable
internal fun rememberNativeSheetDrag(
    active: Boolean,
    open: Boolean = active,
    dismissDistance: Dp = 110.dp,
    onDismiss: () -> Unit,
): NativeSheetDrag {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val latestActive = rememberUpdatedState(active)
    val latestReduced = rememberUpdatedState(reduced)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val drag = remember(scope, density, dismissDistance) {
        with(density) {
            NativeSheetDrag(scope, dismissDistance.toPx(), 600.dp.toPx(), latestActive, latestReduced, latestDismiss)
        }
    }
    LaunchedEffect(drag, active, open, reduced) {
        if (open) drag.reset() else drag.stopSettling()
    }
    return drag
}

internal fun Modifier.nativeSheetDragContent(drag: NativeSheetDrag, active: Boolean): Modifier =
    nestedScroll(drag.contentScroll).pointerInput(drag, active) {
        if (!active) return@pointerInput
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            drag.startContentGesture()
            // Scrollable can dispatch a zero-velocity fling on both release and cancellation.
            // The initial pass sees a real up before Scrollable and a consumed up on cancellation.
            var event = awaitPointerEvent(PointerEventPass.Initial)
            while (event.changes.any { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
            drag.finishContentGesture(event.changes.any { it.changedToUp() })
        }
    }

internal fun Modifier.nativeSheetDragHandle(drag: NativeSheetDrag, active: Boolean): Modifier =
    pointerInput(drag, active) {
        if (!active) return@pointerInput
        val tracker = VelocityTracker()
        detectDragGestures(
            orientationLock = Orientation.Vertical,
            onDragStart = { down, slopTrigger, overSlop ->
                drag.stopSettling()
                tracker.resetTracking()
                tracker.addPointerInputChange(down)
                drag.dragBy(slopTrigger.position.y - down.position.y - overSlop.y)
            },
            onDrag = { change, amount ->
                tracker.addPointerInputChange(change)
                drag.dragBy(amount.y)
                change.consume()
            },
            onDragEnd = { up ->
                tracker.addPointerInputChange(up)
                drag.release(tracker.calculateVelocity().y)
            },
            onDragCancel = { drag.settle() },
        )
    }
