package com.stuassistant.app.nativeapp

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import com.kyant.shapes.RoundedRectangle
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import org.json.JSONObject
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
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                CalendarCounter("${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}", AcademicClock.dayStart(day),
                                    TextStyle(color = LocalNativeColors.current.ink, fontSize = 18.sp, fontFamily = NativeFontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), duration = 280)
                            }
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

@Composable
internal fun CalendarTimeline(day: String, onDay: (String) -> Unit, drafts: List<JSONObject>, selected: Int,
    existing: List<CalendarMark>, doc: AcademicTimetableDoc?, onEdit: (Int, String, Long, Long?) -> Unit, onSelect: (Int) -> Unit, onPrecise: (CalendarMark) -> Unit) {
    val colors = LocalNativeColors.current
    val density = LocalDensity.current
    val blockShape = remember { RoundedRectangle(16.dp) }
    var windowStart by remember { mutableFloatStateOf(8f * 60) }
    var windowMinutes by remember { mutableFloatStateOf(6f * 60) }
    var gesturing by remember { mutableStateOf(false) }
    val displayedStart by animateFloatAsState(windowStart, if (gesturing) snap() else tween(180, easing = FastOutSlowInEasing), label = "time-window")
    var keepWindowForSelection by remember { mutableStateOf<Int?>(null) }
    val currentSelected by rememberUpdatedState(selected)
    val currentMarks = calendarMarks(drafts)
    LaunchedEffect(selected) {
        if (keepWindowForSelection == selected) {
            keepWindowForSelection = null
            return@LaunchedEffect
        }
        val mark = currentMarks.firstOrNull { it.item == selected }
        if (mark != null) {
            val duration = ((mark.end ?: mark.start) - mark.start) / 60000f
            windowMinutes = (duration + 240).coerceIn(360f, 1440f)
            val minute = AcademicClock.moment(mark.start).minutes.toFloat() + duration / 2
            windowStart = (minute - windowMinutes / 2).coerceIn(0f, 1440f - windowMinutes)
        }
    }
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        NButton("‹", { onDay(AcademicClock.addDays(day, -1)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "前一天" })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CalendarCounter("${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}", AcademicClock.dayStart(day),
                TextStyle(color = colors.ink, fontSize = 18.sp, fontFamily = NativeFontFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), duration = 280)
        }
        NButton("›", { onDay(AcademicClock.addDays(day, 1)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "后一天" })
    }
    AnimatedContent(day, Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center, transitionSpec = {
        val sign = if (targetState > initialState) 1 else -1
        (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { sign * it } + fadeIn(tween(180))) togetherWith
            (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -sign * it } + fadeOut(tween(180)))
    }, label = "timeline-day-slide") { date ->
        val midnight = AcademicClock.dayStart(date)
        val courses = AcademicTimetable.planFor(doc, date).blocks.map { CalendarMark(it.key, it.course.title,
            midnight + it.startMin * 60000L, midnight + it.endMin * 60000L, source = "course") }
        val marks = (courses + existing + currentMarks).filter { it.start < midnight + 86400000 && (it.end ?: it.start) >= midnight }
            .sortedBy { if (it.item == selected) 2 else if (it.item >= 0) 1 else 0 }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 220.dp).fillMaxHeight().clipToBounds()
            .semantics { contentDescription = "日历时间线"; stateDescription = "可见分钟:${windowMinutes.roundToInt()}" }) {
            val chartScale = (maxHeight / 220.dp).coerceAtMost(1f)
            fun cy(value: Int) = (value * chartScale).dp
            val track = rememberLayerBackdrop()
            val width = constraints.maxWidth.toFloat()
            fun x(ms: Long) = ((ms - midnight) / 60000f - displayedStart) / windowMinutes * width
            Box(Modifier.fillMaxSize().calendarGestures(date, marks, chartScale,
                viewport = { windowStart to windowMinutes },
                updateViewport = { start, minutes -> windowStart = start; windowMinutes = minutes },
                active = { gesturing = it }, onEdit = onEdit,
                selectByDrag = { index -> keepWindowForSelection = index.takeIf { it != currentSelected }; onSelect(index) },
                onTap = { onSelect(it.item); onPrecise(it) },
                onCreate = { onEdit(selected, "schedule", it, null) })
                .layerBackdrop(track)) {
            Box(Modifier.fillMaxSize()) {
                val step = if (windowMinutes > 720) 360 else if (windowMinutes > 300) 120 else 60
                for (minute in 0..1440 step step) {
                    val px = (minute - displayedStart) / windowMinutes * width
                    if (px in -30f..(width + 30)) {
                        Box(Modifier.offset { IntOffset(px.roundToInt(), cy(54).roundToPx()) }.width(1.dp).height(cy(140)).background(colors.ink.copy(alpha = .14f)))
                        Box(Modifier.offset { IntOffset((px - 24.dp.toPx()).roundToInt(), cy(20).roundToPx()) }.width(48.dp), contentAlignment = Alignment.Center) {
                            BasicText(AcademicClock.clock(minute), style = TextStyle(color = colors.ink.copy(alpha = .72f), fontSize = 12.sp, fontFamily = NativeFontFamily))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().padding(top = cy(90)).height(cy(72)).background(colors.brand.copy(alpha = .055f)))
                Box(Modifier.fillMaxWidth().padding(top = cy(126)).height(1.dp).background(colors.brand.copy(alpha = .16f)))
            }
            marks.forEach { mark -> key(mark.key) {
                val left = x(mark.start)
                val right = mark.end?.let(::x) ?: left
                if (right >= -48 && left <= width + 48) {
                    val editable = mark.item >= 0
                    val tint = when { mark.field == "deadline" -> if (colors.bg.luminance() > .5f) Color(0xFFD63760) else Color(0xFFFF809D); editable -> colors.brand; mark.source == "course" -> colors.muted; else -> Color(0xFF398A79) }
                    val point = mark.end == null
                    val targetWidth = if (point) 48.dp else with(density) { (right-left).coerceAtLeast(48.dp.toPx()).toDp() }
                    val offset = if (point) left - with(density) { 24.dp.toPx() } else left
                    val modifier = Modifier.offset { IntOffset(offset.roundToInt(), cy(66).roundToPx()) }.width(targetWidth).height(cy(144))
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${mark.title} ${if (mark.field == "deadline") "截止" else "安排"} ${CalendarDrafts.iso(mark.start)}${if (editable) " 可拖动" else " 只读"}"
                            if (editable) onClick(label = "修改时间") { onSelect(mark.item); onPrecise(mark); true }
                        }
                    Box(modifier) {
                        if (point) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = cy(8)).width(9.dp).height(cy(94)).background(tint.copy(alpha = .12f)))
                            Box(Modifier.align(Alignment.TopCenter).padding(top = cy(8)).width(3.dp).height(cy(94)).background(tint))
                            Box(Modifier.align(Alignment.TopCenter).padding(top = cy(82)).size(36.dp).clip(CircleShape).background(tint.copy(alpha = .17f)), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(tint).border(2.dp, colors.bg, CircleShape))
                            }
                        } else {
                            Box(Modifier.fillMaxWidth().padding(top = cy(26)).height(cy(72)).clip(blockShape)
                                .background(tint.copy(alpha = if (editable) .94f else .20f))
                                .border(if (editable) 1.5.dp else 1.dp, tint.copy(alpha = if (editable) 1f else .32f), blockShape), contentAlignment = Alignment.Center) {
                                BasicText(mark.title, Modifier.padding(horizontal = 12.dp), style = TextStyle(color = if (editable) colors.onBrand else colors.ink, fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (editable) {
                                    Box(Modifier.align(Alignment.CenterStart).padding(start = 5.dp).width(2.dp).height(cy(20)).background(colors.onBrand.copy(alpha = .75f)))
                                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp).width(2.dp).height(cy(20)).background(colors.onBrand.copy(alpha = .75f)))
                                }
                            }
                        }
                    }
                    // A point's separate hollow tail grows a range; the main round grip always moves the point.
                    if (point && editable && mark.field == "schedule") {
                        Box(Modifier.offset { IntOffset((left + 12.dp.toPx()).roundToInt(), cy(112).roundToPx()) }.size(44.dp)
                            .semantics { contentDescription = "拖出时长" }
                            , contentAlignment = Alignment.Center) {
                            Box(Modifier.size(9.dp).border(1.5.dp, colors.brand, CircleShape))
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
    if (currentMarks.none { it.item == selected }) Box(Modifier.padding(horizontal = 24.dp)) {
        NRow("时间", trailing = "未安排  ＋", onClick = {
            onPrecise(CalendarMark("new-$selected", drafts[selected].optString("title"),
                AcademicClock.dayStart(day) + 14 * 3600000L, null, item = selected))
        })
    }
    }
}

@Composable internal fun CalendarPrecise(mark: CalendarMark, save: (Long, Long?) -> Unit) {
    var date by remember(mark.key) { mutableStateOf(AcademicClock.moment(mark.start).date) }
    var start by remember(mark.key) { mutableStateOf(AcademicClock.clockAt(mark.start)) }
    var end by remember(mark.key) { mutableStateOf(mark.end?.let(AcademicClock::clockAt).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var field by remember { mutableStateOf("start") }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NChips(listOf("date" to "日期", "start" to if (mark.field == "deadline") "截止" else "开始") +
            (if (mark.field == "schedule") listOf("end" to "结束") else emptyList()), field, { field = it })
        Box(Modifier.semantics { contentDescription = "精确时间编辑" }) {
            NField("", when(field) { "date" -> date; "end" -> end; else -> start }, {
                when(field) { "date" -> date = it; "end" -> end = it; else -> start = it }; error = null
            }, placeholder = if(field == "date") "2026-11-02" else if(field == "end") "可空" else "14:00")
        }
        NError(error)
        Spacer(Modifier.weight(1f))
            NButton("完成", {
                val a = AcademicClock.parseTime("${date}T$start:00")
                val b = if (end.isBlank()) null else AcademicClock.parseTime("${date}T$end:00")
                if (a == null || (end.isNotBlank() && (b == null || b <= a))) error = "日期或时间无效，结束需晚于开始"
                else save(a, b)
            }, primary = true, modifier = Modifier.fillMaxWidth())
    }
}
