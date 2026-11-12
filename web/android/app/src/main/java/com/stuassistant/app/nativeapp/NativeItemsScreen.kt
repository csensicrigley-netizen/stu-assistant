package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.delay
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
        if (!s.preview && s.token != null) try {
            records = store.calendarItems()
        } catch (e: Exception) {
            error = e.message
        }
    }
    LaunchedEffect(s.token) { if (!s.preview && s.token != null) runCatching { store.loadPlans() } }
    key(s.server, s.user?.optString("id"), s.preview) {
        NativeItemsPage(
            s = s,
            records = records,
            error = error,
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
            onToggleCourse = { store.toggleTask(it) },
            busyPlanIds = busyPlans,
            onDeleteRecord = { id ->
                val account = store.calendarAccount()
                scope.launch { try { store.removeCalendar(id, account) } catch (e: Exception) { error = e.message } }
            },
            onDeletePlan = { plan ->
                val id = plan.optString("id")
                if (id.isNotBlank() && id !in busyPlans) {
                    busyPlans = busyPlans + id
                    scope.launch {
                        try { store.removePlan(id) }
                        catch (e: Exception) { error = e.message }
                        finally { busyPlans = busyPlans - id }
                    }
                }
            },
        )
    }
}

/**
 * Each list row keeps completion, disclosure, and personal-item deletion cleanly separated.
 * Completed rows stay in place so the checkbox can undo immediately, while personal items
 * also support explicit two-step deletion without conflicting with horizontal day swipes.
 */
@Composable
internal fun NativeItemsPage(
    s: NativeState,
    records: List<JSONObject>,
    error: String? = null,
    onAdd: () -> Unit,
    onToggleRecord: (String) -> Unit,
    onTogglePlan: (JSONObject) -> Unit,
    onToggleCourse: (String) -> Unit,
    busyPlanIds: Set<String> = emptySet(),
    onDeleteRecord: (String) -> Unit = {},
    onDeletePlan: (JSONObject) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf("items") }
    var day by rememberSaveable { mutableStateOf(s.calendarFocusDate ?: AcademicClock.moment().date) }
    val pages = rememberSaveableStateHolder()
    LaunchedEffect(s.calendarLastBatch) { s.calendarFocusDate?.let { day = it } }
    fun parent() { section = if (section == "academic") "items" else "academic" }
    BackHandler(section != "items") { parent() }

    fun sectionDepth(value: String): Int = when (value) {
        "items" -> 0
        "academic" -> 1
        else -> 2
    }

    NativeDirectionalContent(
        targetState = section,
        orderOf = ::sectionDepth,
        label = "items-page",
    ) { target ->
        pages.SaveableStateProvider(target) {
            val title = when (target) {
                "academic" -> "学业"
                "exams" -> "考试安排"
                "grades" -> "课程成绩"
                else -> "事项"
            }
            NPage(
                title = title,
                eyebrow = if (target == "items") "日程与待办" else "教务学业",
                actions = {
                    if (target == "items") {
                        NButton(
                            "学业",
                            { section = "academic" },
                            haptic = NativeButtonHaptic.Navigation,
                            modifier = Modifier.semantics { contentDescription = "查看考试与成绩" },
                        )
                    } else {
                        NButton(
                            if (target == "academic") "‹ 事项" else "‹ 学业",
                            { parent() },
                            haptic = NativeButtonHaptic.Navigation,
                            modifier = Modifier.semantics {
                                contentDescription = if (target == "academic") "返回事项" else "返回学业"
                            },
                        )
                    }
                },
            ) {
                when (target) {
                    "academic" -> NCard {
                        NRow(
                            title = "考试安排",
                            subtitle = if (s.exams.isEmpty()) "教务考试时间与考场" else "${s.exams.size} 场考试",
                            leadingIcon = "calendar",
                            onClick = { section = "exams" },
                        )
                        NDivider()
                        NRow(
                            title = "课程成绩",
                            subtitle = if (s.grades.isEmpty()) "各学期成绩与学分" else "${s.grades.size} 条成绩",
                            leadingIcon = "study",
                            onClick = { section = "grades" },
                        )
                    }
                    "exams" -> AcademicExams(s.exams.map(AcademicRecords::exam), AcademicClock.nowMillis())
                    "grades" -> AcademicGrades(s.grades.map(AcademicRecords::grade))
                    else -> ItemsAgenda(
                        s = s,
                        records = records,
                        day = day,
                        onDay = { day = it },
                        error = error,
                        onAdd = onAdd,
                        onToggleRecord = onToggleRecord,
                        onTogglePlan = onTogglePlan,
                        onToggleCourse = onToggleCourse,
                        busyPlanIds = busyPlanIds,
                        onDeleteRecord = onDeleteRecord,
                        onDeletePlan = onDeletePlan,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemsComposeBanner(onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val shape = RoundedRectangle(20.dp)
    val shadowColor = c.brand.copy(alpha = if (dark) 0.28f else 0.16f)
    val fill = remember(c.brand, dark) {
        Brush.linearGradient(
            listOf(
                lerp(c.brand, Color.White, if (dark) 0.14f else 0.18f).copy(alpha = 0.88f),
                c.brand.copy(alpha = 0.84f),
            ),
            start = Offset.Zero,
            end = Offset(700f, 240f),
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape, ambientColor = shadowColor, spotColor = shadowColor)
            .nativeCardAction(onClick, haptic = NativeButtonHaptic.Submit, shape = shape)
            .background(fill)
            .border(1.dp, Color.White.copy(alpha = 0.36f), shape)
            .padding(horizontal = 16.dp, vertical = 13.dp)
            .semantics { contentDescription = "记一件事" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            NativeIcon("plus", c.onBrand, size = 18.dp, strokeWidth = 2.2f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                "记一件事",
                maxLines = 1,
                softWrap = false,
                style = nativeTextStyle(color = c.onBrand, sizeSp = 15.5f, lineHeightSp = 20f, weight = FontWeight.Bold),
            )
            BasicText(
                "写下待办、日程或截止时间，支持智能排入日历",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = nativeTextStyle(color = c.onBrand.copy(alpha = 0.82f), sizeSp = 12f, lineHeightSp = 16f),
            )
        }
        NativeIcon("chevron-right", c.onBrand.copy(alpha = 0.85f), size = 17.dp)
    }
}

@Composable
private fun ColumnScope.ItemsAgenda(
    s: NativeState,
    records: List<JSONObject>,
    day: String,
    onDay: (String) -> Unit,
    error: String?,
    onAdd: () -> Unit,
    onToggleRecord: (String) -> Unit,
    onTogglePlan: (JSONObject) -> Unit,
    onToggleCourse: (String) -> Unit,
    busyPlanIds: Set<String>,
    onDeleteRecord: (String) -> Unit,
    onDeletePlan: (JSONObject) -> Unit,
) {
    val c = LocalNativeColors.current
    val today = AcademicClock.moment().date

    ItemsComposeBanner(onAdd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ergonomicHorizontalSwipe(
                onSwipePrevious = { onDay(AcademicClock.addDays(day, -1)) },
                onSwipeNext = { onDay(AcademicClock.addDays(day, 1)) },
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NativeStepSwitcherBar(
            title = "${AcademicClock.monthDay(day)} · ${AcademicClock.weekdayText(AcademicClock.weekday(day))}",
            order = AcademicClock.dayStart(day),
            previousLabel = "事项前一天",
            nextLabel = "事项后一天",
            onPrevious = { onDay(AcademicClock.addDays(day, -1)) },
            onNext = { onDay(AcademicClock.addDays(day, 1)) },
            idleSubtitle = "今天 · 左右轻滑切换日期",
            resetLabel = if (day != today) "回到今天" else null,
            onReset = if (day != today) ({ onDay(today) }) else null,
        )

        NError(error)

        val days = rememberSaveableStateHolder()
        NativeDirectionalContent(
            targetState = day,
            orderOf = { it },
            label = "agenda-day",
        ) { date ->
            days.SaveableStateProvider(date) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val dated = records.filter { it.optJSONObject("draft")?.let(CalendarDrafts::date) == date }
                    val old = s.plans.filter { it.optString("date") == date }
                    if (dated.isEmpty() && old.isEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .nativeGlassCard(20)
                                .padding(horizontal = 18.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            NIconBadge("spark", c.brand, sizeDp = 40, iconDp = 20)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                NText("这一天还空着", 15, bold = true)
                                NText("点上方「记一件事」写下安排，或左右轻滑看看前后几天。", 13, muted = true)
                            }
                        }
                    }
                    dated.forEach { record ->
                        val id = record.optString("id")
                        key("calendar-$id") {
                            CalendarRecordRow(record, onToggleRecord, onDelete = { onDeleteRecord(id) })
                        }
                    }
                    old.forEach { plan ->
                        val id = plan.optString("id")
                        key("plan-$id") {
                            ItemsDisclosureRow(
                                id = "plan-$id",
                                title = plan.optString("title"),
                                subtitle = listOf(plan.optString("start"), plan.optString("end")).filter { it.isNotBlank() }.joinToString(" – "),
                                done = plan.optBoolean("done"),
                                onToggle = { onTogglePlan(plan) },
                                busy = id in busyPlanIds,
                                onDelete = { onDeletePlan(plan) },
                            ) {
                                ItemsDetail("日期", plan.optString("date"))
                                if (plan.optString("note").isNotBlank()) NText(plan.optString("note"), 14)
                            }
                        }
                    }
                }
            }
        }
    }

    val undated = records.filter { it.optJSONObject("draft")?.let(CalendarDrafts::date) == null }
    if (undated.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(3.5.dp).height(16.dp).clip(Capsule()).background(c.brand.copy(alpha = 0.8f)))
            Box(Modifier.weight(1f)) { NTitle("待安排", 19) }
            NTag("${undated.size} 项", c.brand)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            undated.forEach { record ->
                val id = record.optString("id")
                key("calendar-$id") {
                    CalendarRecordRow(record, onToggleRecord, onDelete = { onDeleteRecord(id) })
                }
            }
        }
    }

    if (s.tasks.isNotEmpty()) {
        var limit by rememberSaveable { mutableIntStateOf(12) }
        NDivider()
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.width(3.5.dp).height(16.dp).clip(Capsule()).background(c.brand.copy(alpha = 0.8f)))
            Box(Modifier.weight(1f)) { NTitle("课程动态", 19) }
            NTag("未分类", c.muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            s.tasks.take(limit).forEach { raw ->
                val task = AcademicRecords.task(raw)
                key("course-${task.id}") {
                    val context = LocalContext.current
                    ItemsDisclosureRow("course-${task.id}", task.title, task.course, task.id in s.done, { onToggleCourse(task.id) }) {
                        ItemsDetail("来源", AcademicRecords.source(task.source))
                        ItemsDetail("截止", itemsTaskDeadline(task))
                        if (task.schoolStatus.isNotBlank()) ItemsDetail("学校状态", task.schoolStatus)
                        val body = raw.optString("body").ifBlank { raw.optString("description") }
                        if (body.isNotBlank()) {
                            NText(
                                android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim(),
                                14,
                            )
                        }
                        task.url?.let { url ->
                            NButton("打开 ${AcademicRecords.source(task.source)}", { nativeOpenLink(context, url) }, haptic = NativeButtonHaptic.Navigation)
                        }
                    }
                }
            }
        }
        if (s.tasks.size > limit) {
            NButton("查看更多（${s.tasks.size - limit}）", { limit += 12 }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CalendarRecordRow(
    record: JSONObject,
    toggle: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val draft = record.optJSONObject("draft") ?: return
    ItemsDisclosureRow(
        id = "calendar-${record.optString("id")}",
        title = draft.optString("title"),
        subtitle = CalendarDrafts.label(draft),
        done = record.optBoolean("done"),
        onToggle = { toggle(record.optString("id")) },
        onDelete = onDelete,
    ) {
        CalendarDrafts.date(draft)?.let { ItemsDetail("日期", it) }
        val details = draft.optJSONObject("details")
        details?.optString("note")?.takeIf { it.isNotBlank() }?.let { NText(it, 14) }
        draft.optJSONObject("deadline")?.optString("value")?.takeIf { it.isNotBlank() }?.let {
            ItemsDetail("截止", it.replace('T', ' '))
        }
        draft.optJSONObject("plan")?.let {
            val end = runCatching { AcademicClock.addDays(it.optString("end_date_exclusive"), -1) }.getOrNull()
            ItemsDetail("计划", listOfNotNull(it.optString("start_date").takeIf(String::isNotBlank), end).distinct().joinToString(" — "))
        }
        details?.optInt("priority")?.takeIf { it > 0 }?.let {
            ItemsDetail("优先级", if (it == 1) "重要" else "很重要")
        }
    }
}

@Composable
private fun ItemsDetail(label: String, value: String) {
    if (value.isNotBlank()) Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(56.dp)) { NText(label, 13, muted = true, maxLines = 1, softWrap = false) }
        Box(Modifier.weight(1f)) { NText(value, 14, tabularNums = true) }
    }
}

@Composable
internal fun ItemsDisclosureRow(
    id: String,
    title: String,
    subtitle: String,
    done: Boolean,
    onToggle: () -> Unit,
    busy: Boolean = false,
    onDelete: (() -> Unit)? = null,
    detail: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(id) { mutableStateOf(false) }
    val colors = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val tick = nSelectionFeedback()
    val destructiveHaptic = nHapticFeedback(NativeButtonHaptic.Destructive.release)

    LaunchedEffect(confirmDelete) {
        if (confirmDelete) {
            delay(3200)
            confirmDelete = false
        }
    }

    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "item-disclosure",
    )
    val doneProgress by animateFloatAsState(
        targetValue = if (done) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = 540f),
        label = "item-completed",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (done) 1f else 0.92f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.56f, stiffness = 560f),
        label = "item-check-scale",
    )
    val checkPath = remember { NativeIcons.path("check") }

    val cardTint = if (expanded) {
        lerp(colors.surface, colors.brand, 0.045f)
    } else {
        colors.surface
    }

    Column(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(radiusDp = 18, tint = cardTint),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .toggleable(done, enabled = !busy, role = Role.Checkbox, onValueChange = { tick(); onToggle() })
                    .semantics {
                        contentDescription = "${if (done) "取消完成" else "标记完成"}：$title"
                        stateDescription = if (busy) "正在保存" else if (done) "已完成" else "未完成"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(
                    Modifier
                        .size(22.dp)
                        .graphicsLayer {
                            scaleX = checkScale
                            scaleY = checkScale
                        },
                ) {
                    val fillAlpha = doneProgress.coerceIn(0f, 1f)
                    if (fillAlpha > 0.01f) {
                        drawCircle(colors.brand.copy(alpha = fillAlpha), radius = size.width / 2f)
                    }
                    drawCircle(
                        color = if (done) colors.brand else colors.muted.copy(alpha = .45f),
                        radius = size.width / 2f - 1.dp.toPx(),
                        style = Stroke(1.6.dp.toPx()),
                    )
                    if (fillAlpha > 0.02f) {
                        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
                            drawPath(
                                path = checkPath,
                                color = colors.onBrand.copy(alpha = fillAlpha),
                                style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp)
                    .clickable(role = Role.Button) {
                        confirmDelete = false
                        expanded = !expanded
                        tick()
                    }
                    .semantics {
                        contentDescription = "${if (expanded) "收起" else "展开"}详情：$title"
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BasicText(
                        title,
                        style = nativeTextStyle(
                            color = if (done) colors.muted else colors.ink,
                            sizeSp = 15.5f,
                            lineHeightSp = 22f,
                            weight = FontWeight.SemiBold,
                            decoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotBlank()) {
                        NText(subtitle, 12, muted = true, maxLines = 1, tabularNums = true)
                    }
                }
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    NativeIcon(
                        name = "chevron-right",
                        color = colors.muted,
                        size = 17.dp,
                        modifier = Modifier.graphicsLayer { rotationZ = angle },
                    )
                }
            }
            if (onDelete != null) {
                ItemsInlineDeleteAction(
                    title = title,
                    confirming = confirmDelete,
                    enabled = !busy,
                    onRequestConfirm = { tick(); confirmDelete = true },
                    onConfirmDelete = {
                        confirmDelete = false
                        destructiveHaptic()
                        onDelete()
                    },
                )
            }
        }
        AnimatedVisibility(
            expanded,
            enter = if (reduced) EnterTransition.None else expandVertically(spring(dampingRatio = 0.86f, stiffness = 480f), clip = false) + fadeIn(tween(160)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(160), clip = false) + fadeOut(tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NDivider()
                detail()
                if (onDelete != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        val pillShape = Capsule()
                        Row(
                            Modifier
                                .heightIn(min = 36.dp)
                                .clip(pillShape)
                                .background(colors.danger.copy(alpha = if (confirmDelete) 0.16f else 0.09f))
                                .border(1.dp, colors.danger.copy(alpha = 0.30f), pillShape)
                                .clickable(enabled = !busy, role = Role.Button) {
                                    if (!confirmDelete) {
                                        tick()
                                        confirmDelete = true
                                    } else {
                                        confirmDelete = false
                                        destructiveHaptic()
                                        onDelete()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            NativeIcon("trash", colors.danger, size = 14.dp)
                            BasicText(
                                if (confirmDelete) "再次轻点确认删除" else "删除事项",
                                maxLines = 1,
                                softWrap = false,
                                style = nativeTextStyle(
                                    color = colors.danger,
                                    sizeSp = 12.5f,
                                    lineHeightSp = 16f,
                                    weight = FontWeight.SemiBold,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemsInlineDeleteAction(
    title: String,
    confirming: Boolean,
    enabled: Boolean,
    onRequestConfirm: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val c = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    AnimatedContent(
        targetState = confirming,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 540f), initialScale = 0.86f)) togetherWith
                (fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.88f))
        },
        label = "inline-delete-pill",
    ) { isConfirm ->
        if (isConfirm) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .height(32.dp)
                    .clip(Capsule())
                    .background(c.danger)
                    .semantics { contentDescription = "确认删除：$title" }
                    .clickable(enabled = enabled, role = Role.Button, onClick = onConfirmDelete)
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    "确认删除",
                    maxLines = 1,
                    softWrap = false,
                    style = nativeTextStyle(
                        color = Color.White,
                        sizeSp = 12f,
                        lineHeightSp = 15f,
                        weight = FontWeight.SemiBold,
                    ),
                )
            }
        } else {
            Box(
                Modifier
                    .size(42.dp)
                    .semantics { contentDescription = "删除事项：$title" }
                    .clickable(enabled = enabled, role = Role.Button, onClick = onRequestConfirm),
                contentAlignment = Alignment.Center,
            ) {
                NativeIcon("trash", c.muted.copy(alpha = if (enabled) 0.75f else 0.35f), size = 16.dp)
            }
        }
    }
}
