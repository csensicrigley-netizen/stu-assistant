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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in real Internet/Jev test. Isolated app data and a disposable STU account. */
@RunWith(AndroidJUnit4::class)
class NativeCalendarOnlineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun find(text: String): AccessibilityNodeInfo? {
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
    private fun screenshot(name: String) {
        val file = File(inst.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
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
            assertEquals("Packaged service must be the production STU origin", "https://stu.oyishi.asia", store.state.value.server)
            store.login("android-calendar-${UUID.randomUUID()}@example.invalid", UUID.randomUUID().toString(), true)
            registered = true
            cleanupSession = NativeSession(store.state.value.server, store.state.value.token, 0)
            scenario = ActivityScenario.launch(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true))
            scenario.onActivity { activity -> activity.setContent {
                val backdrop = rememberLayerBackdrop()
                val state by store.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color.White))
                    CompositionLocalProvider(LocalNativeBackdrop provides backdrop, LocalNativeFeedback provides feedback, LocalNativeStrength provides 72) {
                        NativeCalendarComposer(store, state) { closed.set(true) }
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
            val drag = bounds("可拖动")
            val down = SystemClock.uptimeMillis()
            event(MotionEvent.ACTION_DOWN, down, drag.exactCenterX(), drag.top + drag.height() * .45f)
            repeat(12) { step ->
                event(MotionEvent.ACTION_MOVE, down, drag.exactCenterX() + (step+1)*5f, drag.top + drag.height() * .45f)
                Thread.sleep(16)
            }
            event(MotionEvent.ACTION_UP, down, drag.exactCenterX()+60f, drag.top+drag.height()*.45f)
            screenshot("calendar-dragged.png")
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
    private class IsolatedContext(base: Context): ContextWrapper(base) {
        private val prefix = "native-calendar-test-${UUID.randomUUID()}-"
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
