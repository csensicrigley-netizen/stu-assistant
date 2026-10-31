package com.stuassistant.app.glass

/** Input position is accumulated synchronously, independently of animated or rounded values. */
internal class SliderDragValue(private val range: ClosedFloatingPointRange<Float>) {
    var value: Float = range.start
        private set

    fun start(value: Float) {
        this.value = value.coerceIn(range)
    }

    fun dragBy(deltaPixels: Float, trackWidth: Int, isLtr: Boolean): Float {
        if (trackWidth <= 0 || !deltaPixels.isFinite()) return value
        val delta = (range.endInclusive - range.start) * deltaPixels / trackWidth
        value = (value + if (isLtr) delta else -delta).coerceIn(range)
        return value
    }
}
