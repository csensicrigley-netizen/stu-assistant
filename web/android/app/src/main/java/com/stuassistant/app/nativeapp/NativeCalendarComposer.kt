package com.stuassistant.app.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private val CalendarRevealEase = CubicBezierEasing(.12f, .82f, .18f, 1f)

/** Feathered circular reveal in this layer's coordinate system, seeded at Submit. */
private fun Modifier.calendarReveal(progress: Float, origin: Offset) = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    if (progress > 0f) {
        drawContent()
        if (progress < .9999f) {
            val extent = hypot(max(origin.x, size.width-origin.x), max(origin.y, size.height-origin.y)) / .82f
            drawRect(Brush.radialGradient(0f to Color.Black, .82f to Color.Black, 1f to Color.Transparent,
                center = origin, radius = max(1f, extent * progress)), blendMode = BlendMode.DstIn)
        }
    }
}

/** Only an explicit submission calls Jev. Keyboard, header and review share one stable anchor. */
@Composable
fun NativeCalendarComposer(store: NativeStore, s: NativeState, close: () -> Unit) {
    val c = LocalNativeColors.current
    val backdrop = LocalNativeBackdrop.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var text by rememberSaveable { mutableStateOf("") }
    var phase by rememberSaveable { mutableStateOf("input") }
    var packed by rememberSaveable { mutableStateOf("[]") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableStateOf(AcademicClock.moment().date) }
    var reviewNeeded by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var manualMenu by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var bodyPosition by remember { mutableStateOf(Offset.Zero) }
    val account = remember { runCatching { store.calendarAccount() }.getOrNull() }
    if (account == null) { LaunchedEffect(Unit) { close() }; return }
    val batch = rememberSaveable { UUID.randomUUID().toString() }
    val drafts = remember(packed) { academicObjects(JSONArray(packed)) }
    val reveal by animateFloatAsState(if (phase == "input") 0f else 1f,
        tween(if (phase == "input") 360 else 900, easing = CalendarRevealEase), label = "calendar-radial-reveal")
    LaunchedEffect(phase) {
        if (phase == "input") { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(Unit) {
        try { records = store.calendarItems() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "本地事项读取失败，请重新打开" }
        if (phase == "loading" && job == null) { phase = "input"; error = "上次识别已中断，可手动重试" }
    }
    LaunchedEffect(s.server, s.user?.optString("id")) { if (runCatching { store.calendarAccount() }.getOrNull() != account) close() }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun back() {
        if (saving) return
        when { options -> { options = false; keyboard?.hide() }
            phase != "input" -> { job?.cancel(); phase = "input"; error = null }
            else -> { keyboard?.hide(); close() } }
    }
    BackHandler { back() }
    fun change(index: Int, value: JSONObject) {
        packed = JSONArray(drafts.mapIndexed { i, old -> if (i == index) value else old }).toString()
    }
    fun submit(manual: Boolean) {
        if (text.isBlank() || saving || phase == "loading") return
        keyboard?.hide(); manualMenu = false; options = false; error = null; reviewNeeded = false; packed = "[]"; phase = "loading"
        job = scope.launch {
            try {
                val result = if (manual) listOf(JSONObject().put("title", text.trim()).put("timezone", "Asia/Shanghai").put("reminders", JSONArray()))
                else {
                    val response = store.inferCalendar(text.trim())
                    val items = academicObjects(response.optJSONArray("items"))
                    require(items.isNotEmpty())
                    reviewNeeded = items.any { (it.optJSONArray("issues")?.length() ?: 0) > 0 } || (response.optJSONArray("issues")?.length() ?: 0) > 0
                    items.map { it.getJSONObject("draft") }
                }
                packed = JSONArray(result).toString(); selected = 0
                day = CalendarDrafts.date(result.first()) ?: day
                phase = "review"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                phase = "input"
                error = if (e is NativeApiError) when {
                    e.code == "timeout" || e.status == 504 -> "识别超时，结果未知；请自行决定是否重试"
                    e.status == 401 -> "登录已过期，请重新登录"
                    e.status == 429 -> "请求过于频繁或今日用量已达上限，请稍后再试"
                    else -> "在线识别未完成，可重试或手动记录"
                } else "识别未完成，可重试或手动记录"
            }
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(phase) { detectTapGestures { if (phase == "input") back() } }) {
        Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = .12f)))
        Box(Modifier.fillMaxSize().calendarReveal(reveal, origin).background(c.bg))
        // Broad blur with vertical feathering; there is no filled input card or hard outline.
        Box(Modifier.statusBarsPadding().fillMaxWidth().height(132.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, .18f to Color.Black,
                    .72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }
            .drawPlainBackdrop(backdrop, { RectangleShape }, { blur(28.dp.toPx()) },
                onDrawSurface = { drawRect(c.bg.copy(alpha = .18f + reveal * .75f)) }))
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth().height(72.dp)
                .semantics { contentDescription = "日历输入栏" }.padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(text, { if (phase == "input") text = it.take(200) },
                    Modifier.weight(1f).height(56.dp).wrapContentHeight(Alignment.CenterVertically).focusRequester(focus)
                        .semantics { contentDescription = "记一件事输入框" },
                    enabled = phase == "input", maxLines = 2,
                    textStyle = TextStyle(color = c.ink, fontSize = 17.sp, lineHeight = 24.sp, fontFamily = NativeFontFamily),
                    cursorBrush = SolidColor(c.brand), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(false) }),
                    decorationBox = { inner -> Box { if (text.isEmpty()) NText("想做什么，写下来…", 17, muted = true); inner() } })
                if (phase == "input") Box(Modifier.size(36.dp).semantics { contentDescription = "记录方式" }
                    .clickable { manualMenu = !manualMenu }, contentAlignment = Alignment.Center) { NText("⋯", 21, muted = true) }
                else Spacer(Modifier.width(36.dp))
                // Explicit square constraints make the existing Kyant capsule a circle.
                // NButton retains the project's paired press/release haptics, including disposal.
                NButton(if (phase == "input") "↑" else "×", { if (phase == "input") submit(false) else back() },
                    enabled = !saving && (phase != "input" || text.isNotBlank()), primary = phase == "input",
                    modifier = Modifier.size(48.dp).semantics { contentDescription = if (phase == "input") "提交事项" else "关闭建议" }
                        .onGloballyPositioned { origin = it.boundsInRoot().center })
            }
            AnimatedVisibility(phase == "input" && (manualMenu || error != null), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NError(error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NButton("手动记录", { submit(true) }, enabled = text.isNotBlank()) }
                }
            }
            AnimatedVisibility(phase != "input", modifier = Modifier.weight(1f), enter = fadeIn(tween(80)), exit = fadeOut(tween(360))) {
                Column(Modifier.fillMaxSize().onGloballyPositioned { bodyPosition = it.boundsInRoot().topLeft }
                    .calendarReveal(reveal, origin - bodyPosition)) {
                    Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { NTitle("建议", 30) }
                                if (drafts.size > 1) Row(Modifier.widthIn(max = 176.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    drafts.forEachIndexed { index, draft ->
                                        Box(Modifier.size(32.dp).clip(CircleShape).background(if (index == selected) c.brand else c.brand.copy(alpha = .09f))
                                            .semantics { contentDescription = "第${index+1}条事项" }.clickable {
                                                selected = index; day = CalendarDrafts.date(draft) ?: day; options = false
                                            }, contentAlignment = Alignment.Center) {
                                            BasicText("${index+1}", style = TextStyle(color = if (selected == index) c.onBrand else c.brand, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
                                        }
                                    }
                                }
                            }
                            AnimatedContent(drafts.getOrNull(selected)?.let(CalendarDrafts::label).orEmpty(),
                                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) }, label = "suggested-time") { label ->
                                if (label.isEmpty()) Box(Modifier.height(28.dp))
                                else BasicText(label, style = TextStyle(color = c.ink, fontSize = 23.sp, fontFamily = NativeFontFamily, fontWeight = FontWeight.SemiBold))
                            }
                        }
                        AnimatedContent(phase == "loading", transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(240)) }, label = "inference-to-timeline") { loading ->
                            if (loading) CalendarPendingTimeline()
                            else if (drafts.isNotEmpty()) {
                                val existing = calendarMarks(records.filter { !it.optBoolean("done") }.map { it.getJSONObject("draft") }, "local") +
                                    s.plans.filter { !s.sample && !it.optBoolean("done") }.mapNotNull { plan ->
                                        val a = AcademicClock.parseTime("${plan.optString("date")}T${plan.optString("start")}:00")
                                        val b = AcademicClock.parseTime("${plan.optString("date")}T${plan.optString("end")}:00")
                                        a?.let { CalendarMark("legacy-${plan.optString("id")}", plan.optString("title"), it, b) }
                                    }
                                CalendarTimeline(day, { day = it }, drafts, selected, existing,
                                    AcademicTimetable.normalize(if (s.sample) null else s.timetable),
                                    { i, field, start, end -> change(i, CalendarDrafts.edit(drafts[i], field, start, end)) }, { selected = it })
                            }
                        }
                        AnimatedVisibility(phase == "review" && drafts.isNotEmpty(), enter = fadeIn(tween(280)) + expandVertically()) {
                            Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
                                NDivider()
                                NRow("事件选项", trailing = if (options) "⌃" else "⌄", onClick = { options = !options })
                                AnimatedVisibility(options, enter = expandVertically(tween(280)) + fadeIn(), exit = shrinkVertically(tween(240)) + fadeOut()) {
                                    if (drafts.isNotEmpty()) CalendarItemOptions(drafts[selected], reviewNeeded) { change(selected, it) }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(phase == "review", enter = fadeIn(tween(280)) + expandVertically(), exit = fadeOut()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            NError(error)
                            NButton(if (saving) "保存中…" else "确认", {
                                if (!saving) { saving = true; scope.launch {
                                    try { store.saveCalendar(batch, drafts, account); close() }
                                    catch (e: Exception) { error = e.message }
                                    finally { saving = false }
                                } }
                            }, enabled = !saving && drafts.all { it.optString("title").isNotBlank() }, primary = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** A travelling light on the eventual time axis, never a fabricated event block. */
@Composable private fun CalendarPendingTimeline() {
    val c = LocalNativeColors.current
    val loop = rememberInfiniteTransition(label = "calendar-thinking")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = LinearEasing)), label = "light-orbit")
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "正在在线识别" }) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(42.dp, 16.dp)) {
                repeat(3) { i -> drawCircle(c.brand.copy(alpha = .25f + .45f * ((sin((phase*6.28f-i).toDouble())+1)/2).toFloat()),
                    2.dp.toPx(), Offset(size.width * (.2f + i*.3f), size.height/2)) }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val middle = size.height * .48f
            repeat(7) { i -> val x = size.width * i / 6
                drawLine(c.ink.copy(alpha = .07f), Offset(x, 35.dp.toPx()), Offset(x, size.height-45.dp.toPx()), 1.dp.toPx()) }
            drawLine(c.brand.copy(alpha = .13f), Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
            val x = size.width * (.5f + .22f * sin(phase * 6.283f))
            drawCircle(Brush.radialGradient(listOf(c.brand.copy(alpha = .15f), Color.Transparent), Offset(x, middle), 32.dp.toPx()), 32.dp.toPx(), Offset(x,middle))
            drawCircle(c.brand.copy(alpha = .65f), 5.dp.toPx(), Offset(x, middle))
            drawCircle(c.brand.copy(alpha = .22f), 12.dp.toPx(), Offset(x, middle), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable private fun CalendarItemOptions(draft: JSONObject, review: Boolean, change: (JSONObject) -> Unit) {
    fun update(key: String, value: Any) { change(JSONObject(draft.toString()).put(key, value)) }
    val details = draft.optJSONObject("details") ?: JSONObject()
    val reminders = academicObjects(draft.optJSONArray("reminders"))
    val reminderChoice = when { reminders.isEmpty() -> "none"
        reminders.size == 1 && reminders[0].optString("kind") == "relative" && reminders[0].optInt("offset_minutes") == -15 -> "15"
        reminders.size == 1 && reminders[0].optString("kind") == "relative" && reminders[0].optInt("offset_minutes") == 0 -> "0"
        else -> "custom" }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NField("事项", draft.optString("title"), { update("title", it.take(200)) })
        if (review) NRow("识别状态", trailing = "有待确认字段")
        NField("备注", details.optString("note"), { update("details", JSONObject(details.toString()).put("note", it.take(10000))) }, multiline = true)
        NText("优先级", 14, true)
        NChips(listOf("0" to "普通", "1" to "重要", "2" to "很重要"), details.optInt("priority", 0).toString(), {
            update("details", JSONObject(details.toString()).put("priority", it.toInt()))
        })
        NRow("提醒", trailing = if (reminders.isEmpty()) "未设置" else "仅记录 · 通知未启用")
        val hasAnchor = CalendarDrafts.start(draft) != null || CalendarDrafts.deadline(draft) != null
        if (hasAnchor) NChips((if (reminderChoice == "custom") listOf("custom" to "已识别") else emptyList()) + listOf("none" to "无", "0" to "准时", "15" to "提前15分"), reminderChoice, { choice ->
            if (choice == "custom") return@NChips
            val anchor = if (draft.optJSONObject("schedule") != null) "start" else "deadline"
            update("reminders", if (choice == "none") JSONArray() else JSONArray().put(JSONObject()
                .put("kind", "relative").put("anchor", anchor).put("offset_minutes", -choice.toInt())))
        })
    }
}
