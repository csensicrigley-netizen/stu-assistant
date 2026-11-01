package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

val LocalCalendarComposer = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun NativeCalendarItemsScreen(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("items") }
    var day by rememberSaveable { mutableStateOf(AcademicClock.moment().date) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val open = LocalCalendarComposer.current
    LaunchedEffect(s.calendarLastBatch) { s.calendarFocusDate?.let { day = it } }
    LaunchedEffect(s.server, s.user?.optString("id"), s.calendarRevision) {
        records = emptyList()
        if (!s.preview && s.token != null) try { records = store.calendarItems() }
        catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(s.token) { if (!s.preview && s.token != null) runCatching { store.loadPlans() } }
    NPage("事项", actions = {
        NButton(if (section == "exams") "返回" else "考试", { section = if (section == "exams") "items" else "exams" })
        NButton(if (section == "grades") "返回" else "成绩", { section = if (section == "grades") "items" else "grades" })
    }) {
        AnimatedContent(section, transitionSpec = { (fadeIn(tween(220)) + slideInHorizontally { it / 8 }) togetherWith fadeOut(tween(150)) }, label = "items-section") { target ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                when (target) {
                    "exams" -> AcademicExams(s.exams.map(AcademicRecords::exam), AcademicClock.nowMillis())
                    "grades" -> AcademicGrades(s.grades.map(AcademicRecords::grade))
                    else -> {
                        NButton("＋ 记一件事", { if (s.preview || s.token == null) onConnect() else open() }, primary = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            NButton("‹", { day = AcademicClock.addDays(day, -1) })
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { NText("${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}", 18, true) }
                            NButton("›", { day = AcademicClock.addDays(day, 1) })
                        }
                        NError(error)
                        AnimatedContent(day, transitionSpec = {
                            val direction = if (targetState > initialState) 1 else -1
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it * direction / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally(tween(220)) { -it * direction / 3 } + fadeOut())
                        }, label = "agenda-day") { date ->
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                val dated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == date }
                                val old = s.plans.filter { it.optString("date") == date }
                                if (dated.isEmpty() && old.isEmpty()) NText("这一天还空着", 16, muted = true)
                                dated.forEach { record ->
                                    val draft = record.getJSONObject("draft")
                                    NRow((if (record.optBoolean("done")) "✓  " else "○  ") + draft.optString("title"), CalendarDrafts.label(draft), onClick = {
                                        val account = store.calendarAccount()
                                        scope.launch { try { store.toggleCalendar(record.optString("id"), account) } catch (e: Exception) { error = e.message } }
                                    })
                                }
                                old.forEach { plan -> NRow(plan.optString("title"), plan.optString("start"), if (plan.optBoolean("done")) "✓" else "○", onClick = {
                                    scope.launch { try { store.togglePlan(plan.optString("id"), !plan.optBoolean("done")) } catch (e: Exception) { error = e.message } }
                                }) }
                            }
                        }
                        val undated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == null }
                        if (undated.isNotEmpty()) {
                            NTitle("待安排", 21)
                            undated.forEach { record -> NRow(record.getJSONObject("draft").optString("title"), trailing = if (record.optBoolean("done")) "✓" else "○", onClick = {
                                val account = store.calendarAccount()
                                scope.launch { try { store.toggleCalendar(record.optString("id"), account) } catch (e: Exception) { error = e.message } }
                            }) }
                        }
                        if (s.tasks.isNotEmpty()) {
                            NDivider(); NTitle("课程动态", 21)
                            s.tasks.map(AcademicRecords::task).filter { it.id !in s.done }.take(12).forEach { task ->
                                NRow(task.title, task.course, "○", onClick = { store.toggleTask(task.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Root sibling above the navigation: input anchor never moves when the surface grows. */
@Composable
fun NativeCalendarComposer(store: NativeStore, s: NativeState, close: () -> Unit) {
    val colors = LocalNativeColors.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf("") }
    var phase by rememberSaveable { mutableStateOf("input") }
    var packed by rememberSaveable { mutableStateOf("[]") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableStateOf(AcademicClock.moment().date) }
    var issueText by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var manualMenu by remember { mutableStateOf(false) }
    val account = remember { store.calendarAccount() }
    val batch = rememberSaveable { UUID.randomUUID().toString() }
    val drafts = remember(packed) { academicObjects(JSONArray(packed)) }
    val expanded by animateFloatAsState(if (phase == "input") 0f else 1f, tween(520, easing = FastOutSlowInEasing), label = "surface-expansion")
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show(); records = store.calendarItems() }
    LaunchedEffect(s.server, s.user?.optString("id")) { if (runCatching { store.calendarAccount() }.getOrNull() != account) close() }
    // A killed in-flight request is never silently reissued (it may already have cost quota).
    LaunchedEffect(Unit) { if (phase == "loading" && job == null) { phase = "input"; error = "上次识别已中断，可手动重试" } }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    BackHandler { job?.cancel(); keyboard?.hide(); close() }
    fun change(index: Int, draft: JSONObject) { packed = JSONArray(drafts.mapIndexed { i, old -> if (i == index) draft else old }).toString() }
    fun submit(manual: Boolean) {
        if (text.isBlank() || saving || phase == "loading") return
        keyboard?.hide(); manualMenu = false; error = null; issueText = ""; packed = "[]"; phase = "loading"
        job = scope.launch {
            try {
                val result = if (manual) listOf(JSONObject().put("title", text.trim()).put("timezone", "Asia/Shanghai").put("reminders", JSONArray()))
                else {
                    val response = store.inferCalendar(text.trim())
                    val items = academicObjects(response.optJSONArray("items"))
                    require(items.isNotEmpty()) { "服务未返回可用事项" }
                    if (items.any { (it.optJSONArray("issues")?.length() ?: 0) > 0 } || (response.optJSONArray("issues")?.length() ?: 0) > 0)
                        issueText = "有时间或含义待确认，请检查后再保存"
                    items.map { it.getJSONObject("draft") }
                }
                packed = JSONArray(result).toString(); selected = 0
                day = CalendarDrafts.date(result.first()) ?: day
                phase = "review"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { phase = "input"; error = if (e is NativeApiError) when (e.code) {
                "timeout" -> "识别超时，结果未知；请自行决定是否重试"
                "network" -> "网络连接失败，可重试或手动记录"
                else -> when (e.status) {
                    401 -> "登录已过期，请重新登录"
                    429 -> "请求过于频繁或今日用量已达上限，请稍后再试"
                    503 -> "在线识别暂不可用，可稍后重试或手动记录"
                    504 -> "识别超时，结果未知；请自行决定是否重试"
                    else -> "在线识别未完成，请重试或手动记录"
                }
            } else e.message ?: "识别未完成" }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.bg.copy(alpha = .25f + expanded * .75f)).pointerInput(phase) {
        detectTapGestures { if (phase == "input") { keyboard?.hide(); close() } }
    }) {
        val screenHeight = maxHeight
        Box(Modifier.fillMaxWidth().height(140.dp + (screenHeight - 140.dp) * expanded)
            .clip(RoundedCornerShape(bottomStart = (32 * (1 - expanded)).dp, bottomEnd = (32 * (1 - expanded)).dp))
            .background(colors.bg))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth().semantics { contentDescription = "日历输入栏" }
                .clip(RoundedCornerShape(28.dp)).background(colors.surface).padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(text, { if (phase == "input") text = it.take(200) },
                    Modifier.weight(1f).height(56.dp).wrapContentHeight(Alignment.CenterVertically).focusRequester(focus)
                        .semantics { contentDescription = "记一件事输入框" },
                    enabled = phase == "input", maxLines = 2,
                    textStyle = TextStyle(color = colors.ink, fontSize = 17.sp, lineHeight = 24.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(false) }),
                    decorationBox = { inner -> Box { if (text.isEmpty()) NText("想做什么，写下来…", 17, muted = true); inner() } })
                if (phase == "input") Box(Modifier.size(36.dp).semantics { contentDescription = "记录方式" }
                    .clickable { manualMenu = !manualMenu }, contentAlignment = Alignment.Center) { NText("⋯", 21, muted = true) }
                else Spacer(Modifier.width(36.dp))
                NButton(if (phase == "input") "↑" else "×", {
                    if (phase == "input") submit(false) else { job?.cancel(); keyboard?.hide(); close() }
                }, enabled = phase != "input" || text.isNotBlank(), primary = phase == "input")
            }
            AnimatedVisibility(phase == "input" && (manualMenu || error != null), enter = fadeIn(tween(180)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NError(error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        NButton("手动记录", { submit(true) }, enabled = text.isNotBlank())
                    }
                }
            }
            AnimatedVisibility(phase != "input", modifier = Modifier.weight(1f), enter = fadeIn(tween(350, 100)) + expandVertically(tween(440)), exit = fadeOut(tween(160))) {
                Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        NTitle("建议", 32)
                        if (drafts.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                drafts.forEachIndexed { index, draft ->
                                    if (drafts.size > 1) NButton(draft.optString("title"), { selected = index; day = CalendarDrafts.date(draft) ?: day }, primary = selected == index)
                                }
                            }
                            NText(CalendarDrafts.label(drafts[selected.coerceIn(drafts.indices)]), 23, true)
                        }
                    }
                    AnimatedContent(phase == "loading", transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) }, label = "result-reveal") { loading ->
                        if (loading) CalendarSkeleton()
                        else if (drafts.isNotEmpty()) {
                            val doc = AcademicTimetable.normalize(if (s.sample) null else s.timetable)
                            val existing = calendarMarks(records.filter { !it.optBoolean("done") }.map { it.getJSONObject("draft") }, "local") +
                                s.plans.filter { !s.sample && !it.optBoolean("done") }.mapNotNull { plan ->
                                    val a = AcademicClock.parseTime("${plan.optString("date")}T${plan.optString("start")}:00")
                                    val b = AcademicClock.parseTime("${plan.optString("date")}T${plan.optString("end")}:00")
                                    a?.let { CalendarMark("legacy-${plan.optString("id")}", plan.optString("title"), it, b) }
                                }
                            CalendarTimeline(day, { day = it }, drafts, selected, existing, doc, { i, field, start, end ->
                                change(i, CalendarDrafts.edit(drafts[i], field, start, end))
                            }, { index -> selected = index })
                        }
                    }
                    AnimatedVisibility(phase == "review") {
                        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (issueText.isNotEmpty()) NText(issueText, 14, muted = true)
                            if (drafts.any { (it.optJSONArray("reminders")?.length() ?: 0) > 0 }) NText("提醒意图会保留，此版本暂不发送通知", 14, muted = true)
                            NError(error)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                NButton("返回修改", { phase = "input"; scope.launch { focus.requestFocus(); keyboard?.show() } }, enabled = !saving)
                                NButton(if (saving) "保存中…" else "确认", {
                                    if (!saving) { saving = true; scope.launch {
                                        try { store.saveCalendar(batch, drafts, account); close() }
                                        catch (e: Exception) { error = e.message }
                                        finally { saving = false }
                                    } }
                                }, enabled = !saving, primary = true, modifier = Modifier.widthIn(min = 124.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CalendarSkeleton() {
    val pulse = rememberInfiniteTransition(label = "inference")
    val alpha by pulse.animateFloat(.18f, .45f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "skeleton")
    val colors = LocalNativeColors.current
    Box(Modifier.fillMaxWidth().height(270.dp).semantics { contentDescription = "正在在线识别" }) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(82.dp).background(colors.muted.copy(alpha = alpha / 3)))
        Box(Modifier.align(Alignment.Center).width(110.dp).height(82.dp).clip(RoundedCornerShape(20.dp)).background(colors.brand.copy(alpha = alpha)))
    }
}

@Composable
private fun CalendarTimeline(day: String, onDay: (String) -> Unit, drafts: List<JSONObject>, selected: Int,
    existing: List<CalendarMark>, doc: AcademicTimetableDoc?, onEdit: (Int, String, Long, Long?) -> Unit, onSelect: (Int) -> Unit) {
    val colors = LocalNativeColors.current
    val density = LocalDensity.current
    var windowStart by remember { mutableFloatStateOf(8f * 60) }
    var windowMinutes by remember { mutableFloatStateOf(6f * 60) }
    val displayedStart by animateFloatAsState(windowStart, tween(180, easing = FastOutSlowInEasing), label = "time-window")
    var precise by remember { mutableStateOf<CalendarMark?>(null) }
    val currentMarks = calendarMarks(drafts)
    LaunchedEffect(selected) {
        val mark = currentMarks.firstOrNull { it.item == selected }
        if (mark != null) {
            val duration = ((mark.end ?: mark.start) - mark.start) / 60000f
            windowMinutes = (duration + 240).coerceIn(360f, 1440f)
            val minute = AcademicClock.moment(mark.start).minutes.toFloat() + duration / 2
            windowStart = (minute - windowMinutes / 2).coerceIn(0f, 1440f - windowMinutes)
        }
    }
    Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        NButton("‹", { onDay(AcademicClock.addDays(day, -1)) }, modifier = Modifier.semantics { contentDescription = "前一天" })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Crossfade(day, Modifier.fillMaxWidth(), animationSpec = tween(180), label = "date-label") { label ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    NText("${AcademicClock.monthDay(label)} · ${AcademicClock.weekdayText(AcademicClock.weekday(label))}", 18, true)
                }
            }
        }
        NButton("›", { onDay(AcademicClock.addDays(day, 1)) }, modifier = Modifier.semantics { contentDescription = "后一天" })
    }
    AnimatedContent(day, Modifier.fillMaxWidth().clipToBounds(), transitionSpec = {
        val sign = if (targetState > initialState) 1 else -1
        (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { sign * it } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -sign * it } + fadeOut(tween(180)))
    }, label = "timeline-day-slide") { date ->
        val midnight = AcademicClock.dayStart(date)
        val courses = AcademicTimetable.planFor(doc, date).blocks.map { CalendarMark(it.key, it.course.title,
            midnight + it.startMin * 60000L, midnight + it.endMin * 60000L, source = "course") }
        val marks = (courses + existing + currentMarks).filter { it.start < midnight + 86400000 && (it.end ?: it.start) >= midnight }
            .sortedBy { if (it.item == selected) 2 else if (it.item >= 0) 1 else 0 }
        BoxWithConstraints(Modifier.fillMaxWidth().height(270.dp).clipToBounds()) {
            val track = rememberLayerBackdrop()
            val width = constraints.maxWidth.toFloat()
            fun x(ms: Long) = ((ms - midnight) / 60000f - displayedStart) / windowMinutes * width
            fun at(px: Float) = midnight + ((displayedStart + px / width * windowMinutes) * 60000).toLong()
            Box(Modifier.fillMaxSize().layerBackdrop(track)) {
            Box(Modifier.fillMaxSize().pointerInput(date, windowMinutes) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val old = windowMinutes
                    val next = (old / zoom).coerceIn(120f, 1440f)
                    windowStart = (windowStart + centroid.x / width * (old - next) - pan.x / width * old).coerceIn(0f, 1440f - next)
                    windowMinutes = next
                }
            }.pointerInput(date, windowMinutes, windowStart, selected) {
                detectTapGestures(onLongPress = { position ->
                    onEdit(selected, "schedule", CalendarDrafts.snap(at(position.x)), null)
                })
            }) {
                val step = if (windowMinutes > 720) 360 else if (windowMinutes > 300) 120 else 60
                for (minute in 0..1440 step step) {
                    val px = (minute - displayedStart) / windowMinutes * width
                    if (px in -30f..(width + 30)) {
                        Box(Modifier.offset { IntOffset(px.roundToInt(), 54.dp.roundToPx()) }.width(1.dp).height(140.dp).background(colors.border.copy(alpha = .55f)))
                        Box(Modifier.offset { IntOffset((px - 24.dp.toPx()).roundToInt(), 20.dp.roundToPx()) }.width(48.dp), contentAlignment = Alignment.Center) {
                            NText(AcademicClock.clock(minute), 12, muted = true)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = 90.dp).height(72.dp).background(colors.surface.copy(alpha = .32f)))
            }
            marks.forEach { mark -> key(mark.key) {
                val left = x(mark.start)
                val right = mark.end?.let(::x) ?: left
                if (right >= -48 && left <= width + 48) {
                    val editable = mark.item >= 0
                    val tint = when { mark.field == "deadline" -> Color(0xFFFF8FA4); editable -> colors.brand; mark.source == "course" -> colors.muted; else -> Color(0xFF75B6A8) }
                    var delta by remember { mutableFloatStateOf(0f) }
                    var edge by remember { mutableIntStateOf(0) }
                    val currentEdit by rememberUpdatedState(onEdit)
                    val currentMark by rememberUpdatedState(mark)
                    val point = mark.end == null
                    val targetWidth = if (point) 48.dp else with(density) { (right-left).coerceAtLeast(48.dp.toPx()).toDp() }
                    val offset = if (point) left - with(density) { 24.dp.toPx() } else left
                    val modifier = Modifier.offset { IntOffset(offset.roundToInt(), 66.dp.roundToPx()) }.width(targetWidth).height(144.dp)
                        .semantics { contentDescription = "${mark.title} ${if (mark.field == "deadline") "截止" else "安排"} ${CalendarDrafts.iso(mark.start)}${if (editable) " 可拖动" else " 只读"}" }
                    Box(modifier.then(if (editable) Modifier.pointerInput(mark.key, windowMinutes, date) {
                        var original = currentMark
                        detectDragGestures(onDragStart = { pos ->
                            original = currentMark; delta = 0f; onSelect(original.item)
                            edge = if (original.end == null) 0 else when {
                                pos.x < 18.dp.toPx() -> -1
                                pos.x > size.width - 18.dp.toPx() -> 1
                                else -> 0
                            }
                        }, onDrag = { change, amount ->
                            change.consume(); delta += amount.x
                            val shift = (delta / width * windowMinutes * 60000).toLong()
                            val a = original.start; val b = original.end
                            val newStart = if (edge == 1) a else (a + shift).coerceIn(midnight, midnight + 86340000)
                            val newEnd = when { b == null -> null; edge == -1 -> b.coerceAtLeast(newStart + 60000); edge == 1 -> (b + shift).coerceAtMost(midnight + 86400000).takeIf { it > a + 300000 }; else -> newStart + (b-a) }
                            currentEdit(original.item, original.field, newStart, newEnd)
                        }, onDragEnd = {
                            val m = currentMark
                            currentEdit(m.item, m.field, CalendarDrafts.snap(m.start), m.end?.let(CalendarDrafts::snap)?.takeIf { it > CalendarDrafts.snap(m.start) })
                        }, onDragCancel = { currentEdit(original.item, original.field, original.start, original.end) })
                    }.pointerInput(mark.key) { detectTapGestures { onSelect(currentMark.item); precise = currentMark } } else Modifier)) {
                        if (point) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).width(2.dp).height(94.dp).background(tint))
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 86.dp).size(28.dp).clip(CircleShape).background(tint.copy(alpha = .18f)), contentAlignment = Alignment.Center) { Box(Modifier.size(14.dp).clip(CircleShape).background(tint)) }
                        } else {
                            Box(Modifier.fillMaxWidth().padding(top = 26.dp).height(72.dp).clip(RoundedCornerShape(15.dp))
                                .background(tint.copy(alpha = if (editable) .30f else .16f))
                                .border(if (editable) 1.dp else 0.dp, if (editable) tint.copy(alpha = .7f) else Color.Transparent, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                                BasicText(mark.title, Modifier.padding(horizontal = 8.dp), style = TextStyle(color = tint, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Clip)
                                if (editable) {
                                    Box(Modifier.align(Alignment.CenterStart).padding(start = 5.dp).width(2.dp).height(18.dp).background(tint.copy(alpha = .75f)))
                                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).width(2.dp).height(18.dp).background(tint.copy(alpha = .75f)))
                                }
                            }
                        }
                        if (point && editable) BasicText(if (mark.field == "deadline") "截止" else mark.title,
                            Modifier.align(Alignment.BottomCenter), style = TextStyle(color = tint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // A point's separate hollow tail grows a range; the main round grip always moves the point.
                    if (point && editable && mark.field == "schedule") {
                        Box(Modifier.offset { IntOffset((left + 12.dp.toPx()).roundToInt(), 112.dp.roundToPx()) }.size(44.dp)
                            .semantics { contentDescription = "拖出时长" }
                            .pointerInput(mark.key, date, windowMinutes) {
                                var start = mark.start; var distance = 0f
                                detectDragGestures(onDragStart = { start = currentMark.start; distance = 0f }, onDrag = { change, amount ->
                                    change.consume(); distance += amount.x
                                    // Keep this grip mounted for the full gesture; commit on release.
                                    delta = distance
                                }, onDragEnd = {
                                    val end = CalendarDrafts.snap(start + (distance / width * windowMinutes * 60000).toLong())
                                    currentEdit(mark.item, "schedule", start, end.takeIf { it > start }); delta = 0f
                                }, onDragCancel = { delta = 0f })
                            }, contentAlignment = Alignment.Center) {
                            Box(Modifier.offset { IntOffset(delta.roundToInt(), 0) }.size(9.dp).border(1.5.dp, colors.brand, CircleShape))
                        }
                    }
                }
            } }
            }
            // Feather the full-bleed edges without enclosing the timeline in a card.
            listOf(true, false).forEach { leftEdge ->
                val mask = Brush.horizontalGradient(if (leftEdge) listOf(Color.Black, Color.Transparent) else listOf(Color.Transparent, Color.Black))
                Box(Modifier.align(if (leftEdge) Alignment.CenterStart else Alignment.CenterEnd).width(24.dp).fillMaxHeight()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent { drawContent(); drawRect(mask, blendMode = BlendMode.DstIn) }
                    .drawPlainBackdrop(backdrop = track, shape = { RectangleShape }, effects = { blur(4.dp.toPx()) })
                    .background(colors.bg.copy(alpha = .55f)))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        if (currentMarks.none { it.item == selected }) NButton("安排时间", {
            precise = CalendarMark("new-$selected", drafts[selected].optString("title"),
                AcademicClock.dayStart(day) + 14 * 3600000L, null, item = selected)
        })
        NButton("回到事项", {
            val mark = currentMarks.firstOrNull { it.item == selected }
            if (mark != null) {
                onDay(AcademicClock.moment(mark.start).date)
                windowStart = (AcademicClock.moment(mark.start).minutes - windowMinutes / 2).coerceIn(0f, 1440f - windowMinutes)
            }
        })
    }
    AnimatedVisibility(precise != null, enter = expandVertically(tween(240)) + fadeIn(), exit = shrinkVertically(tween(200)) + fadeOut()) {
        precise?.let { mark -> CalendarPrecise(mark, { start, end -> onEdit(mark.item, mark.field, start, end); precise = null }, { precise = null }) }
    }
    }
}

@Composable private fun CalendarPrecise(mark: CalendarMark, save: (Long, Long?) -> Unit, close: () -> Unit) {
    var date by remember(mark.key) { mutableStateOf(AcademicClock.moment(mark.start).date) }
    var start by remember(mark.key) { mutableStateOf(AcademicClock.clockAt(mark.start)) }
    var end by remember(mark.key) { mutableStateOf(mark.end?.let(AcademicClock::clockAt).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NText(mark.title, 17, true)
        NField("日期", date, { date = it }, placeholder = "2026-11-02")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { NField(if (mark.field == "deadline") "截止" else "时间", start, { start = it }, placeholder = "14:00") }
            if (mark.field == "schedule") Box(Modifier.weight(1f)) { NField("结束（可空）", end, { end = it }, placeholder = "15:00") }
        }
        NError(error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            NButton("取消", close)
            NButton("完成", {
                val a = AcademicClock.parseTime("${date}T$start:00")
                val b = if (end.isBlank()) null else AcademicClock.parseTime("${date}T$end:00")
                if (a == null || (end.isNotBlank() && (b == null || b <= a))) error = "请填写有效日期和时间，结束需晚于开始"
                else save(a, b)
            }, primary = true)
        }
    }
}
