package com.stuassistant.app.nativeapp

/** Single-day interval partitioning, informed by super-calendar's layoutDayEvents.
 * Actual instants remain on the mark; clipping and minimum size are display-only.
 */
internal data class CalendarSlot(val mark: CalendarMark, val column: Int, val columns: Int,
    val start: Long, val end: Long, val midnight: Long)

internal fun calendarDaySlots(marks: List<CalendarMark>, midnight: Long): List<CalendarSlot> {
    val dayEnd = midnight + 86400000L
    data class Segment(val mark: CalendarMark, val start: Long, val end: Long)
    val segments = marks.mapNotNull { mark ->
        val end = mark.end ?: (mark.start + 1L)
        if (mark.start >= dayEnd || end <= midnight) null
        else Segment(mark, maxOf(mark.start, midnight), minOf(end, dayEnd))
    }.sortedWith(compareBy<Segment> { it.start }.thenByDescending { it.end }.thenBy { it.mark.key })
    val result = mutableListOf<CalendarSlot>()
    val cluster = mutableListOf<Segment>()
    var clusterEnd = Long.MIN_VALUE
    fun flush() {
        val ends = mutableListOf<Long>()
        val assigned = cluster.map { segment ->
            val available = ends.indexOfFirst { it <= segment.start }
            val column = if (available < 0) ends.size.also { ends.add(segment.end) }
                else available.also { ends[it] = segment.end }
            segment to column
        }
        assigned.forEach { (segment, column) ->
            result += CalendarSlot(segment.mark, column, ends.size, segment.start, segment.end, midnight)
        }
        cluster.clear()
    }
    for (segment in segments) {
        if (cluster.isNotEmpty() && segment.start >= clusterEnd) flush()
        cluster += segment
        clusterEnd = maxOf(clusterEnd, segment.end)
    }
    if (cluster.isNotEmpty()) flush()
    return result
}

internal data class CalendarBox(val slot: CalendarSlot, val left: Float, val top: Float,
    val width: Float, val height: Float, val anchorY: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

/** Drawing and hit testing must use exactly the same day/viewport mapping. */
internal fun calendarBoxes(slots: List<CalendarSlot>, midnight: Long, startMinutes: Float,
    minutes: Float, width: Float, height: Float, density: Float): List<CalendarBox> {
    val gutter = 52f * density
    val laneWidth = (width - gutter - 12f*density).coerceAtLeast(1f) / 3f
    return slots.map { slot ->
        val columnWidth = laneWidth / slot.columns
        val dayIndex = ((slot.midnight - midnight) / 86400000L).toInt() + 1
        val left = gutter + dayIndex * laneWidth + slot.column * columnWidth + 3f*density
        val point = slot.mark.end == null
        fun y(time: Long) = ((time-slot.midnight)/60000f-startMinutes)/minutes * height
        val anchor = y(slot.start)
        CalendarBox(slot, left, if (point) anchor-24f*density else anchor,
            (columnWidth-6f*density).coerceAtLeast(1f),
            if (point) 48f*density else (y(slot.end)-anchor).coerceAtLeast(44f*density), anchor)
    }
}
