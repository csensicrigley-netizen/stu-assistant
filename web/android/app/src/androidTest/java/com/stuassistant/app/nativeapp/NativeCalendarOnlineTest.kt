package com.stuassistant.app.nativeapp

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.*
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.NativeFeedbackSink
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

// UiAutomation.takeScreenshot intermittently returns null with the emulator IME.
// Shell capture uses the same real display and includes the keyboard reliably.
internal fun calendarDeviceScreenshot(inst: android.app.Instrumentation): android.graphics.Bitmap =
    android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("screencap -p")).use {
        android.graphics.BitmapFactory.decodeStream(it) ?: error("Device screenshot could not be decoded")
    }

/** Opt-in real Internet/Jev test. Isolated app data and a disposable STU account. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeCalendarOnlineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    @Before fun cleanInterruptedTestAccounts() = runBlocking {
        // A process-level crash cannot execute finally. Recover only our isolated
        // test directories and synthetic accounts, never normal app preferences.
        val prefixPattern=Regex("native-calendar-test-[0-9a-f-]{36}-")
        inst.targetContext.noBackupFilesDir.listFiles().orEmpty()
            .filter { it.isDirectory && prefixPattern.matches(it.name) }.forEach { directory ->
                val isolated=IsolatedContext(inst.targetContext,directory.name)
                val old=NativeStore(isolated)
                try {
                    val state=old.state.value
                    val email=state.user?.optString("email").orEmpty()
                    if(state.token!=null && email.startsWith("android-calendar-") && email.endsWith("@example.invalid")) {
                        val api=NativeApi()
                        try { api.request(NativeSession(state.server,state.token,0),"/v1/account","DELETE") }
                        finally { api.close() }
                    }
                } finally { old.close();isolated.cleanup() }
            }
    }
    private fun find(text: String): AccessibilityNodeInfo? {
        // UIAutomation may retain the outgoing AnimatedContent tree after a day slide.
        // Read the current window, not stale virtual nodes from the preceding day/test.
        if (android.os.Build.VERSION.SDK_INT >= 33) inst.uiAutomation.clearCache()
        val root = inst.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && (node.contentDescription?.toString()?.contains(text) == true || node.text?.toString() == text)) return node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return null
    }
    private fun waitFor(text: String, timeout: Long = 15000): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < until) { find(text)?.let { return it }; Thread.sleep(100) }
        val root = inst.uiAutomation.rootInActiveWindow
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        if (root != null) nodes.add(root)
        val report = StringBuilder()
        while (nodes.isNotEmpty()) {
            val n = nodes.removeFirst(); val r = Rect(); n.getBoundsInScreen(r)
            report.appendLine("visible=${n.isVisibleToUser} $r text=${n.text} desc=${n.contentDescription}")
            repeat(n.childCount) { n.getChild(it)?.let(nodes::add) }
        }
        File(inst.targetContext.getExternalFilesDir(null), "calendar-missing-ui.txt").writeText(report.toString())
        error("UI missing: $text")
    }
    private fun focusedInput(): AccessibilityNodeInfo? {
        inst.uiAutomation.clearCache()
        val root = inst.uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isFocused) return node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return null
    }
    private fun bounds(text: String): Rect = Rect().also { waitFor(text).getBoundsInScreen(it) }
    private fun event(action: Int, time: Long, x: Float, y: Float) {
        val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(inst.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
    }
    private fun tap(text: String) {
        val r = bounds(text); val t = SystemClock.uptimeMillis()
        event(MotionEvent.ACTION_DOWN, t, r.exactCenterX(), r.exactCenterY())
        event(MotionEvent.ACTION_UP, t, r.exactCenterX(), r.exactCenterY())
    }
    private fun tapField(description: String) {
        inst.uiAutomation.clearCache()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(inst.uiAutomation.rootInActiveWindow)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // NField exposes the label on an inner semantics node; the editable
            // parent itself has no contentDescription in Compose's Android tree.
            if (node.contentDescription?.toString() == description) {
                val r = Rect(); node.getBoundsInScreen(r)
                val t = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, t, r.exactCenterX(), r.exactCenterY())
                event(MotionEvent.ACTION_UP, t, r.exactCenterX(), r.exactCenterY())
                return
            }
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        screenshot("calendar-field-target-failure.png")
        val dump = StringBuilder()
        queue.add(inst.uiAutomation.rootInActiveWindow)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst(); val r = Rect(); node.getBoundsInScreen(r)
            dump.appendLine("$r editable=${node.isEditable} text=${node.text} desc=${node.contentDescription}")
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        File(inst.targetContext.getExternalFilesDir(null), "calendar-field-tree.txt").writeText(dump.toString())
        error("Editable input missing: $description")
    }
    @Test fun compactTimeEditorKeepsAllFieldsUsable() {
        val a = AcademicClock.parseTime("2026-11-02T14:00:00")!!
        var saved = false
        ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val base = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(base).background(Color.White))
                    CompositionLocalProvider(LocalNativeBackdrop provides base) {
                        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                            Spacer(Modifier.height(96.dp))
                            CalendarPrecise(CalendarMark("qa", "英语课", a, a+3600000)) { _, _ -> saved = true }
                        }
                    }
                }
            } }
            waitFor("精确日期")
            tapField("开始"); Thread.sleep(700)
            screenshot("calendar-compact-time-fields.png")
            assertNotNull(focusedInput())
            waitFor("精确结束时间"); waitFor("精确日期")
            tap("完成")
            Thread.sleep(200)
            assertTrue(saved)
        }
    }
    private fun moveEvent(text: String) {
        val drag = bounds(text)
        val down = SystemClock.uptimeMillis()
        val y = drag.top + drag.height() * .45f
        event(MotionEvent.ACTION_DOWN, down, drag.exactCenterX(), y)
        repeat(12) { step ->
            event(MotionEvent.ACTION_MOVE, down, drag.exactCenterX(), y + (step+1)*5f)
            Thread.sleep(16)
        }
        event(MotionEvent.ACTION_UP, down, drag.exactCenterX(), y+60f)
        Thread.sleep(250)
    }
    private fun screenshot(name: String) {
        val file = File(inst.targetContext.getExternalFilesDir(null), name)
        val bitmap = calendarDeviceScreenshot(inst)
        try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }
    private fun panTimeline(direction: Int) {
        val r = bounds("日历时间线")
        val time = SystemClock.uptimeMillis()
        val x = r.left + r.width() * .06f
        val start = r.exactCenterY()
        val distance = r.height() * .18f * direction
        event(MotionEvent.ACTION_DOWN, time, x, start)
        repeat(12) { step ->
            event(MotionEvent.ACTION_MOVE, time, x, start + distance * (step+1)/12)
            Thread.sleep(16)
        }
        event(MotionEvent.ACTION_UP, time, x, start+distance)
        Thread.sleep(200)
    }
    private fun pinch(centerX: Float, y: Float, from: Float, to: Float) {
        val time = SystemClock.uptimeMillis()
        fun send(action: Int, count: Int, half: Float) {
            val props = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply { x = centerX; this.y = y + if (i == 0) -half else half; pressure = 1f; size = 1f } }
            val e = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, count, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(inst.uiAutomation.injectInputEvent(e, true)) } finally { e.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 1, from)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from)
        repeat(16) { i -> send(MotionEvent.ACTION_MOVE, 2, from + (to-from)*(i+1)/16); Thread.sleep(16) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to)
        send(MotionEvent.ACTION_UP, 1, to)
        Thread.sleep(250)
    }
    @Test fun threeDayDragAndBothResizeHandlesKeepTheirMeaning() {
        val start = AcademicClock.dayStart("2026-11-02") + 14*3600000L
        val initial = CalendarDrafts.edit(JSONObject().put("title","跨列测试"),"schedule",start,start+3600000L)
        val latest = java.util.concurrent.atomic.AtomicReference(initial)
        ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext,NativeActivity::class.java).putExtra("preview",true)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var draft by remember { mutableStateOf(initial) }
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize().background(Color.White).layerBackdrop(backdrop)) {
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            CalendarTimeline("2026-11-02",{},listOf(draft),0,emptyList(),null,
                                { _,field,a,b -> draft=CalendarDrafts.edit(draft,field,a,b);latest.set(draft) },{}, {})
                        }
                    }
                }
            } }
            waitFor("跨列测试")
            Thread.sleep(500)
            fun drag(x:Float,y:Float,dx:Float,dy:Float) {
                val time=SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN,time,x,y)
                repeat(16) { i -> event(MotionEvent.ACTION_MOVE,time,x+dx*(i+1)/16,y+dy*(i+1)/16);Thread.sleep(16) }
                event(MotionEvent.ACTION_UP,time,x+dx,y+dy)
                Thread.sleep(300)
            }
            var r=bounds("跨列测试")
            val chart=bounds("日历时间线")
            val scale=inst.targetContext.resources.displayMetrics.density
            val column=(chart.width()-64*scale)/3
            drag(r.exactCenterX(),r.exactCenterY(),column,0f)
            assertEquals("Moving between columns must change only date",start+86400000L,CalendarDrafts.start(latest.get()))
            assertEquals(3600000L,CalendarDrafts.end(latest.get())!!-CalendarDrafts.start(latest.get())!!)
            r=bounds("跨列测试")
            val oldEnd=CalendarDrafts.end(latest.get())!!
            drag(r.exactCenterX(),r.top+6*scale,0f,-chart.height()*.08f)
            assertTrue(CalendarDrafts.start(latest.get())!!<start+86400000L)
            assertEquals("Top grip must keep end fixed",oldEnd,CalendarDrafts.end(latest.get()))
            r=bounds("跨列测试")
            val oldStart=CalendarDrafts.start(latest.get())!!
            drag(r.exactCenterX(),r.bottom-6*scale,0f,chart.height()*.08f)
            assertEquals("Bottom grip must keep start fixed",oldStart,CalendarDrafts.start(latest.get()))
            assertTrue(CalendarDrafts.end(latest.get())!!>oldEnd)
            screenshot("calendar-three-day-resized.png")
        }
    }
    @Test fun realJevKeyboardDragAndEncryptedPersistence() = runBlocking {
        runFlow("明天下午两点到三点和导师开会，明天下午五点前交报告", false)
    }
    @Test fun realDeadlinePointDrag() = runBlocking {
        runFlow("明天下午五点前交报告", true)
    }
    private suspend fun runFlow(inputText: String, deadlineOnly: Boolean) {
        org.junit.Assume.assumeTrue("Enable explicitly; this test uses live Jev quota",
            InstrumentationRegistry.getArguments().getString("calendarOnline") == "true")
        val context = IsolatedContext(inst.targetContext)
        var store = NativeStore(context)
        val closed = AtomicBoolean(false)
        var scenario: ActivityScenario<NativeActivity>? = null
        var registered = false
        var cleanupSession: NativeSession? = null
        val feedbackEvents = CopyOnWriteArrayList<String>()
        val feedback = object : NativeFeedbackSink {
            override fun play(kind: String, strength: Int) { feedbackEvents.add(kind) }
            override fun cancel() = Unit
        }
        try {
            assertEquals("Packaged service must be the production STU origin", "https://your-domain.example.com", store.state.value.server)
            store.login("android-calendar-${UUID.randomUUID()}@example.invalid", UUID.randomUUID().toString(), true)
            registered = true
            cleanupSession = NativeSession(store.state.value.server, store.state.value.token, 0)
            scenario = ActivityScenario.launch(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true))
            scenario.onActivity { activity -> activity.setContent {
                val backdrop = rememberLayerBackdrop()
                val ambient = rememberLayerBackdrop()
                val state by store.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    Canvas(Modifier.fillMaxSize().layerBackdrop(ambient)) {
                        drawRect(Color.White)
                        drawRect(Color.Black, topLeft = androidx.compose.ui.geometry.Offset(size.width*.76f, size.height*.04f),
                            size = androidx.compose.ui.geometry.Size(size.width*.2f, size.height*.1f))
                    }
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop, LocalNativeFeedback provides feedback, LocalNativeStrength provides 72) {
                        NativeCalendarComposer(store, state, inputBackdrop = ambient) { closed.set(true) }
                    }
                }
            } }
            waitFor("记一件事输入框")
            var focused = false
            repeat(30) {
                if (focusedInput() != null) focused = true
                if (!focused) Thread.sleep(100)
            }
            screenshot("calendar-focus.png")
            val input = focusedInput()
            assertTrue("Input did not automatically focus", focused)
            assertTrue(input!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
            }))
            Thread.sleep(300)
            val anchor = bounds("日历输入栏")
            val submit = bounds("提交事项")
            assertEquals("Submit must be circular", submit.width(), submit.height())
            assertTrue("Submit needs breathing room inside input", anchor.right - submit.right > 0)
            screenshot("calendar-input.png")
            tap("↑")
            Thread.sleep(500)
            assertEquals("Submit needs one press and one release", listOf("press", "release"), feedbackEvents.toList())
            screenshot("calendar-loading.png")
            waitFor("确认", 100000)
            Thread.sleep(700)
            val after = bounds("日历输入栏")
            assertEquals("Input anchor moved during expansion", anchor.top, after.top)
            assertNotNull(find("建议"))
            for (unwanted in listOf("建议安排", "尚未保存", "确认保存", "拖动改时间", "回到事项", "返回修改", "有时间或含义待确认，请检查后再保存")) assertNull(find(unwanted))
            assertNull("Options must start collapsed", find("优先级"))
            screenshot(if (deadlineOnly) "calendar-deadline.png" else "calendar-online.png")
            tap("后一天"); Thread.sleep(600); screenshot("calendar-next-day.png")
            tap("前一天"); Thread.sleep(600); screenshot("calendar-return-day.png")
            if (!deadlineOnly) {
                val block = bounds("安排")
                val ticksBefore = feedbackEvents.count { it == "selection" }
                panTimeline(1)
                val ticksAfter = feedbackEvents.count { it == "selection" }
                assertTrue("Axis pan must emit bounded scale ticks", ticksAfter - ticksBefore in 1..4)
                Thread.sleep(200)
                assertEquals("Resting timeline must stay silent", ticksAfter, feedbackEvents.count { it == "selection" })
                panTimeline(-1)
                val original = waitFor("安排").contentDescription.toString()
                val half = block.height() * .2f
                val minutes = waitFor("日历时间线").stateDescription.toString().substringAfter(':').toInt()
                pinch(block.exactCenterX(), block.top + block.height()*.45f, half, half*2)
                val zoomed = waitFor("日历时间线").stateDescription.toString().substringAfter(':').toInt()
                assertTrue("Pinching on an event must zoom the axis", zoomed < minutes*.7)
                assertEquals("Pinch must not edit the event", original, waitFor("安排").contentDescription.toString())
                screenshot("calendar-pinch.png")
                pinch(block.exactCenterX(), block.top + block.height()*.45f, half*2, half)
                assertTrue("Pinch inward must zoom out", waitFor("日历时间线").stateDescription.toString().substringAfter(':').toInt() >= minutes-2)
            }
            moveEvent(if (deadlineOnly) "可拖动" else "安排")
            if (!deadlineOnly) {
                val tick = bounds("12:00")
                moveEvent("截止")
                assertEquals("Selecting by dragging must not shift the time axis", tick.top, bounds("12:00").top)
            }
            screenshot("calendar-dragged.png")
            tap(if (deadlineOnly) "可拖动" else "安排"); Thread.sleep(500)
            waitFor("精确日期")
            waitFor("精确开始时间")
            if (!deadlineOnly) waitFor("精确结束时间")
            assertNull("Time editor must replace Confirm, not overlap it", find("确认"))
            tapField(if (deadlineOnly) "截止" else "开始"); Thread.sleep(700)
            screenshot("calendar-precise-keyboard.png")
            assertNotNull("Time field must receive keyboard focus", focusedInput())
            waitFor("精确日期")
            if (!deadlineOnly) waitFor("精确结束时间")
            waitFor("完成")
            screenshot("calendar-precise-keyboard.png")
            tap("完成"); Thread.sleep(500)
            tap("事件选项"); Thread.sleep(400)
            waitFor("优先级")
            screenshot("calendar-options.png")
            tap("事件选项"); Thread.sleep(350)
            tap("确认")
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!closed.get() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Confirmation did not complete", closed.get())
            val saved = store.calendarItems()
            assertTrue("Real response not saved locally", saved.isNotEmpty())
            if (deadlineOnly) {
                val draft = saved.first().getJSONObject("draft")
                assertNotNull("Jev must return an exact deadline", CalendarDrafts.deadline(draft))
                assertNull("A deadline must not invent a scheduled block", CalendarDrafts.start(draft))
                assertNotEquals("Dragging the deadline did not change its value", "17:00", AcademicClock.clockAt(CalendarDrafts.deadline(draft)!!))
            } else {
                val draft = saved.map { it.getJSONObject("draft") }.first { CalendarDrafts.start(it) != null }
                assertNotEquals("Dragging the range did not change its start", "14:00", AcademicClock.clockAt(CalendarDrafts.start(draft)!!))
                assertEquals("Moving a range must preserve duration", 3600000L, CalendarDrafts.end(draft)!! - CalendarDrafts.start(draft)!!)
            }
            val account = store.calendarAccount()
            val batch = saved.first().getString("batch")
            store.saveCalendar(batch, saved.map { it.getJSONObject("draft") }, account)
            assertEquals("Repeated confirm duplicated items", saved.size, store.calendarItems().size)
            scenario.close(); scenario = null
            store.close(); store = NativeStore(context)
            assertEquals("Saved data did not survive reopening", saved.map { it.toString() }, store.calendarItems().map { it.toString() })
            println("NATIVE_CALENDAR_ONLINE_OK items=${saved.size} source=real-online-jeV localPersistence=true")
        } finally {
            scenario?.close()
            store.close()
            try {
                if (registered) {
                    val api = NativeApi()
                    try { api.request(cleanupSession!!, "/v1/account", "DELETE") } finally { api.close() }
                }
            } finally { context.cleanup(); AcademicClock.setPreviewTime(null) }
        }
    }
    private class IsolatedContext(base: Context, private val prefix: String = "native-calendar-test-${UUID.randomUUID()}-"): ContextWrapper(base) {
        private val names = mutableSetOf<String>()
        private val directory = File(base.noBackupFilesDir, prefix)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory.apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(prefix+name); return baseContext.getSharedPreferences(prefix+name, mode)
        }
        fun cleanup() { names.forEach { baseContext.deleteSharedPreferences(it) }; directory.deleteRecursively() }
    }
}
