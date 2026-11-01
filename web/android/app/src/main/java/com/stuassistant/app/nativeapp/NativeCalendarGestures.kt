package com.stuassistant.app.nativeapp

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp

/** One gesture owner across empty track, event bodies and grips. Viewport changes
 * never restart pointer input; a second finger always promotes the gesture to zoom. */
@Composable internal fun Modifier.calendarGestures(date: String, marks: List<CalendarMark>, scale: Float,
    viewport: () -> Pair<Float, Float>, updateViewport: (Float, Float) -> Unit, active: (Boolean) -> Unit,
    onEdit: (Int, String, Long, Long?) -> Unit, selectByDrag: (Int) -> Unit,
    onTap: (CalendarMark) -> Unit, onCreate: (Long) -> Unit): Modifier {
    val latestMarks by rememberUpdatedState(marks)
    val currentViewport by rememberUpdatedState(viewport)
    val update by rememberUpdatedState(updateViewport)
    val edit by rememberUpdatedState(onEdit)
    val select by rememberUpdatedState(selectByDrag)
    val tap by rememberUpdatedState(onTap)
    val create by rememberUpdatedState(onCreate)
    val currentScale by rememberUpdatedState(scale)
    return pointerInput(date) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            active(true)
            val (baseStart, baseMinutes) = currentViewport()
            val midnight = AcademicClock.dayStart(date)
            val width = size.width.toFloat()
            fun x(time: Long) = ((time - midnight) / 60000f - baseStart) / baseMinutes * width
            val sy = currentScale
            val hit = latestMarks.asReversed().firstOrNull { mark ->
                if (mark.item < 0) false else {
                    val a = x(mark.start)
                    val right = mark.end?.let(::x) ?: a
                    val left = if (mark.end == null) a - 24.dp.toPx() else a
                    val end = if (mark.end == null) a + 56.dp.toPx() else maxOf(right, a + 48.dp.toPx())
                    down.position.x in left..end && down.position.y in (66.dp.toPx()*sy)..(210.dp.toPx()*sy)
                }
            }
            val grow = hit?.let { it.end == null && it.field == "schedule" &&
                down.position.x > x(it.start) + 18.dp.toPx() && down.position.y in (112.dp.toPx()*sy)..(156.dp.toPx()*sy) } == true
            val edge = if (hit?.end == null) 0 else when {
                down.position.x < x(hit.start) + 18.dp.toPx() -> -1
                down.position.x > maxOf(x(hit.end), x(hit.start) + 48.dp.toPx()) - 18.dp.toPx() -> 1
                else -> 0
            }
            var multi = false
            var moved = false
            var edited = false
            var complete = false
            var a = hit?.start ?: 0L
            var b = hit?.end
            try {
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2) {
                        if (!multi && edited && hit != null) { edit(hit.item, hit.field, hit.start, hit.end); edited = false }
                        multi = true
                        val (start, minutes) = currentViewport()
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (centroid != Offset.Unspecified && zoom.isFinite() && zoom > 0f) {
                            val next = (minutes / zoom).coerceIn(120f, 1440f)
                            update((start + centroid.x / width * (minutes - next) - pan.x / width * minutes).coerceIn(0f, 1440f-next), next)
                        }
                        event.changes.forEach { it.consume() }
                    } else if (multi) {
                        event.changes.forEach { it.consume() }
                    } else {
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer != null) {
                            val delta = pointer.position - down.position
                            if (!moved && delta.getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                if (hit != null) select(hit.item)
                            }
                            if (moved && pointer.pressed) {
                                pointer.consume()
                                if (hit == null) update((baseStart - delta.x / width * baseMinutes).coerceIn(0f,1440f-baseMinutes), baseMinutes)
                                else {
                                    val shift = (delta.x / width * baseMinutes * 60000).toLong()
                                    a = if (edge == 1 || grow) hit.start else (hit.start + shift).coerceIn(midnight, midnight+86340000)
                                    b = when {
                                        grow -> (hit.start + shift).coerceAtMost(midnight+86400000).takeIf { it > hit.start }
                                        hit.end == null -> null
                                        edge == -1 -> hit.end.coerceAtLeast(a+60000)
                                        edge == 1 -> (hit.end+shift).coerceAtMost(midnight+86400000).takeIf { it > hit.start+300000 }
                                        else -> a + (hit.end-hit.start)
                                    }
                                    edit(hit.item, hit.field, a, b); edited = true
                                }
                            }
                            if (!pointer.pressed && !moved) {
                                pointer.consume()
                                if (hit != null) tap(hit)
                                else if (pointer.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                                    create(CalendarDrafts.snap(midnight+((baseStart+down.position.x/width*baseMinutes)*60000).toLong()))
                                }
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (!multi && edited && hit != null) {
                    val start = CalendarDrafts.snap(a)
                    edit(hit.item, hit.field, start, b?.let(CalendarDrafts::snap)?.takeIf { it > start })
                }
                complete = true
            } finally {
                if (!complete && edited && hit != null) edit(hit.item, hit.field, hit.start, hit.end)
                active(false)
            }
        }
    }
}
