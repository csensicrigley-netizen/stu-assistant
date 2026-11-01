package com.stuassistant.app.nativeapp

import org.junit.Assert.*
import org.junit.Test

class NativeCalendarLayoutTest {
    private val day = AcademicClock.dayStart("2026-11-02")
    private fun mark(key: String, start: Int, end: Int?) = CalendarMark(key, key,
        day + start * 60000L, end?.let { day + it * 60000L })

    @Test fun chainedOverlapsShareColumnsAndTouchingEventsReuseSpace() {
        val slots = calendarDaySlots(listOf(mark("a", 600, 660), mark("b", 630, 690),
            mark("c", 660, 720), mark("d", 720, 780)), day).associateBy { it.mark.key }
        assertEquals(2, slots.getValue("a").columns)
        assertNotEquals(slots.getValue("a").column, slots.getValue("b").column)
        assertEquals(slots.getValue("a").column, slots.getValue("c").column)
        assertEquals(1, slots.getValue("d").columns)
    }

    @Test fun overnightSegmentsClipWithoutMutatingActualInstants() {
        val overnight = mark("night", 1380, 1500)
        val today = calendarDaySlots(listOf(overnight), day).single()
        val tomorrow = calendarDaySlots(listOf(overnight), day + 86400000).single()
        assertEquals(day + 86400000, today.end)
        assertEquals(day + 86400000, tomorrow.start)
        assertEquals(overnight, today.mark)
        assertEquals(overnight, tomorrow.mark)
    }

    @Test fun pointKeepsExactTimeAndNullDurationEvenWhenOverlapping() {
        val point = mark("ddl", 660, null)
        val slot = calendarDaySlots(listOf(mark("class", 600, 720), point), day).last()
        val box = calendarBoxes(listOf(slot), day, 600f, 240f, 360f, 400f, 1f).single()
        assertNull(box.slot.mark.end)
        assertEquals(day + 660 * 60000L, box.slot.mark.start)
        assertEquals(100f, box.anchorY, .01f)
        assertEquals(2, slot.columns)
    }

    @Test fun threeDaysShareVerticalTimeAndDifferentHorizontalColumns() {
        val slots = (-1..1).flatMap { offset ->
            val start = day + offset * 86400000L
            calendarDaySlots(listOf(CalendarMark("$offset", "item", start + 600*60000L, start + 660*60000L)), start)
        }
        val boxes = calendarBoxes(slots, day, 480f, 360f, 360f, 480f, 1f)
        assertEquals(3, boxes.size)
        boxes.forEach { assertEquals(160f, it.top, .01f) }
        assertTrue(boxes[0].right < boxes[1].left)
        assertTrue(boxes[1].right < boxes[2].left)
        assertTrue(boxes[2].right < 360f)
    }

    @Test fun midnightPointBelongsOnlyToItsOwnDay() {
        val point = mark("midnight", 1440, null)
        assertTrue(calendarDaySlots(listOf(point), day).isEmpty())
        assertEquals(1, calendarDaySlots(listOf(point), day + 86400000).size)
    }
}
