package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInputModeManager
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
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real home cards and modal host with pointer input and a silent feedback sink. */
@RunWith(AndroidJUnit4::class)
class NativeCardSheetMotionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val feedback = RecordingFeedback()
    private lateinit var scenario: ActivityScenario<NativeActivity>
    private lateinit var context: IsolatedContext
    private lateinit var store: NativeStore

    @Before fun setUp() {
        context = IsolatedContext(instrumentation.targetContext)
        store = NativeStore(context)
        scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, NativeActivity::class.java)
            .putExtra("preview", true).putExtra("now", "2026-10-30T08:00:00+08:00"))
    }

    @After fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        if (::store.isInitialized) store.close()
        if (::context.isInitialized) context.cleanup()
        AcademicClock.setPreviewTime(null)
    }

    @Test fun actualHomeStaticCardIsSilentAndConnectCardUsesOneNavigationPair() {
        val connects = AtomicInteger()
        installHome(NativeState(sample = true, preview = true), connects)
        tap(awaitBounds("接下来两周没有排课"))
        instrumentation.waitForIdleSync()
        assertEquals("A static home card must not imply an action", emptyList<FeedbackEvent>(), feedback.events.toList())
        assertEquals(0, connects.get())

        tap(awaitBounds("这是示例课表"))
        awaitCondition("Connect card did not invoke its action") { connects.get() == 1 }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(FeedbackEvent("navigate_press", 72), FeedbackEvent("navigate_release", 72)), feedback.events.toList())
    }

    @Test fun actualHomeCourseCardOpensItsSheetWithExactlyOneNavigationPair() {
        val sheets = NativeSheetController()
        val timetable = JSONObject("""{
            "semester":"2026-2027-1","status":"ok",
            "courses":[{"id":"motion-course","title":"Motion fixture course","hue":250,
              "meetings":[{"weekday":5,"weeks":[8],"periods":[1],"location":"Fixture room"}]}]
        }""")
        installHome(NativeState(sample = true, preview = true, timetable = timetable), AtomicInteger(), sheets)
        tap(awaitBounds("Motion fixture course"))
        awaitCondition("Home course card did not register its sheet") { entryCount(sheets) == 1 }
        awaitCondition("Course sheet did not become accessible") { nodeWithText("关闭") != null }
        instrumentation.waitForIdleSync()
        assertEquals(listOf(FeedbackEvent("navigate_press", 72), FeedbackEvent("navigate_release", 72)), feedback.events.toList())
    }

    @Test fun exitingSheetRetainsItsEntryAndBlocksBackgroundUntilRemoval() {
        val fixture = installModal(probeExitPointers = true)
        val background = awaitBounds(BACKGROUND)
        enterKeyboardMode(fixture)
        instrumentation.runOnMainSync { fixture.backgroundFocus.requestFocus() }
        awaitCondition("Background must be focusable before the modal guard applies") { fixture.backgroundFocused.get() }
        openFirst(fixture)
        update(fixture) { fixture.first.value = false }
        assertEquals("NSheet disposal must retain its visual entry for exit", 1, fixture.countAtFirstDisposal.get())
        awaitCondition("Exit pointer probe did not run") { fixture.exitProbeRan.get() }
        assertTrue("Pointer probe must observe an actual retained exit", fixture.exitProbeWasGuarded.get())
        assertTrue("Compose view rejected the exit pointer probe", fixture.exitProbeAccepted.get())
        assertTrue("Background focus must remain blocked at disposal", fixture.backgroundBlockedAtDisposal.get())
        assertEquals("Exit scrim must consume background pointer input", 0, fixture.backgroundClicks.get())
        awaitCondition("Exiting sheet entry was never removed") { entryCount(fixture.sheets) == 0 }
        awaitCondition("Background semantics did not return") { nodeWithText(BACKGROUND) != null }
        tap(background)
        awaitCondition("Background remained blocked after removal") { fixture.backgroundClicks.get() == 1 }
    }

    @Test fun reducedMotionSheetRemainsAccessibleAndLeavesNoEntryOrBackgroundGuard() {
        val fixture = installModal(reducedMotion = true)
        val background = awaitBounds(BACKGROUND)
        openFirst(fixture)
        assertTrue("Reduced-motion content must remain accessible", nodeWithText(FIRST_TARGET) != null)
        assertEquals(1, entryCount(fixture.sheets))

        update(fixture) { fixture.first.value = false }
        awaitCondition("Reduced-motion close retained an orphaned entry") { entryCount(fixture.sheets) == 0 }
        awaitCondition("Reduced-motion close left background semantics hidden") { nodeWithText(BACKGROUND) != null }
        tap(background)
        awaitCondition("Reduced-motion close left the background blocked") { fixture.backgroundClicks.get() == 1 }
    }

    @Test fun scrimAndBackEachDismissOnePresentedSheet() {
        val fixture = installModal()
        val scrimPoint = awaitBounds(BACKGROUND)
        openFirst(fixture)
        tap(scrimPoint)
        awaitCondition("Scrim did not dismiss its sheet") { fixture.firstDismissals.get() == 1 }
        awaitCondition("Scrim-dismissed entry was never removed") { entryCount(fixture.sheets) == 0 }
        assertEquals(0, fixture.backgroundClicks.get())

        openFirst(fixture)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitCondition("Back did not dismiss the top sheet") { fixture.firstDismissals.get() == 2 }
        awaitCondition("Back-dismissed entry was never removed") { entryCount(fixture.sheets) == 0 }
        assertEquals(2, fixture.firstDismissals.get())
        assertEquals(0, fixture.backgroundClicks.get())
    }

    @Test fun cancelledHandleDragSettlesWithoutDismissingTheSheet() {
        val fixture = installModal()
        openFirst(fixture)
        val title = settledBounds(FIRST_TITLE)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val screen = screenBounds()
        val startX = screen.exactCenterX()
        val startY = title.top - 12f * density
        val downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        try {
            inject(MotionEvent.ACTION_DOWN, downTime, startX, startY)
            pointerDown = true
            repeat(3) { index ->
                SystemClock.sleep(24)
                inject(MotionEvent.ACTION_MOVE, downTime, startX, startY + (index + 1) * 22f * density)
            }
            inject(MotionEvent.ACTION_CANCEL, downTime, startX, startY + 66f * density)
            pointerDown = false
            awaitCondition("Cancelled drag did not settle back") {
                val restored = boundsOf(FIRST_TITLE)
                restored != null && kotlin.math.abs(restored.top - title.top) <= 2f * density
            }
            assertEquals(1, entryCount(fixture.sheets))
            assertEquals(0, fixture.firstDismissals.get())
            assertTrue(fixture.first.value)
        } finally {
            if (pointerDown) runCatching { inject(MotionEvent.ACTION_CANCEL, downTime, startX, startY) }
        }
    }

    @Test fun stackedSheetExitRestoresLowerFocusBeforeRestoringTheBackground() {
        val fixture = installModal()
        enterKeyboardMode(fixture)
        instrumentation.runOnMainSync { fixture.backgroundFocus.requestFocus() }
        awaitCondition("Background fixture did not take focus") { fixture.backgroundFocused.get() }
        openFirst(fixture)
        instrumentation.runOnMainSync { fixture.firstFocus.requestFocus() }
        awaitCondition("First sheet target did not take focus") { fixture.firstFocused.get() }
        update(fixture) { fixture.second.value = true }
        awaitCondition("Nested sheet was not registered") { entryCount(fixture.sheets) == 2 }
        awaitCondition("Nested sheet did not become accessible") { nodeWithText(SECOND_TARGET) != null }
        instrumentation.runOnMainSync { fixture.secondFocus.requestFocus() }
        awaitCondition("Nested sheet target did not take focus") { fixture.secondFocused.get() }
        assertFalse(fixture.backgroundFocused.get())
        assertTrue("Underlying sheet must be hidden from accessibility", nodeWithText(FIRST_TARGET) == null)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitCondition("Only the nested sheet should leave") { entryCount(fixture.sheets) == 1 }
        awaitCondition("Lower sheet did not restore its saved child focus") { fixture.firstFocused.get() }
        assertEquals(0, fixture.firstDismissals.get())
        assertEquals(1, fixture.secondDismissals.get())
        assertFalse(fixture.backgroundFocused.get())

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        awaitCondition("Last sheet entry did not finish leaving") { entryCount(fixture.sheets) == 0 }
        awaitCondition("Background did not regain its saved child focus") { fixture.backgroundFocused.get() }
        assertEquals(1, fixture.firstDismissals.get())
    }

    @Test fun removingAParentAlsoRetiresItsNestedVisualEntry() {
        val fixture = installModal()
        openFirst(fixture)
        update(fixture) { fixture.second.value = true }
        awaitCondition("Nested sheet was not registered") { entryCount(fixture.sheets) == 2 }
        settledBounds(SECOND_TITLE)

        // This is an external state change, rather than a dismiss request to the top sheet.
        update(fixture) { fixture.first.value = false }
        assertEquals(2, fixture.countAtFirstDisposal.get())
        awaitCondition("Parent removal left an orphaned nested sheet") { entryCount(fixture.sheets) == 0 }
        awaitCondition("Parent removal did not unlock the background") { nodeWithText(BACKGROUND) != null }
        assertEquals(0, fixture.firstDismissals.get())
        assertEquals(0, fixture.secondDismissals.get())
    }

    private fun installHome(state: NativeState, connects: AtomicInteger, sheets: NativeSheetController = NativeSheetController()) {
        setFixture(sheets) { NativeTodayScreen(store, state, onNavigate = {}, onConnect = { connects.incrementAndGet() }) }
    }

    private class ModalFixture(val probeExitPointers: Boolean) {
        val sheets = NativeSheetController()
        val first = mutableStateOf(false)
        val second = mutableStateOf(false)
        val applied = AtomicInteger()
        val countAtFirstDisposal = AtomicInteger(-1)
        val firstWasApplied = AtomicBoolean()
        val backgroundClicks = AtomicInteger()
        val firstDismissals = AtomicInteger()
        val secondDismissals = AtomicInteger()
        val backgroundFocus = FocusRequester()
        val firstFocus = FocusRequester()
        val secondFocus = FocusRequester()
        val backgroundFocused = AtomicBoolean()
        val firstFocused = AtomicBoolean()
        val secondFocused = AtomicBoolean()
        val backgroundBlockedAtDisposal = AtomicBoolean()
        val exitProbeRan = AtomicBoolean()
        val exitProbeWasGuarded = AtomicBoolean()
        val exitProbeAccepted = AtomicBoolean()
        var backgroundProbeX = 0f
        var backgroundProbeY = 0f
        lateinit var inputModeManager: InputModeManager
    }

    private fun installModal(reducedMotion: Boolean = false, probeExitPointers: Boolean = false): ModalFixture {
        val fixture = ModalFixture(probeExitPointers)
        setFixture(fixture.sheets, reducedMotion) {
            val view = LocalView.current
            val inputModeManager = LocalInputModeManager.current
            val first = fixture.first.value
            val second = fixture.second.value
            Column(Modifier.fillMaxSize().nativeSheetBackground(fixture.sheets).padding(24.dp, 64.dp)) {
                Box(Modifier.size(180.dp, 56.dp).semantics { contentDescription = BACKGROUND }
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInRoot()
                        fixture.backgroundProbeX = bounds.center.x
                        fixture.backgroundProbeY = bounds.center.y
                    }
                    .focusRequester(fixture.backgroundFocus)
                    .onFocusChanged { fixture.backgroundFocused.set(it.isFocused) }
                    .clickable { fixture.backgroundClicks.incrementAndGet() }) { BasicText(BACKGROUND) }
            }
            if (first) NSheet(FIRST_TITLE, {
                fixture.firstDismissals.incrementAndGet()
                fixture.first.value = false
            }) {
                NButton(FIRST_TARGET, {}, modifier = Modifier.semantics { contentDescription = FIRST_TARGET }
                    .focusRequester(fixture.firstFocus).onFocusChanged { fixture.firstFocused.set(it.isFocused) })
                if (second) NSheet(SECOND_TITLE, {
                    fixture.secondDismissals.incrementAndGet()
                    fixture.second.value = false
                }) {
                    NButton(SECOND_TARGET, {}, modifier = Modifier.semantics { contentDescription = SECOND_TARGET }
                        .focusRequester(fixture.secondFocus).onFocusChanged { fixture.secondFocused.set(it.isFocused) })
                }
            }
            SideEffect {
                fixture.inputModeManager = inputModeManager
                if (first) fixture.firstWasApplied.set(true)
                else if (fixture.firstWasApplied.getAndSet(false)) {
                    fixture.countAtFirstDisposal.set(fixture.sheets.entries.size)
                    if (fixture.probeExitPointers) {
                        fixture.backgroundBlockedAtDisposal.set(!fixture.backgroundFocus.requestFocus(FocusDirection.Enter))
                        // Run after apply/launch effects, in the same UI queue as the exit.
                        // Dispatching to the real Compose view prevents instrumentation-thread
                        // scheduling from moving this probe past the animation's final frame.
                        view.post {
                            val guarded = fixture.sheets.entries.isNotEmpty()
                            fixture.exitProbeWasGuarded.set(guarded)
                            if (guarded) fixture.exitProbeAccepted.set(dispatchProbe(view, fixture.backgroundProbeX, fixture.backgroundProbeY))
                            fixture.exitProbeRan.set(true)
                        }
                    }
                }
                fixture.applied.incrementAndGet()
            }
        }
        awaitCondition("Modal fixture did not compose") { fixture.applied.get() > 0 }
        return fixture
    }

    private fun setFixture(sheets: NativeSheetController, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
        scenario.onActivity { activity ->
            activity.setContent {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(
                        LocalNativeBackdrop provides backdrop,
                        LocalNativeFeedback provides feedback,
                        LocalNativeStrength provides 72,
                        LocalNativeSheets provides sheets,
                        LocalLiquidGlassReducedMotion provides reducedMotion,
                    ) {
                        content()
                        NativeSheetHost(sheets, backdrop)
                    }
                }
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun openFirst(fixture: ModalFixture) {
        update(fixture) { fixture.first.value = true }
        awaitCondition("First sheet was not registered") { entryCount(fixture.sheets) == 1 }
        settledBounds(FIRST_TITLE)
    }

    private fun enterKeyboardMode(fixture: ModalFixture) {
        instrumentation.runOnMainSync {
            assertTrue("Real Compose input-mode manager rejected keyboard mode",
                fixture.inputModeManager.requestInputMode(InputMode.Keyboard))
        }
        awaitCondition("Compose did not enter keyboard mode") {
            var keyboard = false
            instrumentation.runOnMainSync { keyboard = fixture.inputModeManager.inputMode == InputMode.Keyboard }
            keyboard
        }
        instrumentation.waitForIdleSync()
    }

    private fun update(fixture: ModalFixture, change: () -> Unit) {
        val previous = fixture.applied.get()
        instrumentation.runOnMainSync(change)
        awaitCondition("Modal fixture did not recompose") { fixture.applied.get() > previous }
        instrumentation.waitForIdleSync()
    }

    private fun entryCount(sheets: NativeSheetController): Int {
        var count = -1
        instrumentation.runOnMainSync { count = sheets.entries.size }
        return count
    }

    private fun awaitBounds(label: String): Rect {
        var found: Rect? = null
        awaitCondition("Missing accessible fixture: $label") {
            val current = boundsOf(label)
            if (current != null) found = current
            current != null
        }
        return requireNotNull(found)
    }

    private fun settledBounds(label: String): Rect {
        var previous = awaitBounds(label)
        var steady = 0
        awaitCondition("Fixture did not finish entering: $label") {
            val current = boundsOf(label)
            if (current != null && current == previous) steady++ else steady = 0
            if (current != null) previous = current
            steady >= 5
        }
        return previous
    }

    private fun boundsOf(label: String): Rect? = nodeWithText(label)?.let { node ->
        Rect().also { node.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
    }

    private fun nodeWithText(label: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.isVisibleToUser && (node.contentDescription?.toString() == label || node.text?.toString()?.contains(label) == true)) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(nodes::addLast) }
        }
        return null
    }

    private fun tap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, down, bounds.exactCenterX(), bounds.exactCenterY())
        try { inject(MotionEvent.ACTION_UP, down, bounds.exactCenterX(), bounds.exactCenterY()) }
        catch (error: Throwable) {
            runCatching { inject(MotionEvent.ACTION_CANCEL, down, bounds.exactCenterX(), bounds.exactCenterY()) }
            throw error
        }
    }

    private fun inject(action: Int, downTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Pointer event rejected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun dispatchProbe(view: View, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 1, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        return try {
            val downAccepted = view.dispatchTouchEvent(down)
            val upAccepted = view.dispatchTouchEvent(up)
            downAccepted && upAccepted
        } finally { down.recycle(); up.recycle() }
    }

    private fun screenBounds(): Rect {
        var bounds = Rect()
        scenario.onActivity { activity ->
            val location = IntArray(2)
            activity.window.decorView.getLocationOnScreen(location)
            bounds = Rect(location[0], location[1], location[0] + activity.window.decorView.width, location[1] + activity.window.decorView.height)
        }
        return bounds
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(16)
        }
        assertTrue(message, condition())
    }

    private data class FeedbackEvent(val kind: String, val strength: Int)

    private class RecordingFeedback : NativeFeedbackSink {
        val events = CopyOnWriteArrayList<FeedbackEvent>()
        override fun play(kind: String, strength: Int) { events.add(FeedbackEvent(kind, strength)) }
        override fun cancel() = Unit
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "native-card-sheet-test-${UUID.randomUUID()}-"
        private val names = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = prefix + name
            names.add(isolated)
            return baseContext.getSharedPreferences(isolated, mode)
        }
        fun cleanup() { names.forEach { baseContext.deleteSharedPreferences(it) } }
    }

    private companion object {
        const val BACKGROUND = "motion-fixture-background"
        const val FIRST_TITLE = "First motion sheet"
        const val SECOND_TITLE = "Second motion sheet"
        const val FIRST_TARGET = "motion-fixture-first-focus"
        const val SECOND_TARGET = "motion-fixture-second-focus"
    }
}
