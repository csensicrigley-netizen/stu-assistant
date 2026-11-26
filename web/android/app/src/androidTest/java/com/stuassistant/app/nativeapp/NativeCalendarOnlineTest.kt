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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

internal fun calendarDeviceScreenshot(inst: android.app.Instrumentation): android.graphics.Bitmap =
    android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("screencap -p")).use {
        android.graphics.BitmapFactory.decodeStream(it) ?: error("Device screenshot could not be decoded")
    }

/** Pure-local calendar composer & timeline gesture instrumented tests (zero external AI calls). */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeCalendarOnlineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun find(text: String): AccessibilityNodeInfo? {
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
            if (node.contentDescription?.toString() == description) {
                val r = Rect(); node.getBoundsInScreen(r)
                val t = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, t, r.exactCenterX(), r.exactCenterY())
                event(MotionEvent.ACTION_UP, t, r.exactCenterX(), r.exactCenterY())
                return
            }
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
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
                            CalendarPrecise(CalendarMark("qa", "英语课", a, a + 3600000)) { _, _ -> saved = true }
                        }
                    }
                }
            } }
            waitFor("精确日期")
            tapField("开始"); Thread.sleep(700)
            assertNotNull(focusedInput())
            waitFor("精确结束时间"); waitFor("精确日期")
            tap("完成")
            Thread.sleep(200)
            assertTrue(saved)
        }
    }

    @Test fun threeDayDragAndBothResizeHandlesKeepTheirMeaning() {
        val start = AcademicClock.dayStart("2026-11-02") + 14 * 3600000L
        val initial = CalendarDrafts.edit(JSONObject().put("title", "跨列测试"), "schedule", start, start + 3600000L)
        val latest = java.util.concurrent.atomic.AtomicReference(initial)
        ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var draft by remember { mutableStateOf(initial) }
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop) {
                        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                            CalendarTimeline("2026-11-02", {}, listOf(draft), 0, emptyList(), null,
                                { _, field, a, b -> draft = CalendarDrafts.edit(draft, field, a, b); latest.set(draft) }, {}, {})
                        }
                    }
                }
            } }
            waitFor("跨列测试")
            Thread.sleep(500)
            fun drag(x: Float, y: Float, dx: Float, dy: Float) {
                val time = SystemClock.uptimeMillis()
                event(MotionEvent.ACTION_DOWN, time, x, y)
                repeat(16) { i -> event(MotionEvent.ACTION_MOVE, time, x + dx * (i + 1) / 16, y + dy * (i + 1) / 16); Thread.sleep(16) }
                event(MotionEvent.ACTION_UP, time, x + dx, y + dy)
                Thread.sleep(300)
            }
            var r = bounds("跨列测试")
            val chart = bounds("日历时间线")
            val scale = inst.targetContext.resources.displayMetrics.density
            val column = (chart.width() - 64 * scale) / 3
            drag(r.exactCenterX(), r.exactCenterY(), column, 0f)
            assertEquals("Moving between columns must change only date", start + 86400000L, CalendarDrafts.start(latest.get()))
            assertEquals(3600000L, CalendarDrafts.end(latest.get())!! - CalendarDrafts.start(latest.get())!!)
            r = bounds("跨列测试")
            val oldEnd = CalendarDrafts.end(latest.get())!!
            drag(r.exactCenterX(), r.top + 6 * scale, 0f, -chart.height() * .08f)
            assertTrue(CalendarDrafts.start(latest.get())!! < start + 86400000L)
            assertEquals("Top grip must keep end fixed", oldEnd, CalendarDrafts.end(latest.get()))
            r = bounds("跨列测试")
            val oldStart = CalendarDrafts.start(latest.get())!!
            drag(r.exactCenterX(), r.bottom - 6 * scale, 0f, chart.height() * .08f)
            assertEquals("Bottom grip must keep start fixed", oldStart, CalendarDrafts.start(latest.get()))
            assertTrue(CalendarDrafts.end(latest.get())!! > oldEnd)
        }
    }

    @Test fun localComposerParsesAndPersistsOfflineWithoutLogin() = runBlocking {
        val context = IsolatedContext(inst.targetContext)
        var store = NativeStore(context)
        val closed = AtomicBoolean(false)
        var scenario: ActivityScenario<NativeActivity>? = null
        val feedbackEvents = CopyOnWriteArrayList<String>()
        val feedback = object : NativeFeedbackSink {
            override fun play(kind: String, strength: Int) { feedbackEvents.add(kind) }
            override fun cancel() = Unit
        }
        try {
            scenario = ActivityScenario.launch(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true))
            scenario.onActivity { activity -> activity.setContent {
                val backdrop = rememberLayerBackdrop()
                val ambient = rememberLayerBackdrop()
                val state by store.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    Canvas(Modifier.fillMaxSize().layerBackdrop(ambient)) {
                        drawRect(Color.White)
                    }
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop, LocalNativeFeedback provides feedback, LocalNativeStrength provides 72) {
                        NativeCalendarComposer(store, state, inputBackdrop = ambient) { closed.set(true) }
                    }
                }
            } }
            waitFor("记一件事输入框")
            val input = focusedInput() ?: waitFor("记一件事输入框")
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "明天 14:00 到 15:00 课题组会")
            }))
            Thread.sleep(300)
            tap("↑")
            waitFor("确认", 10000)
            tap("确认")
            val deadline = SystemClock.uptimeMillis() + 10000
            while (!closed.get() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Confirmation did not complete", closed.get())
            val saved = store.calendarItems()
            assertEquals(1, saved.size)
            scenario.close(); scenario = null
            store.close(); store = NativeStore(context)
            assertEquals(saved.map { it.toString() }, store.calendarItems().map { it.toString() })
        } finally {
            scenario?.close()
            store.close()
            context.cleanup()
        }
    }

    private class IsolatedContext(base: Context, private val prefix: String = "native-calendar-test-${UUID.randomUUID()}-") : ContextWrapper(base) {
        private val names = mutableSetOf<String>()
        private val directory = File(base.noBackupFilesDir, prefix)
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory.apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            names.add(prefix + name); return baseContext.getSharedPreferences(prefix + name, mode)
        }
        fun cleanup() { names.forEach { baseContext.deleteSharedPreferences(it) }; directory.deleteRecursively() }
    }
}
