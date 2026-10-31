package com.stuassistant.app.glass

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class SliderDragValueTest {
    @Test fun slowSubIntegerMovesAccumulateEvenWhenDisplayedValuesAreRounded() {
        val input = SliderDragValue(0f..100f)
        input.start(40f)
        repeat(100) {
            // Each event is 0.1 of the value range: none should be lost merely
            // because a label, persistence layer or host still shows an integer.
            input.dragBy(1f, 1000, true).roundToInt()
        }
        assertEquals(50f, input.value, 0.001f)
    }

    @Test fun reversalsAndRtlUseTheSameContinuousInputPosition() {
        val ltr = SliderDragValue(0f..100f)
        val rtl = SliderDragValue(0f..100f)
        ltr.start(50f)
        rtl.start(50f)
        repeat(70) { ltr.dragBy(1f, 1000, true); rtl.dragBy(1f, 1000, false) }
        assertEquals(57f, ltr.value, 0.001f)
        assertEquals(43f, rtl.value, 0.001f)
        repeat(70) { ltr.dragBy(-1f, 1000, true); rtl.dragBy(-1f, 1000, false) }
        assertEquals(50f, ltr.value, 0.001f)
        assertEquals(50f, rtl.value, 0.001f)
    }

    @Test fun clampDoesNotAccumulateHiddenOverscrollAndNextGestureResetsPosition() {
        val input = SliderDragValue(10f..20f)
        input.start(19f)
        input.dragBy(1000f, 1000, true)
        assertEquals(20f, input.value, 0f)
        assertEquals(19.9f, input.dragBy(-10f, 1000, true), 0.001f)
        input.start(12f)
        assertEquals(12.1f, input.dragBy(10f, 1000, true), 0.001f)
        assertEquals(12.1f, input.dragBy(10f, 0, true), 0.001f)
    }
}
