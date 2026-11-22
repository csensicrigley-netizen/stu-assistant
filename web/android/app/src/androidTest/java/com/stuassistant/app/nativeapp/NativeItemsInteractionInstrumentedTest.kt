package com.stuassistant.app.nativeapp

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.stuassistant.app.NativeActivity
import org.json.JSONObject
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Offline regression for disclosure, completion and the academic navigation stack.
 * The fixture owns all records and callbacks; it never reads or mutates a user store.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeItemsInteractionInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    @Volatile private var activeScenario: ActivityScenario<NativeActivity>? = null

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
        val desc = it.contentDescription?.toString()
        val txt = it.text?.toString()
        it.isVisibleToUser && (
            desc == label ||
                txt == label ||
                (desc != null && desc.startsWith("$label ")) ||
                (txt != null && txt.startsWith("$label "))
            )
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

    private fun waitForCheckbox(label: String, timeout: Long = 15000): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            val all = nodes()
            val node = all.firstOrNull {
                val desc = it.contentDescription?.toString()
                val txt = it.text?.toString()
                it.isVisibleToUser && (
                    desc == label || txt == label ||
                        (desc != null && desc.startsWith("$label ")) ||
                        (txt != null && txt.startsWith("$label "))
                    )
            }
            if (node != null) {
                if (node.isCheckable) return node
                node.parent?.takeIf { it.isCheckable }?.let { return it }
                val targetBounds = Rect().also(node::getBoundsInScreen)
                return all.firstOrNull {
                    it.isVisibleToUser && it.isCheckable && Rect().also(it::getBoundsInScreen) == targetBounds
                } ?: node
            }
            Thread.sleep(100)
        }
        return waitFor(label, 100)
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

    private fun bounds(label: String, forward: Boolean = true) =
        Rect().also { reveal(label, forward).getBoundsInScreen(it) }

    private fun taggedBounds(tag: String): Rect {
        val deadline = SystemClock.uptimeMillis() + 5000L
        do {
            nodes().firstOrNull { it.isVisibleToUser && it.viewIdResourceName == tag }?.let {
                return Rect().also(it::getBoundsInScreen)
            }
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        error("UI missing: $tag")
    }

    private fun assertSheetFieldsAligned(prefix: String) {
        val tags = listOf("$prefix-title", "$prefix-note", "$prefix-schedule")
        var previous: List<Rect>? = null
        repeat(60) {
            val snapshot = nodes()
            val fields = tags.mapNotNull { tag ->
                snapshot.firstOrNull { it.isVisibleToUser && it.viewIdResourceName == tag }
                    ?.let { Rect().also(it::getBoundsInScreen) }
            }
            // Measure a settled sheet from one accessibility tree. The keyboard and
            // sheet can still be arriving between separate accessibility queries.
            if (fields.size == tags.size && fields == previous) {
                val (title, note, schedule) = fields
                val inset = 10f * inst.targetContext.resources.displayMetrics.density
                assertEquals("The title and note text should share the same leading edge", title.left, note.left)
                assertEquals("The note's text inset is measured from the schedule's leading edge", inset, (note.left - schedule.left).toFloat(), 1f)
                assertTrue("The title row belongs above the note", title.bottom <= note.top)
                return
            }
            previous = fields.takeIf { it.size == tags.size }
            SystemClock.sleep(80)
        }
        error("The sheet's fields did not settle: $prefix")
    }

    private fun tap(label: String, forward: Boolean = true) = gesture(bounds(label, forward))

    private fun tapUndo() {
        // This toast enters asynchronously. Looking for it must never scroll the date pager.
        val deadline = SystemClock.uptimeMillis() + 3000
        var previous: Rect? = null
        do {
            val current = find("撤销")?.let { Rect().also(it::getBoundsInScreen) }
            if (current != null && !current.isEmpty && current == previous) {
                gesture(current)
                return
            }
            previous = current
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        error("The undo action did not settle")
    }

    private fun tapCheckbox(label: String) {
        val check = waitForCheckbox(label)
        assertTrue(check.isCheckable)
        gesture(Rect().also(check::getBoundsInScreen))
    }

    private fun gesture(
        bounds: Rect,
        dx: Float = 0f,
        dy: Float = 0f,
        duration: Long = 32,
        cancel: Boolean = false,
        useEvenEventTimes: Boolean = false,
        beforeRelease: (() -> Unit)? = null,
    ) {
        val scenario = checkNotNull(activeScenario) { "No active scenario" }
        val downTime = SystemClock.uptimeMillis()
        fun sendToDecor(action: Int, fraction: Float) {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val loc = IntArray(2)
                decor.getLocationOnScreen(loc)
                val x = bounds.exactCenterX() + dx * fraction - loc[0]
                val y = bounds.exactCenterY() + dy * fraction - loc[1]
                val eventTime = if (useEvenEventTimes) downTime + (duration * fraction).toLong() else SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    val accepted = decor.dispatchTouchEvent(event)
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP && dx == 0f && dy == 0f) {
                        assertTrue("Touch event rejected ($action at $x, $y)", accepted)
                    }
                } finally {
                    event.recycle()
                }
            }
        }
        sendToDecor(MotionEvent.ACTION_DOWN, 0f)
        if (dx != 0f || dy != 0f) {
            repeat(8) { step ->
                SystemClock.sleep(duration / 8)
                sendToDecor(MotionEvent.ACTION_MOVE, (step + 1) / 8f)
            }
        } else SystemClock.sleep(duration)
        beforeRelease?.invoke()
        sendToDecor(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, 1f)
        Thread.sleep(280)
    }

    private fun back() {
        inst.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        Thread.sleep(700)
    }

    private fun spreadFingers(centerX: Float, centerY: Float, halfSpan: Float, beforeRelease: () -> Unit = {}) {
        val scenario = checkNotNull(activeScenario)
        val downTime = SystemClock.uptimeMillis()
        val pointers = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun send(action: Int, count: Int, distance: Float) {
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val origin = IntArray(2).also(decor::getLocationOnScreen)
                val points = Array(2) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = centerX - origin[0] + if (index == 0) -distance else distance
                        y = centerY - origin[1]
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), action, count, pointers, points,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
                )
                try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        val secondPointer = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(MotionEvent.ACTION_DOWN, 1, halfSpan)
        send(MotionEvent.ACTION_POINTER_DOWN or secondPointer, 2, halfSpan)
        repeat(10) { step ->
            SystemClock.sleep(45)
            send(MotionEvent.ACTION_MOVE, 2, halfSpan * (1f + 0.16f * (step + 1)))
        }
        beforeRelease()
        send(MotionEvent.ACTION_POINTER_UP or secondPointer, 2, halfSpan * 2.6f)
        send(MotionEvent.ACTION_UP, 1, halfSpan * 2.6f)
    }

    private fun screenshot(name: String) {
        val bitmap = calendarDeviceScreenshot(inst)
        try {
            File(inst.targetContext.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    private fun launch(
        now: String = "2026-11-02T09:42:00+08:00",
        dark: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): ActivityScenario<NativeActivity> {
        inst.uiAutomation.executeShellCommand("cmd statusbar collapse").close()
        val intent = Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true).putExtra("now", now)
        return ActivityScenario.launch<NativeActivity>(intent).also { scenario ->
            activeScenario = scenario
            scenario.onActivity { activity ->
                activity.setShowWhenLocked(true)
                activity.setTurnScreenOn(true)
                activity.setContent {
                    val base = rememberLayerBackdrop()
                    val sheets = androidx.compose.runtime.remember { NativeSheetController() }
                    val colors = nativeColors(dark, "sweet")
                    Box(Modifier.fillMaxSize().background(colors.bg).semantics { testTagsAsResourceId = true }) {
                        // Only a sibling owns the source layer: controls must never sample themselves.
                        Box(Modifier.fillMaxSize().layerBackdrop(base).background(colors.bg))
                        CompositionLocalProvider(
                            LocalNativeBackdrop provides base,
                            LocalNativeSheets provides sheets,
                            LocalNativeColors provides colors,
                            LocalLiquidGlassDarkTheme provides dark,
                        ) {
                            content()
                            NativeSheetHost(sheets, base)
                        }
                    }
                }
            }
            inst.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5_000
            var ready = false
            while (SystemClock.uptimeMillis() < deadline && !ready) {
                scenario.onActivity { activity ->
                    ready = activity.hasWindowFocus() && activity.window.decorView.width > 0
                }
                if (!ready) Thread.sleep(50)
            }
            Thread.sleep(750)
        }
    }

    @Test fun draggingWeekStripKeepsTheSelectedDateAcrossListAndGrid() {
        val monday = "周一事项"
        val state = NativeState(calendarFocusDate = "2026-11-24")
        val record = ItemsDrafts.newRecord(monday, date = "2026-11-23", startMin = 600, endMin = 660)
        launch(now = "2026-11-24T09:42:00+08:00") {
            NativeItemsPage(state, listOf(record), onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> })
        }.use {
            val tuesday = bounds("11月24日 周二")
            gesture(tuesday, dx = -1.2f * tuesday.width(), duration = 450)
            waitForCheckbox("标记完成：$monday")
            tap("切换清单与日历")
            waitFor("周一 23")
            tap("切换清单与日历")
            waitForCheckbox("标记完成：$monday")
        }
    }

    @Test fun aShortFastGridSwipeCoastsBeyondTheNextDate() {
        val date = "2026-11-24"
        launch(now = "${date}T09:42:00+08:00") {
            NativeItemsPage(
                NativeState(calendarFocusDate = date), emptyList(), initialGrid = true,
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
            )
        }.use {
            val center = bounds("周二 24")
            val dayWidth = center.exactCenterX() - bounds("周一 23").exactCenterX()
            val x = (center.exactCenterX() + 0.4f * dayWidth).toInt()
            val y = center.bottom + 150
            assertNull(find("周五 27"))
            gesture(Rect(x, y, x + 2, y + 2), dx = -0.7f * dayWidth, duration = 80, useEvenEventTimes = true)
            waitFor("周五 27")
        }
    }

    @Test fun aShortTimedItemResizesAtEightAndKeepsItsDurationAcrossDays() {
        val title = "十五分钟事项"
        val state = NativeState(calendarFocusDate = "2026-11-24")
        val record = ItemsDrafts.newRecord(title, date = "2026-11-24", startMin = 480, endMin = 495)
        val writeCount = AtomicInteger()
        val quarterHour = 13f * inst.targetContext.resources.displayMetrics.density
        launch(now = "2026-11-24T09:42:00+08:00", dark = true) {
            NativeItemsPage(
                state, listOf(record), initialGrid = true, initialEditingId = record.getString("id"),
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
                onUpdateRecordDraft = { _, _ -> writeCount.incrementAndGet() },
            )
        }.use {
            gesture(bounds("上边调整开始时间"), dy = quarterHour, duration = 220)
            waitFor("$title 08:00–08:15")
            assertEquals(0, writeCount.get())

            gesture(bounds("下边调整结束时间"), dy = quarterHour, duration = 220)
            waitFor("$title 08:00–08:30")
            gesture(bounds("上边调整开始时间"), dy = quarterHour, duration = 220)
            waitFor("$title 08:15–08:30")
            assertEquals(2, writeCount.get())

            tap("完成编辑")
            val dx = bounds("周三 25").exactCenterX() - bounds("周二 24").exactCenterX()
            gesture(bounds("$title 08:15–08:30"), dx = dx, duration = 220)
            waitFor("已改期至 11月25日 周三 08:15")
            waitFor("$title 08:15–08:30")
            assertEquals(3, writeCount.get())

            gesture(bounds("$title 08:15–08:30"), dy = quarterHour * 2f, duration = 220, cancel = true)
            waitFor("$title 08:15–08:30")
            assertEquals("Cancelling the gesture must not save a draft", 3, writeCount.get())
        }
    }

    @Test fun theBottomOfTheGridHasSpaceToKeepAnEditedCardAboveItsSheet() {
        val date = "2026-11-24"
        val title = "晚间验收"
        val label = "$title 21:15–22:00"
        val record = ItemsDrafts.newRecord(title, date = date, startMin = 21 * 60 + 15, endMin = 22 * 60)
        launch(now = "${date}T20:30:00+08:00") {
            NativeItemsPage(
                NativeState(calendarFocusDate = date), listOf(record), initialGrid = true,
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
            )
        }.use {
            tap(label)
            waitFor("完成编辑")
            repeat(12) {
                val card = find(label)
                if (card != null && Rect().also(card::getBoundsInScreen).bottom < taggedBounds("items-edit-sheet").top) return@use
                Thread.sleep(150)
            }
            throw AssertionError("The edit sheet still covers the selected grid card")
        }
    }

    @Test fun aDeadlineOpensWithOneTimeAndNoResizeHandles() {
        val date = "2026-11-24"
        val title = "提交材料"
        val record = JSONObject().put("id", "deadline").put(
            "draft", JSONObject().put("title", title)
                .put("deadline", JSONObject().put("kind", "instant").put("value", "${date}T10:30:00+08:00")),
        )
        launch(now = "${date}T09:42:00+08:00") {
            NativeItemsPage(
                NativeState(calendarFocusDate = date), listOf(record), initialGrid = true,
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
            )
        }.use {
            tap("$title 10:30 截止")
            tap("11/24 周二 · 10:30 截止")
            waitFor("截止时间 10:30")
            assertNull(find("上边调整开始时间"))
            assertNull(find("下边调整结束时间"))
            assertNull(find("结束时间"))
        }
    }

    @Test fun twoFingerZoomSnapsToSingleDayWithoutMovingAnItem() {
        val date = "2026-11-24"
        val title = "缩放测试事项"
        val record = ItemsDrafts.newRecord(title, date = date, startMin = 600, endMin = 660)
        val writes = AtomicInteger()
        launch(now = "${date}T09:42:00+08:00", dark = true) {
            NativeItemsPage(
                NativeState(calendarFocusDate = date), listOf(record), initialGrid = true,
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
                onUpdateRecordDraft = { _, _ -> writes.incrementAndGet() },
            )
        }.use {
            waitFor("切换日历视图刻度，当前3日")
            val event = bounds("$title 10:00–11:00")
            val dayWidth = bounds("周三 25").exactCenterX() - bounds("周二 24").exactCenterX()
            spreadFingers(event.exactCenterX() + dayWidth * 0.25f, event.exactCenterY(), dayWidth * 0.25f) {
                waitFor("1日视图")
                assertFalse(nodes().any { node ->
                    listOfNotNull(node.text, node.contentDescription).any { Regex("""\d+\.\d+\s*日""").containsMatchIn(it) }
                })
            }
            waitFor("切换日历视图刻度，当前1日")
            assertEquals(0, writes.get())
        }
    }

    @Test fun undoRestoresBothSidesOfALinkedHomework() {
        val date = "2026-11-24"
        val taskId = "linked-task"
        val taskTitle = "实验作业"
        val itemTitle = "写实验作业"
        var state by mutableStateOf(NativeState(calendarFocusDate = date, tasks = listOf(
            JSONObject().put("id", taskId).put("title", taskTitle).put("course", "实验课")
                .put("due_at", "${date}T17:00:00+08:00"),
        )))
        val record = ItemsDrafts.newRecord(itemTitle, date = date, startMin = 840, endMin = 900, schoolTaskId = taskId)
        launch(now = "${date}T09:42:00+08:00") {
            NativeItemsPage(
                state, listOf(record),
                onSetRecordDone = { _, _ -> },
                onSetCourseDone = { id, done -> state = state.copy(done = if (done) state.done + id else state.done - id) },
            )
        }.use {
            for (title in listOf(taskTitle, itemTitle)) {
                tap("标记完成：$title")
                assertTrue(waitForCheckbox("取消完成：$taskTitle").isChecked)
                assertTrue(waitForCheckbox("取消完成：$itemTitle").isChecked)
                tapUndo()
                assertFalse(waitForCheckbox("标记完成：$taskTitle").isChecked)
                assertFalse(waitForCheckbox("标记完成：$itemTitle").isChecked)
            }
        }
    }

    @Test fun quickAddKeepsItsDraftAndUsesTheTimeTypedInTheTitle() {
        val saved = AtomicReference<JSONObject>()
        val text = "明天 15:00 写实验报告"
        launch(now = "2026-11-24T09:42:00+08:00") {
            NativeItemsPage(
                NativeState(calendarFocusDate = "2026-11-24"), emptyList(),
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
                onUpsertRecord = { record, _ -> saved.set(record) },
            )
        }.use {
            tap("记一件事")
            waitFor("收起新建")
            val editor = nodes().first { it.isVisibleToUser && it.isEditable }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            waitFor(text)
            tap("收起新建")
            waitFor("草稿已保留，点 + 继续")
            tap("记一件事")
            waitFor(text)
            tap("保存事项")
            waitFor("已保存到 11月25日 15:00")
            val item = checkNotNull(ItemsDrafts.parseRecord(checkNotNull(saved.get())))
            assertEquals("写实验报告", item.title)
            assertEquals("2026-11-25", item.date)
            assertEquals("15:00", item.startClock)
        }
    }

    @Test fun quickAddPassesTheTypedDraftToAiAndKeepsTheLocalCopy() {
        val handoff = AtomicReference<CalendarComposerDraft>()
        val title = "明天写实验报告"
        val note = "核对第三组数据"
        launch(now = "2026-11-24T09:42:00+08:00") {
            NativeItemsPage(
                NativeState(token = "offline-fixture", calendarFocusDate = "2026-11-24"), emptyList(),
                onOpenAiComposer = { handoff.set(it) },
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
            )
        }.use {
            tap("记一件事")
            waitFor("收起新建")
            fun type(tag: String, value: String) {
                val field = nodes().first { it.isVisibleToUser && it.isEditable && it.viewIdResourceName == tag }
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }))
                waitFor(value)
            }
            type("items-quick-title", title)
            type("items-quick-note", note)
            tap("AI")
            waitFor("记一件事")
            val draft = checkNotNull(handoff.get())
            assertEquals(title, draft.text)
            assertEquals(note, draft.note)
            val candidate = JSONObject().put("title", "写实验报告")
            assertEquals(note, draft.applyTo(candidate).getJSONObject("details").getString("note"))
            assertFalse(candidate.has("details"))
            tap("记一件事")
            waitFor(title)
            waitFor(note)
        }
    }

    @Test fun pickingATimeFreeDateCreatesAnAllDayItem() {
        val saved = AtomicReference<JSONObject>()
        val title = "整理讲义"
        launch(now = "2026-11-24T09:42:00+08:00") {
            NativeItemsPage(
                NativeState(calendarFocusDate = "2026-11-24"), emptyList(), initialRoute = "items:inbox",
                onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> },
                onUpsertRecord = { record, _ -> saved.set(record) },
            )
        }.use {
            tap("记一件事")
            waitFor("收起新建")
            assertSheetFieldsAligned("items-quick")
            val editor = nodes().first { it.isVisibleToUser && it.isEditable }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
            }))
            waitFor(title)
            gesture(taggedBounds("items-quick-schedule"))
            waitFor("选时间")
            tap("明天")
            waitFor("11/25 周三 · 全天")
            assertNull(find("开始时间 15:00"))
            tap("保存事项")
            waitFor("已保存到 11月25日 全天")
            val item = checkNotNull(ItemsDrafts.parseRecord(checkNotNull(saved.get())))
            assertTrue(item.isAllDay)
            assertNull(item.startMin)
            assertEquals("2026-11-25", item.date)
        }
    }

    @Test fun disclosureDoesNotCompleteAndCheckboxKeepsRowsVisibleAndUndoable() {
        val courseTitle = "实验报告"
        val localTitle = "导师会议"
        val courseToggles = AtomicInteger()
        val recordToggles = AtomicInteger()
        var state by mutableStateOf(NativeState(calendarFocusDate = "2026-11-02", tasks = listOf(
            JSONObject().put("id", "course-qa").put("title", courseTitle).put("course", "实验课")
                .put("source", "mystu").put("due_at", "2026-11-02T17:00:00+08:00")
                .put("url", "https://mystu.stu.edu.cn/qa")
        )))
        var records by mutableStateOf(listOf(JSONObject().put("id", "local-qa").put("done", false)
            .put("draft", JSONObject().put("title", localTitle).put("timezone", "Asia/Shanghai")
                .put("schedule", JSONObject().put("start", "2026-11-02T14:00:00+08:00").put("end", "2026-11-02T15:00:00+08:00"))
                .put("details", JSONObject().put("note", "带上草稿")))))

        launch {
            NativeItemsPage(state, records, onAdd = {}, onTogglePlan = {},
                onSetRecordDone = { id, done ->
                    recordToggles.incrementAndGet()
                    records = records.map { old -> if (old.optString("id") == id) JSONObject(old.toString()).put("done", done) else old }
                }, onSetCourseDone = { id, done ->
                    courseToggles.incrementAndGet()
                    state = state.copy(done = if (done) state.done + id else state.done - id)
                })
        }.use {
            waitFor("事项")
            tap(localTitle)
            waitFor("带上草稿")
            waitFor("删除")
            waitFor("完成编辑")
            assertEquals("Opening edit sheet must not complete the item", 0, recordToggles.get())
            assertSheetFieldsAligned("items-edit")
            tap("完成编辑")
            waitFor("记一件事")

            tap("标记完成：$localTitle")
            val completedLocal = waitForCheckbox("取消完成：$localTitle")
            assertTrue(completedLocal.isChecked)
            assertEquals("已完成", completedLocal.stateDescription.toString())
            assertEquals(1, recordToggles.get())
            tapUndo()
            assertFalse(waitForCheckbox("标记完成：$localTitle").isChecked)
            assertEquals(2, recordToggles.get())

            reveal(courseTitle)
            tap(courseTitle)
            waitFor("打开 MySTU")
            waitFor("来源")
            waitFor("截止")
            assertEquals("Opening course detail sheet must not complete the homework", 0, courseToggles.get())
            back()
            waitFor("记一件事")

            tap("标记完成：$courseTitle", forward = false)
            val completedCourse = waitForCheckbox("取消完成：$courseTitle")
            assertTrue(completedCourse.isChecked)
            assertEquals("已完成", completedCourse.stateDescription.toString())
            assertEquals(1, courseToggles.get())
            screenshot("items-completed-course-retained.png")
            tapCheckbox("取消完成：$courseTitle")
            assertEquals(2, courseToggles.get())
            assertFalse("The second touch must clear homework completion", "course-qa" in state.done)
            assertFalse(waitForCheckbox("标记完成：$courseTitle").isChecked)
            assertEquals("Course and local completion must remain independent", 2, recordToggles.get())
        }
    }

    @Test fun academicPagesUseOneEntryAndSystemBackRestoresItemsContext() {
        val taskTitle = "导航保持样例"
        val state = NativeState(calendarFocusDate = "2026-11-02", tasks = listOf(
            JSONObject().put("id", "navigation-qa").put("title", taskTitle).put("course", "测试课程")
                .put("due_at", "2026-11-02T17:00:00+08:00")
        ), exams = listOf(
            JSONObject().put("id", "exam-qa").put("title", "分析化学期中考试")
                .put("exam_time", "2026-11-20 09:00").put("location", "教学楼 A201")
        ), grades = listOf(
            JSONObject().put("id", "grade-qa").put("semester", "2026-2027-1").put("title", "大学英语")
                .put("score", "88").put("credits", 2).put("gpa", 3.5)
        ))
        launch {
            NativeItemsPage(state, emptyList(), onAdd = {}, onSetRecordDone = { _, _ -> }, onSetCourseDone = { _, _ -> })
        }.use { scenario ->
            waitFor("事项")
            waitFor("日程")
            waitFor("待安排")
            waitFor("作业")
            assertNull("Home must not have competing exam section", find("考试与考核"))
            assertNull("Home must not have competing grades section", find("课程成绩"))

            tap("查看考试与成绩", forward = false)
            waitFor("返回事项")
            val examHeading = bounds("考试与考核")
            SystemClock.sleep(200)
            val beforeSwipe = bounds("返回事项")
            val dp = inst.targetContext.resources.displayMetrics.density
            val edgeTouch = Rect(0, examHeading.top, (30f * dp).toInt(), examHeading.bottom)
            gesture(edgeTouch, dx = 30f * dp, duration = 560) {
                assertTrue("The overlay must follow the finger", bounds("返回事项").left > beforeSwipe.left + 5)
            }
            SystemClock.sleep(320)
            assertTrue("A short swipe should settle back into the academic page", kotlin.math.abs(bounds("返回事项").left - beforeSwipe.left) <= 2)
            waitFor("考试与考核")
            reveal("分析化学期中考试")
            reveal("课程成绩")
            reveal("大学英语")
            screenshot("items-academic-entry.png")

            back()
            waitFor("查看考试与成绩")
            assertNotNull(find("事项"))
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            reveal(taskTitle, forward = false)
            screenshot("items-back-restored-details.png")
        }
    }
}
