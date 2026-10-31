package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.NativeFeedbackSink
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/** Exercises real pointer delivery and the production settings adapter without Compose test APIs. */
@RunWith(AndroidJUnit4::class)
class NativeFeedbackSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: IsolatedSettingsContext
    private lateinit var store: NativeStore
    private lateinit var scenario: ActivityScenario<NativeActivity>
    private val hapticEvents = CopyOnWriteArrayList<Pair<String, Int>>()
    private val cancellations = AtomicInteger()
    private val feedback = object : NativeFeedbackSink {
        override fun play(kind: String, strength: Int) { hapticEvents.add(kind to strength) }
        override fun cancel() { cancellations.incrementAndGet() }
    }

    @Before fun setUp() {
        context = IsolatedSettingsContext(instrumentation.targetContext)
        store = NativeStore(context)
        store.setAppearance(strength = 50)
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeActivity::class.java)
            .putExtra("preview", true))
        scenario.onActivity { activity ->
            activity.setContent {
                val s by store.state.collectAsState()
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop, LocalNativeFeedback provides feedback) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 80.dp)) {
                            NativeFeedbackSettings(store, s)
                        }
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
        awaitSliderValue(50f)
    }

    @After fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        if (::store.isInitialized) store.close()
        if (::context.isInitialized) context.cleanup()
    }

    @Test fun onePixelMovesAccumulateButOnlyReleaseCommitsTheFinalStrength() {
        val bounds = sliderBounds()
        assertTrue("Fixture needs sub-integer input deltas", bounds.width() > 200)
        val pixels = minOf(64, bounds.width() / 10)
        val gesture = PointerGesture(bounds.exactCenterX(), bounds.exactCenterY())
        try {
            gesture.send(MotionEvent.ACTION_DOWN)
            instrumentation.waitForIdleSync()
            repeat(pixels) { gesture.moveByOnePixel() }
            instrumentation.waitForIdleSync()

            val expected = 50f + pixels * 100f / bounds.width()
            // Accessibility screen bounds and input dispatch can differ by up to
            // two physical pixels. Keep this tolerance tied to device geometry,
            // while the pure input-accumulator tests verify exact micro-deltas.
            val draft = awaitSliderValue(expected, 2.1f * 100f / bounds.width())
            assertEquals("Dragging must not publish global settings", 50, savedStrength())
            assertEquals(50, persistedStrength())
            assertTrue("Dragging must not play repeated previews", hapticEvents.isEmpty())

            gesture.send(MotionEvent.ACTION_UP)
            awaitSavedStrength(draft.roundToInt())
            awaitSliderValue(draft.roundToInt().toFloat())
            assertEquals(draft.roundToInt(), persistedStrength())
            instrumentation.waitForIdleSync()
            assertEquals(listOf("preview" to draft.roundToInt()), hapticEvents.toList())
        } finally { gesture.cancelIfActive() }
    }

    @Test fun cancelledDragRestoresTheSavedValueWithoutCommittingItsDraft() {
        val bounds = sliderBounds()
        val pixels = minOf(64, bounds.width() / 10)
        val gesture = PointerGesture(bounds.exactCenterX(), bounds.exactCenterY())
        try {
            gesture.send(MotionEvent.ACTION_DOWN)
            instrumentation.waitForIdleSync()
            repeat(pixels) { gesture.moveByOnePixel() }
            // Roll back an actual unsaved draft; exact displacement is checked
            // independently by the release test and pure accumulator tests.
            awaitUnsavedDraft()
            assertEquals(50, savedStrength())

            gesture.send(MotionEvent.ACTION_CANCEL)
            awaitSliderValue(50f)
            assertEquals("Cancellation must leave global settings unchanged", 50, savedStrength())
            assertEquals(50, persistedStrength())
            assertTrue("Cancellation must not play a preview", hapticEvents.isEmpty())
        } finally { gesture.cancelIfActive() }
    }

    @Test fun draggingToZeroStopsFeedbackImmediatelyAndReleaseDoesNotPreview() {
        val bounds = sliderBounds()
        val gesture = PointerGesture(bounds.exactCenterX(), bounds.exactCenterY())
        try {
            gesture.send(MotionEvent.ACTION_DOWN)
            instrumentation.waitForIdleSync()
            gesture.moveBy(-bounds.width() / 2f)
            awaitSliderValue(0f)
            assertEquals(50, savedStrength())
            assertTrue("Zero must immediately stop playback", cancellations.get() > 0)
            assertTrue(hapticEvents.isEmpty())
            gesture.send(MotionEvent.ACTION_UP)
            awaitSavedStrength(0)
            instrumentation.waitForIdleSync()
            assertTrue("Zero must not play a release preview", hapticEvents.isEmpty())
            assertEquals(0, persistedStrength())
        } finally { gesture.cancelIfActive() }
    }

    private fun savedStrength(): Int {
        var strength = -1
        instrumentation.runOnMainSync { strength = store.state.value.hapticStrength }
        return strength
    }

    private fun persistedStrength() = context.getSharedPreferences("stu.native.settings.v1", Context.MODE_PRIVATE)
        .getInt("strength", -1)

    private fun awaitSavedStrength(expected: Int) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (savedStrength() == expected) return
            SystemClock.sleep(20)
        }
        assertEquals(expected, savedStrength())
    }

    private fun awaitSliderValue(expected: Float, tolerance: Float = .01f): Float {
        val deadline = SystemClock.uptimeMillis() + 5000
        var actual: Float? = null
        while (SystemClock.uptimeMillis() < deadline) {
            actual = sliderNode()?.rangeInfo?.current
            if (actual != null && kotlin.math.abs(actual - expected) <= tolerance) return actual
            SystemClock.sleep(30)
        }
        assertEquals("Accessible slider value did not follow pointer input", expected, actual ?: Float.NaN, tolerance)
        return actual ?: Float.NaN
    }

    private fun awaitUnsavedDraft() {
        val saved = savedStrength().toFloat()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            val draft = sliderNode()?.rangeInfo?.current
            if (draft != null && kotlin.math.abs(draft - saved) > .01f) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Drag never produced an unsaved draft to cancel")
    }

    private fun sliderBounds(): Rect {
        val node = requireNotNull(sliderNode()) { "Strength slider missing" }
        return Rect().also { node.getBoundsInScreen(it) }
    }

    private fun sliderNode(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.contentDescription?.toString() == "触感强度" && node.rangeInfo != null) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::addLast) }
        }
        return null
    }

    private inner class PointerGesture(private var x: Float, private val y: Float) {
        private val downTime = SystemClock.uptimeMillis()
        private var active = false
        fun moveByOnePixel() {
            SystemClock.sleep(16)
            x += 1f
            send(MotionEvent.ACTION_MOVE)
        }
        fun moveBy(deltaPixels: Float) {
            SystemClock.sleep(16)
            x += deltaPixels
            send(MotionEvent.ACTION_MOVE)
        }
        fun cancelIfActive() {
            if (active) runCatching { send(MotionEvent.ACTION_CANCEL) }
        }
        fun send(action: Int) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                val accepted = instrumentation.uiAutomation.injectInputEvent(event, true)
                if (accepted && action == MotionEvent.ACTION_DOWN) active = true
                if (accepted && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) active = false
                assertTrue("Pointer event rejected", accepted)
            } finally { event.recycle() }
        }
    }

    private class IsolatedSettingsContext(base: Context) : ContextWrapper(base) {
        private val prefix = "native-haptics-test-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun cleanup() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }
}
