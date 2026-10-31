package com.stuassistant.app.nativeapp

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.NativeFeedbackSink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Counts production gesture-adapter requests; the fixture never sends them to a motor. */
@RunWith(AndroidJUnit4::class)
class NativeButtonFeedbackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val feedback = RecordingFeedback()
    private lateinit var scenario: ActivityScenario<NativeActivity>

    @Before fun setUp() {
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeActivity::class.java)
            .putExtra("preview", true))
    }

    @After fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun everyCategoryHasExactlyTwoPhasesWithEitherReleaseOrdering() {
        val fixture = installHelper()
        val expected = mutableListOf<FeedbackEvent>()
        val profiles = listOf(
            NativeButtonHaptic.Navigation to ("navigate_press" to "navigate_release"),
            NativeButtonHaptic.Submit to ("press" to "release"),
            NativeButtonHaptic.Utility to ("utility_press" to "utility_release"),
            NativeButtonHaptic.Destructive to ("destructive_press" to "destructive_release"),
        )
        for ((profile, phases) in profiles) {
            update(fixture) { fixture.category.value = profile }
            // One collector release followed by onClick's commit must remain one pair.
            val first = press()
            emit(fixture, first)
            emit(fixture, PressInteraction.Release(first))
            commit(fixture)
            commit(fixture)
            emit(fixture, PressInteraction.Release(first))
            expected += FeedbackEvent(phases.first, 72)
            expected += FeedbackEvent(phases.second, 72)
            assertEvents(expected)

            // A click may commit before the queued interaction release is collected.
            val second = press()
            emit(fixture, second)
            commit(fixture)
            emit(fixture, PressInteraction.Release(second))
            expected += FeedbackEvent(phases.first, 72)
            expected += FeedbackEvent(phases.second, 72)
            assertEvents(expected)
        }
        assertEquals("Pair cleanup must not cancel other feedback", 0, feedback.cancellations.get())
    }

    @Test fun unrelatedAndRepeatedInteractionsCannotStealAnAcceptedPair() {
        val fixture = installHelper()
        val accepted = press()
        val unrelated = press()
        emit(fixture, accepted)
        emit(fixture, accepted)
        emit(fixture, unrelated)
        emit(fixture, PressInteraction.Release(unrelated))
        emit(fixture, PressInteraction.Cancel(unrelated))
        assertEvents(listOf(FeedbackEvent("press", 72)))

        emit(fixture, PressInteraction.Release(accepted))
        commit(fixture)
        assertEvents(listOf(FeedbackEvent("press", 72), FeedbackEvent("release", 72)))
    }

    @Test fun cancelledGestureHasNoReleaseAndDoesNotCancelTheEngine() {
        val fixture = installHelper()
        val cancelled = press()
        emit(fixture, cancelled)
        emit(fixture, PressInteraction.Cancel(cancelled))
        commit(fixture)
        emit(fixture, PressInteraction.Release(cancelled))
        assertEvents(listOf(FeedbackEvent("press", 72)))
        assertEquals(0, feedback.cancellations.get())

        val next = press()
        emit(fixture, next)
        emit(fixture, PressInteraction.Release(next))
        assertEvents(listOf(FeedbackEvent("press", 72), FeedbackEvent("press", 72), FeedbackEvent("release", 72)))
    }

    @Test fun disabledAndZeroStrengthGesturesStaySilentAndZeroMutesPendingRelease() {
        val fixture = installHelper()
        update(fixture) { fixture.enabled.value = false }
        val disabled = press()
        emit(fixture, disabled)
        emit(fixture, PressInteraction.Release(disabled))
        commit(fixture)
        assertEvents(emptyList())

        update(fixture) { fixture.enabled.value = true; fixture.strength.intValue = 0 }
        val zero = press()
        emit(fixture, zero)
        emit(fixture, PressInteraction.Release(zero))
        commit(fixture)
        assertEvents(emptyList())

        update(fixture) { fixture.strength.intValue = 80 }
        val muted = press()
        emit(fixture, muted)
        update(fixture) { fixture.strength.intValue = 0 }
        emit(fixture, PressInteraction.Release(muted))
        commit(fixture)
        update(fixture) { fixture.strength.intValue = 60 }
        commit(fixture)
        assertEvents(listOf(FeedbackEvent("press", 80)))
        assertEquals("The settings owner, not an individual gesture, owns engine cancellation", 0, feedback.cancellations.get())
    }

    @Test fun acceptedGestureLocksItsProfileAndStrengthAcrossBusyRecomposition() {
        val fixture = installHelper()
        val accepted = press()
        emit(fixture, accepted)
        update(fixture) {
            fixture.category.value = NativeButtonHaptic.Destructive
            fixture.strength.intValue = 24
            fixture.enabled.value = false
        }
        emit(fixture, PressInteraction.Release(accepted))
        commit(fixture)
        assertEvents(listOf(FeedbackEvent("press", 72), FeedbackEvent("release", 72)))

        update(fixture) { fixture.enabled.value = true }
        val next = press()
        emit(fixture, next)
        emit(fixture, PressInteraction.Release(next))
        assertEvents(listOf(
            FeedbackEvent("press", 72), FeedbackEvent("release", 72),
            FeedbackEvent("destructive_press", 24), FeedbackEvent("destructive_release", 24),
        ))
    }

    @Test fun disposalClearsThePendingPairEvenWhenAnOldCommitCallbackSurvives() {
        val fixture = installHelper()
        val abandoned = press()
        emit(fixture, abandoned)
        update(fixture) { fixture.shown.value = false }
        commit(fixture)
        emit(fixture, PressInteraction.Release(abandoned))
        assertEvents(listOf(FeedbackEvent("press", 72)))

        update(fixture) { fixture.shown.value = true }
        val next = press()
        emit(fixture, next)
        commit(fixture)
        emit(fixture, PressInteraction.Release(next))
        assertEvents(listOf(FeedbackEvent("press", 72), FeedbackEvent("press", 72), FeedbackEvent("release", 72)))
    }

    @Test fun realGlassButtonFastTapInsideScrollablePageCommitsOnePairBeforeDisposal() {
        val shown = mutableStateOf(true)
        val clicks = AtomicInteger()
        scenario.onActivity { activity ->
            activity.setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(
                        LocalNativeBackdrop provides backdrop,
                        LocalNativeFeedback provides feedback,
                        LocalNativeStrength provides 83,
                    ) {
                        NPage(title = "") {
                            if (shown.value) NativeGlassButton(
                                onClick = { clicks.incrementAndGet(); shown.value = false },
                                modifier = Modifier.semantics { contentDescription = BUTTON_DESCRIPTION },
                                haptic = NativeButtonHaptic.Utility,
                                surfaceColor = Color.LightGray,
                            ) { BasicText("Close fixture") }
                            // Keep the real scrollable ancestor that delays clickable's press.
                            Box(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        awaitCondition("Glass button never appeared") { buttonNode() != null }
        instrumentation.waitForIdleSync()
        val bounds = Rect().also { requireNotNull(buttonNode()).getBoundsInScreen(it) }
        val downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        try {
            inject(MotionEvent.ACTION_DOWN, downTime, bounds)
            pointerDown = true
            // No idle wait between down/up: exercise the fast click and disposal path.
            inject(MotionEvent.ACTION_UP, downTime, bounds)
            pointerDown = false
            awaitCondition("Glass button click did not run") { clicks.get() == 1 }
            awaitCondition("Click did not dispose its button") { buttonNode() == null }
            assertEvents(listOf(FeedbackEvent("utility_press", 83), FeedbackEvent("utility_release", 83)))
            assertEquals(1, clicks.get())
            assertEquals(0, feedback.cancellations.get())
        } finally {
            if (pointerDown) runCatching { inject(MotionEvent.ACTION_CANCEL, downTime, bounds) }
        }
    }

    private class HelperFixture {
        val source = MutableInteractionSource()
        val category = mutableStateOf(NativeButtonHaptic.Submit)
        val strength = mutableIntStateOf(72)
        val enabled = mutableStateOf(true)
        val shown = mutableStateOf(true)
        val applied = AtomicInteger()
        val commit = AtomicReference<() -> Unit>({})
    }

    private fun installHelper(): HelperFixture {
        val fixture = HelperFixture()
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalNativeFeedback provides feedback, LocalNativeStrength provides fixture.strength.intValue) {
                    val release = if (fixture.shown.value) NativePressFeedback(
                        fixture.source, fixture.enabled.value, fixture.category.value,
                    ) else null
                    SideEffect {
                        if (release != null) fixture.commit.set(release)
                        fixture.applied.incrementAndGet()
                    }
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        awaitCondition("Gesture helper did not compose") { fixture.applied.get() > 0 }
        instrumentation.waitForIdleSync()
        return fixture
    }

    private fun update(fixture: HelperFixture, change: () -> Unit) {
        val previous = fixture.applied.get()
        instrumentation.runOnMainSync(change)
        awaitCondition("Gesture helper did not recompose") { fixture.applied.get() > previous }
        instrumentation.waitForIdleSync()
    }

    private fun press() = PressInteraction.Press(Offset.Zero)

    private fun emit(fixture: HelperFixture, interaction: Interaction) {
        instrumentation.runOnMainSync { assertTrue("Interaction buffer rejected event", fixture.source.tryEmit(interaction)) }
        instrumentation.waitForIdleSync()
    }

    private fun commit(fixture: HelperFixture) {
        instrumentation.runOnMainSync { fixture.commit.get().invoke() }
        instrumentation.waitForIdleSync()
    }

    private fun assertEvents(expected: List<FeedbackEvent>) {
        instrumentation.waitForIdleSync()
        assertEquals(expected, feedback.events.toList())
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(16)
        }
        assertTrue(message, condition())
    }

    private fun buttonNode(): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.contentDescription?.toString() == BUTTON_DESCRIPTION) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::addLast) }
        }
        return null
    }

    private fun inject(action: Int, downTime: Long, bounds: Rect) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Pointer event rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private data class FeedbackEvent(val kind: String, val strength: Int)

    private class RecordingFeedback : NativeFeedbackSink {
        val events = CopyOnWriteArrayList<FeedbackEvent>()
        val cancellations = AtomicInteger()
        override fun play(kind: String, strength: Int) { events.add(FeedbackEvent(kind, strength)) }
        override fun cancel() { cancellations.incrementAndGet() }
    }

    private companion object {
        const val BUTTON_DESCRIPTION = "button-feedback-disposal-fixture"
    }
}
