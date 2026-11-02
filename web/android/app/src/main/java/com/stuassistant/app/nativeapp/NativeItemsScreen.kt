package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun NativeCalendarItemsScreen(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    var records by remember(s.server, s.user?.optString("id")) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember(s.server, s.user?.optString("id")) { mutableStateOf<String?>(null) }
    var busyPlans by remember(s.server, s.user?.optString("id")) { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val open = LocalCalendarComposer.current
    LaunchedEffect(s.server, s.user?.optString("id"), s.calendarRevision) {
        if (!s.preview && s.token != null) try { records = store.calendarItems() }
        catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(s.token) { if (!s.preview && s.token != null) runCatching { store.loadPlans() } }
    key(s.server, s.user?.optString("id"), s.preview) {
    NativeItemsPage(s, records, error, busyPlanIds = busyPlans,
        onAdd = { if (s.preview || s.token == null) onConnect() else open() },
        onToggleRecord = { id ->
            val account = store.calendarAccount()
            scope.launch { try { store.toggleCalendar(id, account) } catch (e: Exception) { error = e.message } }
        },
        onTogglePlan = { plan ->
            val id = plan.optString("id")
            if (id !in busyPlans) {
                busyPlans = busyPlans + id
                scope.launch {
                    try { store.togglePlan(id, !plan.optBoolean("done")) }
                    catch (e: Exception) { error = e.message }
                    finally { busyPlans = busyPlans - id }
                }
            }
        },
        onToggleCourse = { store.toggleTask(it) })
    }
}

/** Each list row has two distinct targets: completion and disclosure. Completed
 * rows stay in place, so the same checkbox can undo the operation immediately.
 */
@Composable internal fun NativeItemsPage(s: NativeState, records: List<JSONObject>, error: String? = null,
    onAdd: () -> Unit, onToggleRecord: (String) -> Unit, onTogglePlan: (JSONObject) -> Unit,
    onToggleCourse: (String) -> Unit, busyPlanIds: Set<String> = emptySet()) {
    var section by rememberSaveable { mutableStateOf("items") }
    var day by rememberSaveable { mutableStateOf(s.calendarFocusDate ?: AcademicClock.moment().date) }
    val pages = rememberSaveableStateHolder()
    val reduced = LocalLiquidGlassReducedMotion.current
    LaunchedEffect(s.calendarLastBatch) { s.calendarFocusDate?.let { day = it } }
    fun parent() { section = if (section == "academic") "items" else "academic" }
    BackHandler(section != "items") { parent() }
    AnimatedContent(section, transitionSpec = {
        fun depth(value: String) = when (value) { "items" -> 0; "academic" -> 1; else -> 2 }
        val direction = if (depth(targetState) >= depth(initialState)) 1 else -1
        if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it * direction / 5 } + fadeIn(tween(220))) togetherWith
            (slideOutHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it * direction / 5 } + fadeOut(tween(180)))
    }, label = "items-page") { target ->
        pages.SaveableStateProvider(target) {
            val title = when (target) { "academic" -> "学业"; "exams" -> "考试安排"; "grades" -> "课程成绩"; else -> "事项" }
            NPage(title, actions = {
                if (target == "items") NButton("学业", { section = "academic" },
                    modifier = Modifier.semantics { contentDescription = "查看考试与成绩" })
                else NButton(if (target == "academic") "‹ 事项" else "‹ 学业", { parent() },
                    modifier = Modifier.semantics { contentDescription = if (target == "academic") "返回事项" else "返回学业" })
            }) {
                when (target) {
                    "academic" -> {
                        NRow("考试安排", if (s.exams.isEmpty()) "教务考试时间与考场" else "${s.exams.size} 场考试", onClick = { section = "exams" })
                        NDivider()
                        NRow("课程成绩", if (s.grades.isEmpty()) "各学期成绩与学分" else "${s.grades.size} 条成绩", onClick = { section = "grades" })
                    }
                    "exams" -> AcademicExams(s.exams.map(AcademicRecords::exam), AcademicClock.nowMillis())
                    "grades" -> AcademicGrades(s.grades.map(AcademicRecords::grade))
                    else -> ItemsAgenda(s, records, day, { day = it }, error, onAdd, onToggleRecord, onTogglePlan, onToggleCourse, busyPlanIds)
                }
            }
        }
    }
}

@Composable private fun ColumnScope.ItemsAgenda(s: NativeState, records: List<JSONObject>, day: String,
    onDay: (String) -> Unit, error: String?, onAdd: () -> Unit, onToggleRecord: (String) -> Unit,
    onTogglePlan: (JSONObject) -> Unit, onToggleCourse: (String) -> Unit, busyPlanIds: Set<String>) {
    NButton("＋ 记一件事", onAdd, primary = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NButton("‹", { onDay(AcademicClock.addDays(day, -1)) }, modifier = Modifier.semantics { contentDescription = "事项前一天" })
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            CalendarCounter("${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}", AcademicClock.dayStart(day),
                TextStyle(color = LocalNativeColors.current.ink, fontSize = 18.sp, fontFamily = NativeFontFamily, fontWeight = FontWeight.SemiBold), duration = 280)
        }
        NButton("›", { onDay(AcademicClock.addDays(day, 1)) }, modifier = Modifier.semantics { contentDescription = "事项后一天" })
    }
    NError(error)
    val days = rememberSaveableStateHolder()
    AnimatedContent(day, transitionSpec = {
        val direction = if (targetState > initialState) 1 else -1
        (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it * direction / 3 } + fadeIn()) togetherWith
            (slideOutHorizontally(tween(220)) { -it * direction / 3 } + fadeOut())
    }, label = "agenda-day") { date ->
        days.SaveableStateProvider(date) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val dated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == date }
            val old = s.plans.filter { it.optString("date") == date }
            if (dated.isEmpty() && old.isEmpty()) NText("这一天还空着", 16, muted = true)
            dated.forEach { record -> key("calendar-${record.optString("id")}") { CalendarRecordRow(record, onToggleRecord) } }
            old.forEach { plan -> key("plan-${plan.optString("id")}") {
                ItemsDisclosureRow("plan-${plan.optString("id")}", plan.optString("title"),
                    listOf(plan.optString("start"), plan.optString("end")).filter { it.isNotBlank() }.joinToString(" – "),
                    plan.optBoolean("done"), { onTogglePlan(plan) }, busy = plan.optString("id") in busyPlanIds) {
                    ItemsDetail("日期", plan.optString("date"))
                    if (plan.optString("note").isNotBlank()) NText(plan.optString("note"), 14)
                }
            } }
        }
        }
    }
    val undated = records.filter { CalendarDrafts.date(it.getJSONObject("draft")) == null }
    if (undated.isNotEmpty()) {
        NTitle("待安排", 21)
        Column { undated.forEach { record -> key("calendar-${record.optString("id")}") { CalendarRecordRow(record, onToggleRecord) } } }
    }
    if (s.tasks.isNotEmpty()) {
        var limit by rememberSaveable { mutableIntStateOf(12) }
        NDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { NTitle("课程动态", 21) }
            NText("未分类", 12, muted = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            s.tasks.take(limit).forEach { raw ->
                val task = AcademicRecords.task(raw)
                key("course-${task.id}") {
                    val context = LocalContext.current
                    ItemsDisclosureRow("course-${task.id}", task.title, task.course, task.id in s.done, { onToggleCourse(task.id) }) {
                        ItemsDetail("来源", AcademicRecords.source(task.source))
                        ItemsDetail("截止", itemsTaskDeadline(task))
                        if (task.schoolStatus.isNotBlank()) ItemsDetail("学校状态", task.schoolStatus)
                        val body = raw.optString("body").ifBlank { raw.optString("description") }
                        if (body.isNotBlank()) NText(android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(), 14)
                        task.url?.let { url -> NButton("打开 ${AcademicRecords.source(task.source)}", { nativeOpenLink(context, url) }) }
                    }
                }
            }
        }
        if (s.tasks.size > limit) NButton("查看更多（${s.tasks.size - limit}）", { limit += 12 }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun CalendarRecordRow(record: JSONObject, toggle: (String) -> Unit) {
    val draft = record.getJSONObject("draft")
    ItemsDisclosureRow("calendar-${record.optString("id")}", draft.optString("title"), CalendarDrafts.label(draft),
        record.optBoolean("done"), { toggle(record.optString("id")) }) {
        CalendarDrafts.date(draft)?.let { ItemsDetail("日期", it) }
        val details = draft.optJSONObject("details")
        details?.optString("note")?.takeIf { it.isNotBlank() }?.let { NText(it, 14) }
        draft.optJSONObject("deadline")?.optString("value")?.takeIf { it.isNotBlank() }?.let { ItemsDetail("截止", it.replace('T', ' ')) }
        draft.optJSONObject("plan")?.let {
            val end = runCatching { AcademicClock.addDays(it.optString("end_date_exclusive"), -1) }.getOrNull()
            ItemsDetail("计划", listOfNotNull(it.optString("start_date").takeIf(String::isNotBlank), end).distinct().joinToString(" — "))
        }
        details?.optInt("priority")?.takeIf { it > 0 }?.let { ItemsDetail("优先级", if (it == 1) "重要" else "很重要") }
    }
}

@Composable private fun ItemsDetail(label: String, value: String) {
    if (value.isNotBlank()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(48.dp)) { NText(label, 13, muted = true) }
        Box(Modifier.weight(1f)) { NText(value, 14) }
    }
}

@Composable internal fun ItemsDisclosureRow(id: String, title: String, subtitle: String, done: Boolean,
    onToggle: () -> Unit, busy: Boolean = false, detail: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val colors = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    val angle by animateFloatAsState(if (expanded) 90f else 0f, if (reduced) snap() else tween(220), label = "item-disclosure")
    val doneProgress by animateFloatAsState(if (done) 1f else 0f, if (reduced) snap() else tween(180), label = "item-completed")
    Column(Modifier.fillMaxWidth().animateContentSize(if (reduced) snap() else tween(240)).clip(RoundedRectangle(16.dp)).background(if (expanded) colors.brand.copy(alpha = .045f) else Color.Transparent)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).toggleable(done, enabled = !busy, role = Role.Checkbox, onValueChange = { tick(); onToggle() })
                .semantics { contentDescription = "${if (done) "取消完成" else "标记完成"}：$title"; stateDescription = if (busy) "正在保存" else if (done) "已完成" else "未完成" },
                contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    drawCircle(colors.brand.copy(alpha = doneProgress), radius = size.width / 2)
                    drawCircle(if (done) colors.brand else colors.muted.copy(alpha = .55f), radius = size.width / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    if (doneProgress > 0f) drawPath(Path().apply { moveTo(size.width*.25f,size.height*.50f);lineTo(size.width*.43f,size.height*.68f);lineTo(size.width*.76f,size.height*.32f) },
                        colors.onBrand.copy(alpha = doneProgress), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button) { expanded = !expanded; tick() }
                .semantics { contentDescription = "${if (expanded) "收起" else "展开"}详情：$title"; stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(vertical = 12.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(title, style = TextStyle(color = if (done) colors.muted else colors.ink, fontSize = 16.sp, lineHeight = 23.sp,
                        fontFamily = NativeFontFamily, fontWeight = FontWeight.SemiBold, textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None),
                        maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) NText(subtitle, 13, muted = true)
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    BasicText("›", Modifier.rotate(angle), style = TextStyle(color = colors.muted, fontSize = 25.sp))
                }
            }
        }
        AnimatedVisibility(expanded, enter = if (reduced) EnterTransition.None else expandVertically(tween(260), clip = false) + fadeIn(tween(220)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(220), clip = false) + fadeOut(tween(160))) {
            Column(Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = detail)
        }
    }
}
