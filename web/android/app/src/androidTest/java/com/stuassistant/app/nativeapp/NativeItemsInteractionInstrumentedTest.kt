package com.stuassistant.app.nativeapp

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.stuassistant.app.NativeActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Offline regression for disclosure, completion and the academic navigation stack.
 * The fixture owns all records and callbacks; it never reads or mutates a user store.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeItemsInteractionInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun nodes(): List<AccessibilityNodeInfo> {
        inst.uiAutomation.clearCache()
        val root = inst.uiAutomation.rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result += node
            repeat(node.childCount) { node.getChild(it)?.let(queue::add) }
        }
        return result
    }

    private fun find(label: String): AccessibilityNodeInfo? = nodes().firstOrNull {
        it.isVisibleToUser && (it.contentDescription?.toString() == label || it.text?.toString() == label)
    }

    private fun waitFor(label: String, timeout: Long = 15000): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            find(label)?.let { return it }
            Thread.sleep(100)
        }
        val report = nodes().joinToString("\n") { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            "$bounds visible=${node.isVisibleToUser} text=${node.text} desc=${node.contentDescription} state=${node.stateDescription}"
        }
        File(inst.targetContext.getExternalFilesDir(null), "items-missing-ui.txt").writeText(report)
        error("UI missing: $label")
    }

    /** Scroll through the accessibility scroll owner, without screenshot coordinates. */
    private fun reveal(label: String, forward: Boolean = true): AccessibilityNodeInfo {
        repeat(10) {
            find(label)?.let { return it }
            val scroll = nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?: return waitFor(label)
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (!scroll.performAction(action)) return waitFor(label)
            Thread.sleep(350)
        }
        return waitFor(label)
    }

    private fun tap(label: String, forward: Boolean = true, atArrow: Boolean = false) {
        val bounds = Rect().also { reveal(label, forward).getBoundsInScreen(it) }
        val start = SystemClock.uptimeMillis()
        val x = if (atArrow) bounds.right - minOf(22f * inst.targetContext.resources.displayMetrics.density, bounds.width() / 4f) else bounds.exactCenterX()
        fun send(action: Int) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        send(MotionEvent.ACTION_UP)
        Thread.sleep(350)
    }

    private fun back() {
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        Thread.sleep(450)
    }

    private fun screenshot(name: String) {
        val bitmap = calendarDeviceScreenshot(inst)
        try {
            File(inst.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun launch(content: @androidx.compose.runtime.Composable () -> Unit): ActivityScenario<NativeActivity> {
        return ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true)).also { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val base = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    // Only a sibling owns the source layer: controls must never sample themselves.
                    Box(Modifier.fillMaxSize().layerBackdrop(base).background(Color.White))
                    CompositionLocalProvider(LocalNativeBackdrop provides base) { content() }
                }
            } }
        }
    }

    @Test fun disclosureDoesNotCompleteAndCheckboxKeepsRowsVisibleAndUndoable() {
        val courseTitle = "实验报告"
        val localTitle = "导师会议"
        val courseToggles = AtomicInteger()
        val recordToggles = AtomicInteger()
        var state by mutableStateOf(NativeState(calendarFocusDate = "2026-11-02", tasks = listOf(
            JSONObject().put("id", "course-qa").put("title", courseTitle).put("course", "实验课")
                .put("source", "mystu").put("due_at", "2026-11-03T17:00:00+08:00")
                .put("body", "<p>提交 PDF 实验报告</p>").put("url", "https://mystu.stu.edu.cn/qa")
        )))
        var records by mutableStateOf(listOf(JSONObject().put("id", "local-qa").put("done", false)
            .put("draft", JSONObject().put("title", localTitle).put("timezone", "Asia/Shanghai")
                .put("schedule", JSONObject().put("start", "2026-11-02T14:00:00+08:00").put("end", "2026-11-02T15:00:00+08:00"))
                .put("details", JSONObject().put("note", "带上草稿")))))

        launch {
            NativeItemsPage(state, records, onAdd = {}, onTogglePlan = {},
                onToggleRecord = { id ->
                    recordToggles.incrementAndGet()
                    records = records.map { old -> if (old.optString("id") == id) JSONObject(old.toString()).put("done", !old.optBoolean("done")) else old }
                }, onToggleCourse = { id ->
                    courseToggles.incrementAndGet()
                    state = state.copy(done = if (id in state.done) state.done - id else state.done + id)
                })
        }.use {
            waitFor("事项")
            tap("展开详情：$localTitle")
            waitFor("带上草稿")
            assertEquals("Opening local details must not complete the item", 0, recordToggles.get())
            tap("标记完成：$localTitle")
            val completedLocal = waitFor("取消完成：$localTitle")
            assertTrue(completedLocal.isChecked)
            assertEquals("已完成", completedLocal.stateDescription.toString())
            waitFor("收起详情：$localTitle")
            waitFor("带上草稿")
            assertEquals(1, recordToggles.get())
            tap("取消完成：$localTitle")
            assertFalse(waitFor("标记完成：$localTitle").isChecked)
            assertEquals(2, recordToggles.get())
            tap("收起详情：$localTitle")

            reveal("课程动态")
            waitFor("未分类")
            tap("展开详情：$courseTitle", atArrow = true)
            reveal("打开 MySTU")
            waitFor("来源")
            waitFor("截止")
            waitFor("提交 PDF 实验报告")
            assertEquals("Opening course details must not complete the item", 0, courseToggles.get())
            tap("标记完成：$courseTitle", forward = false)
            val completedCourse = waitFor("取消完成：$courseTitle")
            assertTrue(completedCourse.isChecked)
            assertEquals("已完成", completedCourse.stateDescription.toString())
            waitFor("收起详情：$courseTitle")
            assertEquals(1, courseToggles.get())
            screenshot("items-completed-course-retained.png")
            tap("取消完成：$courseTitle")
            assertFalse(waitFor("标记完成：$courseTitle").isChecked)
            assertEquals(2, courseToggles.get())
            assertEquals("Course and local completion must remain independent", 2, recordToggles.get())
        }
    }

    @Test fun academicPagesUseOneEntryAndSystemBackRestoresItemsContext() {
        val taskTitle = "导航保持样例"
        val state = NativeState(calendarFocusDate = "2026-11-02", tasks = listOf(
            JSONObject().put("id", "navigation-qa").put("title", taskTitle).put("course", "测试课程")
                .put("body", "返回事项仍能看到这段详情")
        ), exams = listOf(
            JSONObject().put("id", "exam-qa").put("title", "分析化学期中考试")
                .put("exam_time", "2026-11-20 09:00").put("location", "教学楼 A201")
        ), grades = listOf(
            JSONObject().put("id", "grade-qa").put("semester", "2026-2027-1").put("title", "大学英语")
                .put("score", "88").put("credits", 2).put("gpa", 3.5)
        ))
        launch {
            NativeItemsPage(state, emptyList(), onAdd = {}, onToggleRecord = {}, onTogglePlan = {}, onToggleCourse = {})
        }.use { scenario ->
            waitFor("事项")
            assertNull("Home must not have competing exam navigation buttons", find("考试安排"))
            assertNull(find("课程成绩"))
            tap("展开详情：$taskTitle")
            reveal("返回事项仍能看到这段详情")
            tap("查看考试与成绩", forward = false)
            waitFor("返回事项")
            waitFor("考试安排")
            waitFor("课程成绩")
            screenshot("items-academic-entry.png")
            tap("考试安排")
            waitFor("返回学业")
            waitFor("分析化学期中考试")
            assertNull("Grades must not be overlaid on the exams page", find("大学英语"))
            back()
            waitFor("返回事项")
            tap("课程成绩")
            waitFor("返回学业")
            waitFor("大学英语")
            screenshot("items-grades-page.png")
            back()
            waitFor("返回事项")
            back()
            waitFor("查看考试与成绩")
            assertNotNull(find("事项"))
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            reveal("收起详情：$taskTitle")
            reveal("返回事项仍能看到这段详情")
            screenshot("items-back-restored-details.png")
        }
    }
}
