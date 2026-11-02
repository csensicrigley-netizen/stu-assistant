package com.stuassistant.app.nativeapp

import androidx.compose.foundation.gestures.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/** One owner for three-day pan, pinch, move and resize. Model changes never
 * restart input. All hit rectangles share the renderer's viewport calculation.
 */
@Composable internal fun Modifier.calendarGestures(date: String, slots: List<CalendarSlot>,
    viewport: () -> Pair<Float, Float>, updateViewport: (Float, Float) -> Unit, active: (Boolean) -> Unit,
    onEdit: (Int, String, Long, Long?) -> Unit, selectByDrag: (Int) -> Unit,
    onTap: (CalendarMark) -> Unit, onCreate: (Long) -> Unit, onScrollTick: () -> Unit): Modifier {
    val latestSlots by rememberUpdatedState(slots)
    val setActive by rememberUpdatedState(active)
    val currentViewport by rememberUpdatedState(viewport)
    val update by rememberUpdatedState(updateViewport)
    val edit by rememberUpdatedState(onEdit)
    val select by rememberUpdatedState(selectByDrag)
    val tap by rememberUpdatedState(onTap)
    val create by rememberUpdatedState(onCreate)
    val scrollTick by rememberUpdatedState(onScrollTick)
    return pointerInput(date) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            setActive(true)
            val (baseStart, baseMinutes) = currentViewport()
            val centerDay = AcademicClock.dayStart(date)
            val height = size.height.toFloat().coerceAtLeast(1f)
            val width = size.width.toFloat()
            val gutter = 52.dp.toPx()
            val dayWidth = ((width-gutter-12.dp.toPx())/3f).coerceAtLeast(1f)
            val geometry = calendarBoxes(latestSlots, centerDay, baseStart, baseMinutes, width, height, density)
            val box = geometry.asReversed().firstOrNull { b ->
                b.slot.mark.item >= 0 && down.position.x in b.left..b.right && down.position.y in b.top..b.bottom
            }
            val hit = box?.slot?.mark
            val grow = box != null && hit?.end == null && hit?.field == "schedule" &&
                down.position.x > box.right - 28.dp.toPx() && down.position.y > box.anchorY + 8.dp.toPx()
            val edge = if (hit?.end == null || box == null) 0 else when {
                down.position.y < box.top + 14.dp.toPx() && hit.start >= box.slot.midnight -> -1
                down.position.y > box.bottom - 14.dp.toPx() && hit.end <= box.slot.midnight+86400000L -> 1
                else -> 0
            }
            val tickMinutes = calendarMinorTick(baseMinutes)
            var lastTick = floor(baseStart/tickMinutes).toInt()
            var lastTickAt = 0L
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
                            val next = (minutes/zoom).coerceIn(120f, 1440f)
                            update((start + centroid.y/height*(minutes-next) - pan.y/height*minutes)
                                .coerceIn(0f,1440f-next), next)
                        }
                        event.changes.forEach { it.consume() }
                    } else if (multi) event.changes.forEach { it.consume() }
                    else {
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer != null) {
                            val delta = pointer.position - down.position
                            if (!moved && delta.getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                if (hit != null) select(hit.item)
                            }
                            if (moved && pointer.pressed) {
                                pointer.consume()
                                if (hit == null) {
                                    val start = (baseStart-delta.y/height*baseMinutes).coerceIn(0f,1440f-baseMinutes)
                                    update(start, baseMinutes)
                                    val tick = floor(start/tickMinutes).toInt()
                                    if (tick != lastTick) {
                                        lastTick = tick
                                        if (pointer.uptimeMillis-lastTickAt >= 80L) { scrollTick(); lastTickAt = pointer.uptimeMillis }
                                    }
                                } else {
                                    val shift = (delta.y/height*baseMinutes*60000).toLong()
                                    val originalDay = AcademicClock.dayStart(AcademicClock.moment(hit.start).date)
                                    val dayDelta = if (edge == 0 && !grow) {
                                        val targetColumn = floor((down.position.x+delta.x-gutter)/dayWidth).toInt().coerceIn(0,2)
                                        (centerDay+(targetColumn-1)*86400000L) - box.slot.midnight
                                    } else 0L
                                    val targetDay = originalDay + dayDelta
                                    a = when {
                                        edge == 1 || grow -> hit.start
                                        edge == -1 -> (hit.start+shift).coerceIn(originalDay, maxOf(originalDay, hit.end!!-60000L))
                                        else -> (hit.start+shift+dayDelta).coerceIn(targetDay, targetDay+86340000L)
                                    }
                                    b = when {
                                        grow -> (hit.start+shift).coerceAtMost(originalDay+86400000L).takeIf { it > hit.start }
                                        hit.end == null -> null
                                        edge == -1 -> hit.end
                                        edge == 1 -> (hit.end+shift).coerceAtMost(originalDay+86400000L).takeIf { it > hit.start+300000L }
                                        else -> a + hit.end-hit.start
                                    }
                                    edit(hit.item, hit.field, a, b); edited = true
                                }
                            }
                            if (!pointer.pressed && !moved) {
                                pointer.consume()
                                if (hit != null) tap(hit)
                                else if (down.position.x >= gutter && pointer.uptimeMillis-down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                                    val column = floor((down.position.x-gutter)/dayWidth).toInt().coerceIn(0,2)
                                    val day = centerDay+(column-1)*86400000L
                                    create(CalendarDrafts.snap(day+((baseStart+down.position.y/height*baseMinutes)*60000).toLong()))
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
                setActive(false)
            }
        }
    }
}

internal fun calendarMinorTick(minutes: Float): Int = when {
    minutes <= 180 -> 15
    minutes <= 480 -> 30
    minutes <= 900 -> 60
    else -> 120
}
