package com.stuassistant.app.nativeapp

import android.animation.ValueAnimator
import android.app.TimePickerDialog
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
private fun academicNow(interval: Long = 30_000): AcademicMoment {
    var now by remember { mutableStateOf(AcademicClock.moment()) }
    LaunchedEffect(interval) {
        while (true) { now = AcademicClock.moment(); delay(interval) }
    }
    return now
}

private fun academicError(error: Exception): String {
    if (error is CancellationException) throw error
    val code = (error as? NativeApiError)?.code ?: error.message.orEmpty()
    return when (code) {
        "network", "timeout" -> "服务器没有及时响应，检查网络后再试。"
        "invalid_token" -> "登录状态过期了，请重新登录。"
        "invalid_input" -> "填写的内容格式不对。"
        "plan_not_found" -> "这条计划已经不在了，刷新一下。"
        else -> (error as? NativeApiError)?.detail?.ifEmpty { null } ?: error.message ?: "没有成功，请重试。"
    }
}

@Composable
private fun AcademicSection(title: String, note: String = "", action: String? = null, onAction: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { NText(title, 18, true) }
            if (note.isNotEmpty()) NText(note, 12, muted = true)
            if (action != null && onAction != null) NButton(action, onAction, haptic = NativeButtonHaptic.Navigation)
        }
        content()
    }
}

@Composable
private fun AcademicEmpty(title: String, text: String = "", content: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = LocalNativeColors.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)) {
        Canvas(Modifier.matchParentSize()) { drawRoundRect(c.muted.copy(alpha = .5f), cornerRadius = CornerRadius(20.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))) }
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).background(c.bg), contentAlignment = Alignment.Center) { AcademicIcon(if (title.contains("考试")) "calendar" else if (title.contains("作业") || title.contains("成绩")) "study" else "today", c.muted, 22) }
            AcademicText(title, c.ink, 16, bold = true, centered = true)
            if (text.isNotEmpty()) AcademicText(text, c.muted, 14, centered = true)
            content?.invoke(this)
        }
    }
}

@Composable
private fun AcademicText(text: String, color: Color, fontSize: Int = 16, bold: Boolean = false, crossed: Boolean = false, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, centered: Boolean = false) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = fontSize.sp, lineHeight = (fontSize * (if (bold) 1.35 else 1.6)).sp,
        fontFamily = NativeFontFamily,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textDecoration = if (crossed) TextDecoration.LineThrough else TextDecoration.None), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun AcademicCheck(checked: Boolean, label: String, enabled: Boolean = true, onToggle: () -> Unit) {
    val colors = LocalNativeColors.current
    val leaf = academicLeafColor()
    val tick = nHapticFeedback("toggle")
    Box(Modifier.size(44.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { tick(); onToggle() })
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val ink = (if (checked) leaf else colors.muted).copy(alpha = if (enabled) 1f else .45f)
            drawRoundRect(if (checked) ink else Color.Transparent, cornerRadius = CornerRadius(6.dp.toPx()))
            if (!checked) drawRoundRect(ink, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            else drawPath(Path().apply { moveTo(size.width * .23f, size.height * .50f); lineTo(size.width * .43f, size.height * .70f); lineTo(size.width * .78f, size.height * .30f) }, colors.surface, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun AcademicProgress(value: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = .15f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
private fun AcademicBlockButton(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    NButton(label, onClick, enabled, primary, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Utility)
}

@Composable
private fun AcademicScoreboard(entries: List<Pair<String, String>>) {
    val c = LocalNativeColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(24.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(24.dp))) {
        entries.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.border))
            Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { NText(label, 12, muted = true); AcademicText(value, c.ink, 24, bold = true) }
        }
    }
}

/** Same 24-unit, rounded 1.75-stroke paths as components/Icon.tsx. */
@Composable
private fun AcademicIcon(name: String, color: Color, dimension: Int = 20) {
    val data = when (name) {
        "today" -> "M12 3.5v2M12 18.5v2M3.5 12h2M18.5 12h2M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Z"
        "study" -> "M4.5 6.2c2.6-1 5.1-.8 7.5.7v12.4c-2.4-1.5-4.9-1.7-7.5-.7V6.2ZM19.5 6.2c-2.6-1-5.1-.8-7.5.7v12.4c2.4-1.5 4.9-1.7 7.5-.7V6.2Z"
        "calendar" -> "M4.5 7.5A2.5 2.5 0 0 1 7 5h10a2.5 2.5 0 0 1 2.5 2.5v10A2.5 2.5 0 0 1 17 20H7a2.5 2.5 0 0 1-2.5-2.5v-10ZM4.5 9.8h15M8.5 3.5v3M15.5 3.5v3"
        "shield" -> "M12 3.5 5 6.2v5.3c0 4.3 3 7.6 7 9 4-1.4 7-4.7 7-9V6.2l-7-2.7ZM9 12l2.2 2.2L15.5 10"
        "spark" -> "M12 3.5c.6 4.3 2.9 6.6 7.2 7.2-4.3.6-6.6 2.9-7.2 7.2-.6-4.3-2.9-6.6-7.2-7.2 4.3-.6 6.6-2.9 7.2-7.2Z"
        "external" -> "M13.5 5H19v5.5M19 5l-8 8M17 14v4a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 5 18V8.5A1.5 1.5 0 0 1 6.5 7H10"
        "pin" -> "M12 21s6.5-5.6 6.5-11a6.5 6.5 0 0 0-13 0c0 5.4 6.5 11 6.5 11ZM12 12.3a2.3 2.3 0 1 0 0-4.6 2.3 2.3 0 0 0 0 4.6Z"
        else -> "M9.5 6l6 6-6 6"
    }
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(Modifier.size(dimension.dp)) { scale(size.width / 24, size.height / 24, pivot = Offset.Zero) { drawPath(path, color, style = Stroke(1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)) } }
}

private data class AcademicTones(val soft: Color, val softInk: Color, val amber: Color, val dueInk: Color)
@Composable
private fun academicTones(): AcademicTones {
    val dark = LocalNativeColors.current.bg.luminance() < .2f
    val classic = LocalNativePalette.current == "classic"
    return when {
        dark && classic -> AcademicTones(academicOklch(.30, .06, 258), academicOklch(.86, .08, 258), academicOklch(.82, .11, 80), academicOklch(.82, .10, 35))
        dark -> AcademicTones(academicOklch(.32, .045, 288), academicOklch(.88, .06, 288), academicOklch(.85, .08, 82), academicOklch(.88, .06, 22))
        classic -> AcademicTones(academicOklch(.935, .035, 258), academicOklch(.38, .15, 258), academicOklch(.48, .10, 70), academicOklch(.45, .16, 32))
        else -> AcademicTones(academicOklch(.942, .035, 288), academicOklch(.40, .13, 288), academicOklch(.50, .09, 78), academicOklch(.45, .14, 22))
    }
}

@Composable
private fun AcademicCta(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val tones = academicTones()
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(20.dp)).background(tones.soft)
        .clickable(role = Role.Button, interactionSource = source, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.brand), contentAlignment = Alignment.Center) { AcademicIcon(icon, if (c.bg.luminance() < .2f) c.bg else Color.White) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) { AcademicText(title, c.ink, 15, bold = true); AcademicText(subtitle, tones.softInk, 13) }
        AcademicIcon("chevron", tones.softInk, 18)
    }
}

@Composable
private fun AcademicDuePreview(task: AcademicTask, now: Long) {
    val c = LocalNativeColors.current; val tones = academicTones()
    val soon = AcademicRecords.bucket(task, now) == "soon"
    val tone = if (soon) tones.amber else tones.dueInk
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (soon) tones.amber else c.danger))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { AcademicText(task.title, c.ink, 15, bold = true, maxLines = 2); NText("${task.course} · ${AcademicRecords.source(task.source)}", 12, muted = true) }
        AcademicText(AcademicRecords.todayDue(task, now), tone, 13, bold = true)
    }
}

@Composable
private fun AcademicNoticePreview(notice: AcademicNotice) {
    val c = LocalNativeColors.current; val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp))
        .then(notice.url?.let { url -> Modifier.clickable(role = Role.Button, interactionSource = source, indication = null) { nativeOpenLink(context, url) } } ?: Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AcademicText(notice.title, c.ink, 15, bold = true, maxLines = 2)
            NText(listOf(AcademicRecords.source(notice.source), notice.section, notice.publishedRaw).filter { it.isNotEmpty() }.joinToString(" · "), 12, muted = true)
        }
        AcademicIcon("external", c.muted, 16)
    }
}

@Composable
private fun AcademicMilestones(items: List<AcademicMilestone>) {
    val c = LocalNativeColors.current; val dueInk = academicTones().dueInk; val leaf = academicLeafColor()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AcademicText(if (item.days <= 0) "进行中" else item.days.toString(), when { item.days <= 0 -> c.brand; item.kind == "exam" -> dueInk; item.kind == "break" -> leaf; else -> c.ink }, if (item.days <= 0) 18 else 30, bold = true)
                    if (item.days > 0) NText("天后", 13, muted = true)
                }
                Spacer(Modifier.height(8.dp)); NText(item.label, 14, true)
                NText(AcademicClock.monthDay(item.from) + if (item.to != item.from) " – ${AcademicClock.monthDay(item.to)}" else "", 12, muted = true)
            }
        }
    }
}

@Composable
private fun AcademicMode(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val label = when {
        s.syncing -> "同步中"
        s.loading -> "读取中"
        s.sample -> "示例数据 ›"
        s.mode == "cached" -> s.syncedAt?.let { "离线 · ${AcademicClock.ago(it)} ↻" } ?: "未连接 ↻"
        else -> s.syncedAt?.let { "${AcademicClock.ago(it)}更新 ↻" } ?: "已更新 ↻"
    }
    Column {
        NButton(label, {
            if (s.sample) onConnect() else scope.launch { try { store.refresh("sync") } catch (e: Exception) { error = academicError(e) } }
        }, enabled = !s.loading && !s.syncing, haptic = if (s.sample) NativeButtonHaptic.Navigation else NativeButtonHaptic.Submit)
        NError(error)
    }
}

private fun academicReconnect(s: NativeState): List<String> {
    val bound = academicStrings(s.user?.optJSONArray("bound"))
    return s.sources.filter { it.optString("state") == "need_login" && it.optString("source") in bound }.map { AcademicRecords.source(it.optString("source")) }
}

/** Exact four course recipes in tokens.css: sweet/classic × light/dark. */
private data class AcademicCourseColors(val mark: Color, val soft: Color, val mid: Color, val ink: Color)
private fun academicOklch(l: Double, c: Double, hue: Int): Color = nativeOklch(l, c, hue.toDouble())
private fun academicCourseRecipe(hue: Int, dark: Boolean, classic: Boolean): AcademicCourseColors = when {
    dark && classic -> AcademicCourseColors(academicOklch(.70, .12, hue), academicOklch(.26, .04, hue), academicOklch(.42, .08, hue), academicOklch(.88, .06, hue))
    dark -> AcademicCourseColors(academicOklch(.73, .10, hue), academicOklch(.27, .035, hue), academicOklch(.44, .065, hue), academicOklch(.90, .05, hue))
    classic -> AcademicCourseColors(academicOklch(.60, .13, hue), academicOklch(.95, .032, hue), academicOklch(.87, .07, hue), academicOklch(.36, .10, hue))
    else -> AcademicCourseColors(academicOklch(.63, .11, hue), academicOklch(.962, .03, hue), academicOklch(.885, .06, hue), academicOklch(.38, .09, hue))
}
@Composable
private fun academicCourseColors(hue: Int) = academicCourseRecipe(hue, LocalNativeColors.current.bg.luminance() < .2f, LocalNativePalette.current == "classic")

@Composable
internal fun academicCourseColor(hue: Int): Color = academicCourseColors(hue).mark

@Composable
private fun academicLeafColor(): Color {
    val dark = LocalNativeColors.current.bg.luminance() < .2f
    return if (LocalNativePalette.current == "classic") academicOklch(if (dark) .76 else .48, if (dark) .12 else .11, 155)
    else academicOklch(if (dark) .80 else .49, if (dark) .08 else .09, 168)
}

@Composable
private fun AcademicRibbon(blocks: List<AcademicBlock>, nowMinute: Int? = null, compact: Boolean = false, labelled: Boolean = !compact, scaleOnly: Boolean = false) {
    val colors = LocalNativeColors.current
    val dark = colors.bg.luminance() < .2f
    val classic = LocalNativePalette.current == "classic"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (!scaleOnly) Canvas(Modifier.fillMaxWidth().height(if (compact) 16.dp else 24.dp)) {
            val span = (AcademicTimetable.gridEnd - AcademicTimetable.gridStart).toFloat()
            fun x(minute: Int) = (minute - AcademicTimetable.gridStart) / span * size.width
            drawRoundRect(colors.border.copy(alpha = .55f), cornerRadius = CornerRadius(5.dp.toPx()))
            listOf(755 to 840, 1115 to 1160).forEach { (from, to) ->
                clipRect(x(from), 0f, x(to), size.height) {
                    var offset = x(from) - size.height
                    while (offset < x(to)) { drawLine(colors.muted.copy(alpha = .18f), Offset(offset, size.height), Offset(offset + size.height, 0f), 1.dp.toPx()); offset += 6.dp.toPx() }
                }
            }
            blocks.forEach { block ->
                val color = academicCourseRecipe(block.course.hue, dark, classic).mark
                val width = (x(block.endMin) - x(block.startMin)).coerceAtLeast(1f)
                drawRoundRect(color.copy(alpha = if (nowMinute != null && block.endMin <= nowMinute) .42f else .85f), Offset(x(block.startMin), 1.dp.toPx()), Size(width, size.height - 2.dp.toPx()), CornerRadius(4.dp.toPx()))
                if (block.conflict) drawRoundRect(colors.danger, Offset(x(block.startMin), 1.dp.toPx()), Size(width, size.height - 2.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
            if (nowMinute != null && nowMinute in AcademicTimetable.gridStart..AcademicTimetable.gridEnd) drawLine(colors.danger, Offset(x(nowMinute), 0f), Offset(x(nowMinute), size.height), 2.dp.toPx())
        }
        if (labelled) BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp)) {
            listOf(8, 10, 12, 14, 16, 18, 20, 22).forEach { hour ->
                val fraction = ((hour * 60).coerceAtMost(AcademicTimetable.gridEnd) - AcademicTimetable.gridStart).toFloat() / (AcademicTimetable.gridEnd - AcademicTimetable.gridStart)
                Box(Modifier.offset(x = ((maxWidth.value - 14) * fraction).dp)) { NText("$hour", 11, muted = true) }
            }
        }
    }
}

@Composable
fun NativeTodayScreen(store: NativeStore, s: NativeState, onNavigate: (String) -> Unit, onConnect: () -> Unit) {
    val now = academicNow(20_000)
    val doc = remember(s.timetable) { AcademicTimetable.normalize(s.timetable) }
    val plan = remember(doc, now.date) { AcademicTimetable.planFor(doc, now.date) }
    val pos = AcademicCalendar.position(now.date)
    val blocks = plan.blocks
    val current = blocks.firstOrNull { now.minutes >= it.startMin && now.minutes < it.endMin }
    val next = blocks.firstOrNull { it.startMin > now.minutes }
    val later = if (current == null && next == null) AcademicTimetable.nextClass(doc, now.date, 24 * 60) else null
    val waiting = !s.sample && doc == null
    val tasks = s.tasks.map(AcademicRecords::task).filter { it.id !in s.done && AcademicRecords.bucket(it, now.ms) in listOf("overdue", "today", "soon") }.sortedBy { it.dueMs ?: 0 }.take(4)
    val notices = s.notices.map(AcademicRecords::notice).sortedByDescending { it.publishedMs ?: 0 }.take(4)
    val milestones = AcademicCalendar.milestones(now.date, 2)
    var open by remember { mutableStateOf<AcademicBlock?>(null) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    NPage(AcademicClock.monthDay(now.date), if (pos != null) "第 ${pos.week} 周 · ${pos.phase?.label.orEmpty()} · ${AcademicClock.weekdayText(now.weekday)}" else AcademicClock.weekdayText(now.weekday), actions = { AcademicMode(store, s, onConnect) }) {
        NText(when {
            waiting -> "正在准备你的课表"
            plan.status.kind == "closed" -> "${plan.status.label}，今天不上课"
            blocks.isNotEmpty() -> "${blocks.size} 节课 · 在教室 ${AcademicClock.span(AcademicTimetable.minutes(blocks))}" + AcademicTimetable.hops(blocks).takeIf { it.isNotEmpty() }?.let { " · 换 ${it.size} 次教室" }.orEmpty()
            pos != null -> "今天没有理论课"
            else -> "不在学期内"
        }, 13, muted = true)
        val reconnect = academicReconnect(s)
        if (reconnect.isNotEmpty()) NCard {
            NText("${reconnect.joinToString("、")}的登录已过期，课表和作业暂时停在上次同步。", 14)
            NButton("重新连接", onConnect, haptic = NativeButtonHaptic.Navigation)
        }
        if (waiting) NCard {
            val busy = s.syncing || s.loading
            NText(if (busy) "正在从教务读取课表" else "还没有读到课表", 14, true)
            NText(if (busy) "第一次同步通常要半分钟左右" else if (s.error != null) "这次同步没有完成" else "学校系统还没有返回课表", 18, true)
            NText(if (busy) "读完后这里会显示你现在该去的教室。可以先切到别的页面。" else "可能是学校系统在维护（夜间常见），也可能是网络问题。稍后再试一次。", 14, muted = true)
            if (busy) NLoading() else {
                NButton("再同步一次", { scope.launch { try { store.refresh("force") } catch (e: Exception) { error = academicError(e) } } }, haptic = NativeButtonHaptic.Submit)
                NText("医学院的同学：教务系统和主校区不是一套，主同步拿不到课表，走下面这条路。", 13, muted = true)
                NButton("连医学院教务", { onNavigate("medical") }, primary = true, haptic = NativeButtonHaptic.Navigation)
                NButton("手动导入课表", { onNavigate("import") }, haptic = NativeButtonHaptic.Navigation)
            }
        } else AcademicNowCard(current, next, later, blocks, now.minutes, plan.status, { open = it })
        NError(error)
        if (s.sample) AcademicCta("这是示例课表", "连接校园账号后，换成你自己的课表、作业和成绩", "shield", onConnect)
        if (blocks.isNotEmpty()) AcademicSection("今天的时间") {
            NCard { AcademicRibbon(blocks, now.minutes) }
            AcademicAgenda(blocks, now.minutes, compact = true) { open = it }
        }
        AcademicSection("快到期", action = if (tasks.isNotEmpty()) "全部作业" else null, onAction = { onNavigate("items:school") }) {
            when {
                waiting -> NLoading()
                tasks.isEmpty() -> NText("✓ 未来 4 天没有到期的作业", 14, muted = true)
                else -> tasks.forEach { task -> AcademicDuePreview(task, now.ms) }
            }
        }
        if (milestones.isNotEmpty()) AcademicSection("校历", action = "查看校历", onAction = { onNavigate("campus:calendar") }) {
            AcademicMilestones(milestones)
        }
        if (notices.isNotEmpty()) AcademicSection("新通知", action = "全部通知", onAction = { onNavigate("campus:notices") }) {
            notices.forEach { notice -> AcademicNoticePreview(notice) }
        }
    }
    open?.let { AcademicCourseSheet(it, pos?.week ?: 0, now.weekday, now.minutes) { open = null } }
}

@Composable
private fun AcademicNowCard(current: AcademicBlock?, upcoming: AcademicBlock?, later: AcademicNextClass?, blocks: List<AcademicBlock>, minute: Int, status: AcademicDayStatus, onOpen: (AcademicBlock) -> Unit) {
    val colors = LocalNativeColors.current
    val interaction = remember { MutableInteractionSource() }
    if (status.kind == "closed") { NCard { NText("▣ 停课", 13, true); NText(status.label, 20, true); NText("按教务处校历，今天没有课。", 14, muted = true) }; return }
    val block = current ?: upcoming
    if (block == null) {
        NCard {
            NText(if (blocks.isNotEmpty()) "✓ 今天的课上完了" else "✧ 今天没有课", 14, true)
            if (later != null) {
                NText("${if (later.offset == 1) "明天" else "${AcademicClock.monthDay(later.date)} ${AcademicClock.weekdayText(later.plan.weekday)}"} ${later.block.start} ${later.block.course.title}", 18, true)
                NText("⌖ ${later.block.location.ifEmpty { "地点未标注" }}${if (later.offset == 1 && later.block.startMin <= 480) " · 早八" else ""}", 14, muted = true)
            } else NText("接下来两周没有排课", 18, true)
        }
        return
    }
    val recipe = academicCourseColors(block.course.hue)
    val tint = recipe.soft
    val mark = recipe.mark
    val previous = blocks.getOrNull(blocks.indexOf(block) - 1)
    val moved = previous != null && previous.location.isNotEmpty() && block.location.isNotEmpty() && previous.location != block.location && block.startMin - previous.endMin <= 30
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.radialGradient(listOf(tint, colors.surface), center = Offset(1000f, 0f), radius = 1100f)).border(1.dp, mark.copy(alpha = .25f), RoundedCornerShape(28.dp)).clickable(role = Role.Button, interactionSource = interaction, indication = null) { onOpen(block) }) {
        AcademicHeroDecoration(mark, Modifier.matchParentSize())
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NText(if (current != null) "● 正在上课 · 还剩 ${AcademicClock.span(block.endMin - minute)}" else "● 下一节 · ${if (block.startMin <= minute) "马上开始" else "还有 ${AcademicClock.span(block.startMin - minute)}"}", 13, true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                NText(block.start, 40, true); NText("–", 25, muted = true); NText(block.end, 40, true)
            }
            NText(block.course.title, 20, true)
            AcademicText("⌖ ${block.location.ifEmpty { "教务未标注地点" }}", recipe.ink, 16, bold = true)
            NText((if (block.teacher.isNotEmpty()) "${block.teacher} · " else "") + "${AcademicTimetable.periodsText(block.periods)} · ${AcademicClock.span(block.span)}", 13, muted = true)
            if (moved && current == null) NText("换教室：上一节在 ${previous!!.location}，课间 ${AcademicClock.span(block.startMin - previous.endMin)}", 13)
            if (status.kind == "makeup") NText(status.note, 13)
            if (current != null) AcademicProgress((minute - block.startMin).toFloat() / block.span, mark, Modifier.padding(top = 6.dp))
        }
    }
}

private var academicHeroSeen = false

@Composable
private fun AcademicHeroDecoration(mark: Color, modifier: Modifier) {
    val allowed = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    val kind = remember { if (!academicHeroSeen && allowed) Random.nextInt(3) else -1 }
    val opening = remember { Animatable(if (kind >= 0) 0f else 1f) }
    LaunchedEffect(kind) { if (kind >= 0) { academicHeroSeen = true; opening.animateTo(1f, tween(5000, easing = LinearEasing)) } }
    if (!allowed) return
    val sheen = rememberInfiniteTransition(label = "course sheen").animateFloat(0f, 1f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Restart), label = "sheen")
    Canvas(modifier) {
        val p = opening.value
        if (kind >= 0 && p < 1f) {
            val life = sin(p * PI).toFloat().coerceAtLeast(0f)
            when (kind) {
                0 -> {
                    val path = Path().apply { moveTo(size.width, size.height); cubicTo(size.width * .72f, size.height * .8f, size.width * .92f, size.height * .2f, size.width * .65f, 0f) }
                    drawPath(path, mark.copy(alpha = .13f * life), style = Stroke(8.dp.toPx()))
                    repeat(6) { i ->
                        val x = size.width * (.78f + .07f * sin(i.toFloat())); val y = size.height * i / 6
                        drawOval(mark.copy(alpha = .13f * life), Offset(x, y), Size(26.dp.toPx() * life, 12.dp.toPx() * life))
                    }
                }
                1 -> {
                    val x = size.width * .8f; val y = size.height * .68f
                    drawRoundRect(mark.copy(alpha = .13f * life), Offset(x - 25.dp.toPx(), y), Size(50.dp.toPx(), 37.dp.toPx()), CornerRadius(6.dp.toPx()))
                    repeat(3) { i -> val theta = p * PI * 8 + i * PI * 2 / 3; drawCircle(mark.copy(alpha = .15f * life), 5.dp.toPx(), Offset(x + cos(theta).toFloat() * 55.dp.toPx(), y + sin(theta).toFloat() * 33.dp.toPx())) }
                }
                else -> {
                    val surface = size.height * (1 - life * .65f)
                    val water = Path().apply { moveTo(0f, size.height); lineTo(0f, surface); var x = 0f; while (x <= size.width) { lineTo(x, surface + sin(x / 30 + p * 12).toFloat() * 8.dp.toPx()); x += 5.dp.toPx() }; lineTo(size.width, size.height); close() }
                    drawPath(water, mark.copy(alpha = .11f))
                    repeat(4) { i -> drawCircle(mark.copy(alpha = .13f * life), (3 + i).dp.toPx(), Offset(size.width * (.65f + i * .08f), surface + (i * 17).dp.toPx())) }
                }
            }
        } else {
            val fraction = (sheen.value / .26f).coerceAtMost(1f)
            val x = (-size.width + size.width * 3 * fraction)
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0xffb6a0ff).copy(alpha = .10f), Color(0xffffa5cd).copy(alpha = .10f), Color.Transparent), Offset(x, 0f), Offset(x + size.width * .6f, size.height)))
        }
    }
}

@Composable
private fun AcademicAgenda(blocks: List<AcademicBlock>, minute: Int, compact: Boolean = false, onOpen: (AcademicBlock) -> Unit) {
    val colors = LocalNativeColors.current
    val entries = blocks.map { Triple(it.startMin, it, null as AcademicGap?) } + AcademicTimetable.gaps(blocks).map { Triple(it.startMin, null as AcademicBlock?, it) }
    entries.sortedBy { it.first }.forEach { (_, block, gap) ->
        if (block != null) AcademicLesson(block, onOpen, if (minute >= block.endMin) "past" else if (minute >= block.startMin) "live" else "next", compact)
        else if (gap != null && compact) Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(52.dp)) { NText(gap.start, 13, muted = true) }
            Box(Modifier.weight(1f)) {
                Canvas(Modifier.width(12.dp).height(30.dp)) { drawLine(colors.muted.copy(alpha = .5f), Offset(6.dp.toPx(), 3.dp.toPx()), Offset(6.dp.toPx(), 27.dp.toPx()), 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))) }
                Box(Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp)) { NText("${gap.label} ${AcademicClock.span(gap.span)}", 13, muted = true) }
            }
        } else if (gap != null) Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Canvas(Modifier.weight(1f).height(1.dp)) { drawLine(colors.muted.copy(alpha = .4f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))) }
            NText("${gap.label} ${AcademicClock.span(gap.span)}", 13, muted = true)
            Canvas(Modifier.weight(1f).height(1.dp)) { drawLine(colors.muted.copy(alpha = .4f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))) }
        }
    }
}

@Composable
private fun AcademicLesson(block: AcademicBlock, onOpen: (AcademicBlock) -> Unit, status: String = "", compact: Boolean = false) {
    val c = LocalNativeColors.current
    val mark = academicCourseColor(block.course.hue)
    val interaction = remember { MutableInteractionSource() }
    if (compact) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, interactionSource = interaction, indication = null) { onOpen(block) }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(52.dp).padding(top = 13.dp)) { AcademicText(block.start, if (status == "past") c.muted else c.ink, 14, bold = true); NText(block.end, 12, muted = true) }
            val recipe = academicCourseColors(block.course.hue)
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (status == "live") recipe.soft else c.surface).border(1.dp, if (status == "live") recipe.mid.copy(alpha = .6f) else c.border, RoundedCornerShape(14.dp))) {
                Canvas(Modifier.matchParentSize()) { drawRect(mark.copy(alpha = if (status == "past") .35f else 1f), size = Size(4.dp.toPx(), size.height)) }
                Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    AcademicText(block.course.title, if (status == "past") c.muted else c.ink, 16, bold = true)
                    NText(block.location.ifEmpty { "地点未标注" } + if (block.teacher.isNotEmpty()) " · ${block.teacher}" else "", 13, muted = true)
                    if (block.conflict) NText("与其他课时间重叠", 12)
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, if (status == "live") mark.copy(alpha = .5f) else c.border, RoundedCornerShape(14.dp)).clickable(role = Role.Button, interactionSource = interaction, indication = null) { onOpen(block) }.padding(vertical = 12.dp, horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(3.dp)).background(mark.copy(alpha = if (status == "past") .4f else 1f)))
        Column(Modifier.width(52.dp)) { NText(block.start, 15, true); NText(block.end, 12, muted = true) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(block.course.title, 16, true)
            NText("${if (compact) "" else "⌖ "}${block.location.ifEmpty { "地点未标注" }}" + if (compact && block.teacher.isNotEmpty()) " · ${block.teacher}" else "", 13, muted = true)
            if (!compact) NText((if (block.teacher.isNotEmpty()) "${block.teacher} · " else "") + AcademicTimetable.periodsText(block.periods), 12, muted = true)
            if (block.conflict) NText("与其他课时间重叠", 12)
        }
        NText("›", 20, muted = true)
    }
}

@Composable
fun NativeWeekScreen(store: NativeStore, s: NativeState, onNavigate: (String) -> Unit, onConnect: () -> Unit) {
    val now = academicNow()
    val position = AcademicCalendar.position(now.date)
    val term = position?.term ?: AcademicCalendar.currentOrNext(now.date)
    var week by rememberSaveable(term.id) { mutableStateOf(position?.week ?: 1) }
    var day by rememberSaveable(term.id) { mutableStateOf(if (position != null) now.weekday else 1) }
    var open by remember { mutableStateOf<AcademicBlock?>(null) }
    val doc = remember(s.timetable) { AcademicTimetable.normalize(s.timetable) }
    val plans = remember(doc, term.id, week) { (1..7).map { AcademicTimetable.planFor(doc, AcademicCalendar.dateOf(term, week, it)) } }
    val selected = plans[day - 1]
    val total = plans.sumOf { AcademicTimetable.minutes(it.blocks) }
    val isCurrent = position?.week == week
    val colors = LocalNativeColors.current
    NPage("课表", term.label, actions = { AcademicMode(store, s, onConnect) }) {
        if (!s.sample && doc == null) AcademicEmpty(if (s.syncing || s.loading) "正在读取课表…" else "还没有课表", if (s.syncing || s.loading) "第一次同步通常要半分钟左右。" else "连接校园账号并同步后，这里会显示你每周的理论课。") {
            if (!s.syncing && !s.loading) { NButton("连医学院教务", { onNavigate("medical") }, primary = true, haptic = NativeButtonHaptic.Navigation); NButton("手动导入课表", { onNavigate("import") }, haptic = NativeButtonHaptic.Navigation) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NButton("‹", { week = (week - 1).coerceAtLeast(1) }, enabled = week > 1, haptic = NativeButtonHaptic.Utility)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                NText("第 $week 周${AcademicCalendar.phase(term, week)?.let { " · ${it.label}" }.orEmpty()}", 20, true)
                NText("${AcademicClock.slash(AcademicCalendar.monday(term, week))} – ${AcademicClock.slash(AcademicCalendar.dateOf(term, week, 7))}" + if (total > 0) " · ${AcademicClock.span(total)}" else "", 12, muted = true)
            }
            NButton("›", { week = (week + 1).coerceAtMost(term.totalWeeks) }, enabled = week < term.totalWeeks, haptic = NativeButtonHaptic.Utility)
        }
        if (position != null && !isCurrent) AcademicBlockButton("回到本周", { week = position.week; day = now.weekday })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(20.dp)).padding(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Spacer(Modifier.width(44.dp)); Box(Modifier.weight(1f)) { AcademicRibbon(emptyList(), labelled = true, compact = true, scaleOnly = true) }; Spacer(Modifier.width(4.dp)) }
            val max = plans.maxOfOrNull { AcademicTimetable.minutes(it.blocks) }?.coerceAtLeast(1) ?: 1
            plans.forEachIndexed { index, plan ->
                val wd = index + 1
                val on = day == wd
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) colors.brand.copy(alpha = .10f) else Color.Transparent).selectable(selected = on, role = Role.RadioButton, onClick = { if (!on) { day = wd } }).semantics { contentDescription = "${AcademicClock.weekdayText(wd)} ${AcademicClock.slash(plan.date)}，${if (plan.status.kind == "closed") "停课" else if (plan.blocks.isEmpty()) "没有课" else "${plan.blocks.size} 节，${AcademicClock.span(AcademicTimetable.minutes(plan.blocks))}"}" }.heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.width(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) { NText(listOf("一", "二", "三", "四", "五", "六", "日")[index], 14, true); NText(plan.date.takeLast(2).toInt().toString(), 12, muted = true) }
                    Box(Modifier.weight(1f)) { if (plan.status.kind == "closed") NText(plan.status.label, 12, muted = true) else AcademicRibbon(plan.blocks, if (isCurrent && wd == now.weekday) now.minutes else null, compact = true) }
                    Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(colors.border), contentAlignment = Alignment.BottomCenter) { Box(Modifier.fillMaxWidth().fillMaxHeight(AcademicTimetable.minutes(plan.blocks).toFloat() / max).background(if (on) colors.brand else colors.muted)) }
                }
            }
        }
        AcademicSection("${AcademicClock.weekdayText(day)} ${AcademicClock.slash(selected.date)}", if (selected.blocks.isNotEmpty()) "${selected.blocks.size} 节 · ${AcademicClock.span(AcademicTimetable.minutes(selected.blocks))}" else "") {
            when {
                selected.status.kind == "closed" -> AcademicEmpty("${selected.status.label} · 停课", "按教务处校历，这一天不上课。")
                selected.blocks.isEmpty() -> AcademicEmpty("这天没有理论课", "实验课和临时调课以学院通知为准。")
                else -> AcademicAgenda(selected.blocks, -1) { open = it }
            }
        }
        if (doc != null && (doc.pending.isNotEmpty() || doc.unarranged.isNotEmpty())) AcademicSection("需要留意") { NCard {
            doc.pending.forEach { NText("⚠ $it", 14) }
            if (doc.unarranged.isNotEmpty()) { NText("教务列出但没有排时间的课程：", 14, muted = true); doc.unarranged.forEach { NText(it.title, 14) } }
        } }
        AcademicSection("医学院") {
            AcademicCta("用医学院教务账号自动同步", "连一次，课表成绩考试都自己来；密码只用一次，不保存", "shield", { onNavigate("medical") })
            AcademicCta("手动导入课表", "不想连账号，或者那边密码改过：粘文字、导 CSV 或一条条加", "spark", { onNavigate("import") })
        }
        NText("只含教务系统里的理论课。节次时刻按教务处作息表：第二、四、五大节各 155 分钟，晚课 19:20 开始。", 12, muted = true)
    }
    open?.let { AcademicCourseSheet(it, position?.week ?: 0, now.weekday, now.minutes) { open = null } }
}

@Composable
private fun AcademicCourseSheet(block: AcademicBlock, week: Int, weekday: Int, minute: Int, onDismiss: () -> Unit) {
    val slots = AcademicTimetable.slots(block.course)
    val (done, total) = AcademicTimetable.progress(block.course, week, weekday, minute)
    NSheet(block.course.title, onDismiss) {
        NText("${AcademicClock.weekdayText(block.weekday)} ${block.start}–${block.end} · ${AcademicClock.span(block.span)}", 13, muted = true)
        NRow("地点", block.location.ifEmpty { "教务未标注地点" })
        NRow("教师", block.teacher.ifEmpty { block.course.teacher }.ifEmpty { "未标注" })
        if (total > 0) { NRow("本学期进度", trailing = "$done / $total 次"); AcademicProgress(done.toFloat() / total, academicCourseColor(block.course.hue)) }
        NText("每周安排", 16, true)
        slots.forEach { slot -> NCard {
            NText("${AcademicClock.weekdayText(slot.weekday)} ${AcademicTimetable.periodByIndex.getValue(slot.periods.first()).start}–${AcademicTimetable.periodByIndex.getValue(slot.periods.last()).end}", 15, true)
            NText("${AcademicTimetable.periodsText(slot.periods)} · ${AcademicTimetable.weeksText(slot.weeks)}", 14)
            NText(slot.location.ifEmpty { "地点未标注" } + if (slot.teacher.isNotEmpty()) " · ${slot.teacher}" else "", 14, muted = true)
            slot.notes.forEach { NText(it, 14) }
            slot.online.forEach { NText("线上：$it", 14) }
            if (slot.rawTimes.isNotEmpty()) NText("教务原文 ${slot.rawTimes.joinToString("；")}", 12, muted = true)
        } }
        NText("时刻按教务处《课堂作息时间表》换算。${if (slots.any { slot -> slot.periods.any { it >= 10 } }) "第 0、A–C 节的编号对应尚未用真实课表核对，" else ""}实际以任课教师通知为准。", 12, muted = true)
    }
}

@Composable
fun NativeItemsScreen(store: NativeStore, s: NativeState, onNavigate: (String) -> Unit, onConnect: () -> Unit, initialSource: String = "all") {
    val now = academicNow(60_000)
    var view by rememberSaveable { mutableStateOf("tasks") }
    var source by rememberSaveable(initialSource) { mutableStateOf(initialSource) }
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    var formOpen by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf("") }
    var end by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf("0") }
    var busy by remember { mutableStateOf(false) }
    var loadingPlans by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var deletePlan by remember { mutableStateOf<JSONObject?>(null) }
    var doneOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var sortOverrides by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var removed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected = day ?: now.date
    val scope = rememberCoroutineScope()
    val tasks = s.tasks.map(AcademicRecords::task)
    val exams = s.exams.map(AcademicRecords::exam)
    val openTasks = tasks.filter { it.id !in s.done && AcademicRecords.bucket(it, now.ms) != "expired" }
    val urgent = openTasks.count { AcademicRecords.bucket(it, now.ms) in listOf("overdue", "today") }
    val nextExam = exams.filter { it.startMs != null && it.startMs > now.ms }.minByOrNull { it.startMs!! }
    val plans = s.plans.filter { it.optString("date") == selected && it.optString("id") !in removed }.sortedWith(compareBy<JSONObject> { academicString(it.opt("start")).ifEmpty { "99:99" } }.thenBy { sortOverrides[it.optString("id")] ?: it.optInt("sort") }.thenBy { it.optLong("created_at") })
    suspend fun load(silent: Boolean = false) {
        if (!silent) loadingPlans = true
        error = null
        try { store.loadPlans() } catch (e: Exception) { error = academicError(e) } finally { loadingPlans = false }
    }
    LaunchedEffect(s.token, s.preview) { if (!s.preview) load() }
    NPage("事项", "计划 · 作业 · 考试 · 成绩", actions = { AcademicMode(store, s, onConnect) }) {
        NText("${openTasks.size} 项作业待完成${nextExam?.date?.let { " · 最近考试 ${AcademicClock.monthDay(it)}" }.orEmpty()}", 13, muted = true)
        NChips(listOf("tasks" to "待办${if (urgent > 0) " $urgent" else ""}", "exams" to "考试", "grades" to "成绩"), view, { view = it })
        when (view) {
            "exams" -> AcademicExams(exams, now.ms)
            "grades" -> AcademicGrades(s.grades.map(AcademicRecords::grade))
            else -> {
                NChips(listOf("all" to "全部", "personal" to "个人", "school" to "作业"), source, { source = it })
                if (source != "school") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (-3..3).forEach { offset ->
                                val date = AcademicClock.addDays(now.date, offset)
                                AcademicDateChoice(date, now.date, selected) { day = date }
                            }
                        }
                        NButton("今天", { day = null }, enabled = selected != now.date, haptic = NativeButtonHaptic.Utility)
                    }
                    if (s.preview) AcademicEmpty("登录后就能写计划", "计划只属于你自己的账号，换设备也还在。上面的日期条可以先看看。") { NButton("登录 / 注册", onConnect, primary = true, haptic = NativeButtonHaptic.Navigation) }
                    else {
                        val sum = s.planSummary
                        AcademicScoreboard(listOf("今天" to "today_total", "已完成" to "today_done", "还剩" to "today_open").map { (label, key) -> label to sum.optInt(key).toString() })
                        val upcoming = academicObjects(sum.optJSONArray("upcoming")).take(3)
                        if (sum.optInt("overdue") > 0 || upcoming.isNotEmpty()) NCard {
                            if (sum.optInt("overdue") > 0) NText("还有 ${sum.optInt("overdue")} 项是之前几天没做完的，翻回去补一下。", 14)
                            if (upcoming.isNotEmpty()) NText("接下来：" + upcoming.joinToString(" · ") { "${AcademicClock.monthDay(it.optString("date"))} ${it.optInt("count")} 项" }, 14, muted = true)
                        }
                        NError(error, { scope.launch { load() } })
                        AcademicSection(if (selected == now.date) "今天的安排" else "${AcademicClock.monthDay(selected)} ${AcademicClock.weekdayText(AcademicClock.weekday(selected))}", if (!loadingPlans) "${plans.size} 项" else "") {
                            when {
                                loadingPlans -> NLoading()
                                plans.isEmpty() -> AcademicEmpty(if (selected == now.date) "今天还空着" else "这一天还空着", if (selected == now.date) "把想做的事写下来，完成一件勾一件。" else "想给这天加点什么？") { NButton("加一条", { formOpen = true }, haptic = NativeButtonHaptic.Utility) }
                                else -> plans.forEachIndexed { index, plan ->
                                    val id = plan.optString("id")
                                    val done = doneOverrides[id] ?: plan.optBoolean("done")
                                    AcademicPlanRow(plan, done, id in pending, index > 0, index < plans.lastIndex,
                                        onToggle = {
                                            val next = !done
                                            doneOverrides = doneOverrides + (id to next); pending = pending + id
                                            scope.launch {
                                                try { store.togglePlan(id, next); load(true) } catch (e: Exception) { error = academicError(e) }
                                                finally { doneOverrides = doneOverrides - id; pending = pending - id }
                                            }
                                        },
                                        onDelete = { deletePlan = plan },
                                        onMove = { delta ->
                                            val ids = plans.map { it.optString("id") }.toMutableList()
                                            val target = index + delta
                                            if (target in ids.indices) {
                                                java.util.Collections.swap(ids, index, target)
                                                sortOverrides = sortOverrides + ids.withIndex().associate { it.value to it.index }
                                                scope.launch {
                                                    try { store.reorderPlans(selected, ids); load(true) } catch (e: Exception) { error = academicError(e) }
                                                    finally { sortOverrides = sortOverrides - ids.toSet() }
                                                }
                                            }
                                        })
                                }
                            }
                        }
                        if (formOpen) NCard {
                            NField("要做什么", title, { title = it.take(200) }, placeholder = "例如：复习细胞生物学", autoFocus = true)
                            NText("1–200 个字，写清楚一点，回头一眼能认出来。", 12, muted = true)
                            NField("备注（可不填）", note, { note = it.take(1000) }, multiline = true, placeholder = "第几章、要带什么、在哪做…", minHeight = 96)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) { AcademicTimeField("开始", start, { start = it }) }
                                Box(Modifier.weight(1f)) { AcademicTimeField("结束", end, { end = it }) }
                            }
                            NText("不写时间就只是一条待办；写了就要晚于开始时间。", 12, muted = true)
                            NText("优先级", 14, true)
                            NChips(listOf("0" to "普通", "1" to "重要", "2" to "紧急"), priority, { priority = it })
                            NError(formError)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f), propagateMinConstraints = true) {
                                NButton(if (busy) "保存中…" else "保存", {
                                    if (busy) return@NButton
                                    formError = when { title.isBlank() -> "标题不能为空。"; start.isEmpty() != end.isEmpty() -> "开始和结束时间要么都填，要么都空。"; start.isNotEmpty() && end <= start -> "结束时间要晚于开始时间。"; else -> null }
                                    if (formError == null) {
                                        busy = true
                                        scope.launch {
                                            try {
                                                store.createPlan(JSONObject().put("date", selected).put("title", title).put("note", note).put("start", start).put("end", end).put("priority", priority.toInt()))
                                                title = ""; note = ""; start = ""; end = ""; priority = "0"; formOpen = false
                                                // The write succeeded; a later read failure is not a rejected save.
                                                try { load(true) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                                            } catch (e: CancellationException) { throw e }
                                            catch (e: Exception) { formError = academicError(e) }
                                            finally { busy = false }
                                        }
                                    }
                                }, enabled = !busy, primary = true, haptic = NativeButtonHaptic.Submit)
                                }
                                Box(Modifier.weight(1f), propagateMinConstraints = true) { NButton("取消", { formOpen = false; formError = null }, enabled = !busy, haptic = NativeButtonHaptic.Utility) }
                            }
                        }
                        if (!formOpen) AcademicBlockButton("＋ 加一条", { formOpen = true })
                        NText("计划是你自己写的，和 MySTU 的作业分开存，同步学校数据不会动它。日期按北京时间算。" + if (selected != now.date) "现在看的是 ${AcademicClock.monthDay(selected)}，距今天 ${AcademicClock.daysUntil(selected, now.ms)} 天。" else "", 12, muted = true)
                    }
                }
                if (source != "personal") AcademicTasks(store, s, tasks, now.ms)
            }
        }
    }
    deletePlan?.let { plan -> NSheet("删除计划", { deletePlan = null }) {
        NText("删掉「${plan.optString("title")}」？删了就找不回来了。", 16)
        NError(error)
        NButton(if (busy) "删除中…" else "确认删除", {
            if (!busy) {
                busy = true; val id = plan.optString("id"); removed = removed + id
                scope.launch {
                    try { store.removePlan(id); deletePlan = null; load(true) } catch (e: Exception) { error = academicError(e) }
                    finally { removed = removed - id; busy = false }
                }
            }
        }, enabled = !busy, primary = true, haptic = NativeButtonHaptic.Destructive)
        NButton("取消", { deletePlan = null }, enabled = !busy, haptic = NativeButtonHaptic.Utility)
    } }
}

@Composable
private fun AcademicTimeField(label: String, value: String, onValue: (String) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NText(label, 14, true)
        NButton(value.ifEmpty { "选择时间" }, {
            val parts = value.split(':')
            TimePickerDialog(context, { _, hour, minute -> onValue(AcademicClock.clock(hour * 60 + minute)) }, parts.getOrNull(0)?.toIntOrNull() ?: 12, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
        }, haptic = NativeButtonHaptic.Utility)
        if (value.isNotEmpty()) NButton("清除", { onValue("") }, haptic = NativeButtonHaptic.Utility)
    }
}

@Composable
private fun AcademicDateChoice(date: String, today: String, selected: String, onSelect: () -> Unit) {
    val colors = LocalNativeColors.current
    val on = date == selected
    Column(Modifier.width(44.dp).heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
        .background(if (on) colors.brand.copy(alpha = .13f) else colors.surface)
        .selectable(on, role = Role.RadioButton, onClick = { if (!on) { onSelect() } })
        .semantics { contentDescription = "${AcademicClock.monthDay(date)} ${AcademicClock.weekdayText(AcademicClock.weekday(date))}${if (date == today) "，今天" else ""}" }
        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NText(if (date == today) "今天" else AcademicClock.weekdayText(AcademicClock.weekday(date)).removePrefix("周"), 12, muted = !on)
        NText(date.takeLast(2).toInt().toString(), 18, true)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AcademicPlanRow(plan: JSONObject, done: Boolean, busy: Boolean, canUp: Boolean, canDown: Boolean, onToggle: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    var armed by rememberSaveable(plan.optString("id")) { mutableStateOf(false) }
    val c = LocalNativeColors.current
    val longPressFeedback = nHapticFeedback("toggle")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(start = 2.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AcademicCheck(done, "${if (done) "取消完成" else "标记完成"}：${plan.optString("title")}", !busy, onToggle)
            Column(Modifier.weight(1f).combinedClickable(enabled = !busy, hapticFeedbackEnabled = false, onClick = {}, onLongClick = { if (!armed) { armed = true; longPressFeedback() } }), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AcademicText(plan.optString("title"), if (done) c.muted else c.ink, bold = true, crossed = done, maxLines = 2)
                if (plan.optString("note").isNotEmpty()) NText(plan.optString("note"), 12, muted = true)
            }
            Column(horizontalAlignment = Alignment.End) {
                val start = academicString(plan.opt("start")); val end = academicString(plan.opt("end"))
                if (start.isNotEmpty()) NText(start + if (end.isNotEmpty()) "–$end" else "", 12, true)
                if (plan.optInt("priority") > 0) AcademicText("●", if (plan.optInt("priority") == 2) c.danger else Color(0xff9b750b), 12, modifier = Modifier.semantics { contentDescription = if (plan.optInt("priority") == 2) "紧急" else "重要" })
            }
            if (!armed) NButton("⋯", { armed = true }, enabled = !busy, haptic = NativeButtonHaptic.Utility)
        }
        if (armed) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NButton("上移", { onMove(-1) }, enabled = canUp && !busy, haptic = NativeButtonHaptic.Utility)
            NButton("下移", { onMove(1) }, enabled = canDown && !busy, haptic = NativeButtonHaptic.Utility)
            NButton("删除", onDelete, enabled = !busy, haptic = NativeButtonHaptic.Utility)
            NButton("收起", { armed = false }, haptic = NativeButtonHaptic.Utility)
        }
    }
}

@Composable
private fun AcademicTasks(store: NativeStore, s: NativeState, tasks: List<AcademicTask>, now: Long) {
    var showDone by rememberSaveable { mutableStateOf(false) }
    val buckets = listOf("overdue" to "已过截止", "today" to "今天截止", "soon" to "4 天内", "later" to "更晚", "none" to "没写截止时间")
    val groups = buckets.map { (key, label) -> Triple(key, label, tasks.filter { it.id !in s.done && AcademicRecords.bucket(it, now) == key }.sortedBy { it.dueMs ?: Long.MAX_VALUE }) }.filter { it.third.isNotEmpty() }
    val finished = tasks.filter { it.id in s.done && AcademicRecords.bucket(it, now) != "expired" }
    val expired = tasks.count { it.id !in s.done && AcademicRecords.bucket(it, now) == "expired" }
    if (tasks.isEmpty() || (groups.isEmpty() && finished.isEmpty())) {
        AcademicEmpty(if (s.syncing) "正在读取 MySTU 作业…" else "还没有作业记录", "同步 MySTU 后会出现在这里。没有记录不代表学校没有布置，雨课堂作业暂时不在其中。")
        return
    }
    groups.forEach { (_, label, rows) -> AcademicSection(label, "${rows.size} 项") { rows.forEach { AcademicTaskRow(it, now, false) { store.toggleTask(it.id) } } } }
    if (finished.isNotEmpty()) {
        AcademicBlockButton("已完成 ${finished.size} 项 ${if (showDone) "⌃" else "⌄"}", { showDone = !showDone })
        if (showDone) finished.forEach { AcademicTaskRow(it, now, true) { store.toggleTask(it.id) } }
    }
    if (expired > 0) NText("另有 $expired 项两周前就已截止的作业没有列出。", 14, muted = true)
    NText("勾选只记在这台设备上，不会向 MySTU 提交任何内容。", 12, muted = true)
}

@Composable
private fun AcademicTaskRow(task: AcademicTask, now: Long, done: Boolean, toggle: () -> Unit) {
    val c = LocalNativeColors.current
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(start = 2.dp, end = if (task.url != null) 2.dp else 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AcademicCheck(done, "${if (done) "取消完成" else "标记完成"}：${task.title}", onToggle = toggle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AcademicText(task.title, if (done) c.muted else c.ink, bold = true, crossed = done, maxLines = 2)
                NText(listOf(task.course, AcademicRecords.source(task.source), task.schoolStatus.takeIf { it.isNotEmpty() }?.let { "学校状态：$it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), 12, muted = true)
            }
            Column(Modifier.width(78.dp), horizontalAlignment = Alignment.End) {
                AcademicText(AcademicRecords.dueLine(task, now), if (AcademicRecords.bucket(task, now) in listOf("today", "overdue")) c.danger else c.muted, 12)
                if (!done) AcademicRecords.leftText(task, now)?.let { NText(it, 11, muted = true) }
            }
            task.url?.let { url -> Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).clickable(role = Role.Button, interactionSource = interaction, indication = null) { nativeOpenLink(context, url) }
                .semantics { contentDescription = "在 ${AcademicRecords.source(task.source)} 打开" }, contentAlignment = Alignment.Center) { AcademicIcon("external", c.muted, 16) } }
        }
}

@Composable
private fun AcademicExams(exams: List<AcademicExam>, now: Long) {
    val c = LocalNativeColors.current; val tones = academicTones()
    val inverse = if (c.bg.luminance() < .2f) c.bg else c.surface
    val sorted = exams.sortedBy { it.startMs ?: Long.MAX_VALUE }
    val upcoming = sorted.filter { it.startMs == null || it.startMs > now - 3 * 3_600_000 }
    val past = sorted.filter { it.startMs != null && it.startMs <= now - 3 * 3_600_000 }
    var sessions by rememberSaveable { mutableStateOf(false) }
    if (exams.isEmpty()) { AcademicEmpty("还没有考试安排", "教务公布后，同步一次就会出现在这里。"); return }
    upcoming.firstOrNull { it.date != null }?.let { first ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.ink).padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { AcademicText("距离第一场考试", inverse.copy(alpha = .8f), 12, bold = true); AcademicText(first.title, inverse, 18, bold = true); AcademicText(first.timeRaw, inverse.copy(alpha = .8f), 13) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(AcademicClock.daysUntil(first.date!!, now).coerceAtLeast(0).toString(), style = TextStyle(fontFamily = NativeFontFamily, color = inverse, fontSize = 52.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold))
                AcademicText("天", inverse.copy(alpha = .85f), 14)
            }
        }
    }
    AcademicSection("考试安排", "${upcoming.size} 场") { upcoming.forEach { exam ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(14.dp)).padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(52.dp).height(56.dp).clip(RoundedCornerShape(14.dp)).background(if (exam.date != null) tones.soft else c.bg), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (exam.date != null) { AcademicText("${exam.date.substring(5, 7).toInt()}月", tones.softInk, 12); AcademicText(exam.date.takeLast(2).toInt().toString(), tones.softInk, 18, bold = true) } else NText("待定", 12, muted = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AcademicText(exam.title, c.ink, 16, bold = true)
                NText(exam.timeRaw.ifEmpty { "时间未公布" }, 13, muted = true)
                NText((if (exam.location.isNotEmpty()) "⌖ ${exam.location}" else "考场未公布") + if (exam.seat.isNotEmpty()) " · 座位 ${exam.seat}" else "", 13, muted = true)
            }
            exam.date?.let { NText(if (AcademicClock.daysUntil(it, now) == 0) "今天" else "${AcademicClock.daysUntil(it, now)} 天", 13, true) }
        }
    } }
    if (past.isNotEmpty()) NText("已结束 ${past.size} 场", 14, muted = true)
    AcademicBlockButton("学校统一考试场次 ${if (sessions) "⌃" else "⌄"}", { sessions = !sessions })
    if (sessions) NCard {
        listOf("08:00–10:00", "10:30–12:30", "13:30–15:30", "16:00–18:00", "19:00–21:30").forEachIndexed { i, time -> NRow("第${listOf("一", "二", "三", "四", "五")[i]}场", trailing = time) }
        NText("来源：2026–2027 学年校历所附考试时间表。具体科目以教务通知为准。", 12, muted = true)
    }
    NText("考试时间按教务原文显示，倒计时只按日期估算。", 12, muted = true)
}

@Composable
private fun AcademicGrades(grades: List<AcademicGrade>) {
    var semester by rememberSaveable { mutableStateOf("all") }
    val semesters = grades.map { it.semester }.filter { it.isNotEmpty() }.distinct().sortedDescending()
    val rows = if (semester == "all") grades else grades.filter { it.semester == semester }
    val sum = AcademicRecords.summarize(rows)
    if (grades.isEmpty()) { AcademicEmpty("还没有成绩记录", "同步教务后会出现在这里。"); return }
    AcademicScrollableChoices(listOf("all" to "全部学期") + semesters.map { it to AcademicRecords.semesterLabel(it) }, semester, { semester = it })
    AcademicScoreboard(listOf("加权均分" to sum.weightedScore?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty().ifEmpty { "—" }, "加权绩点" to sum.weightedGpa?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty().ifEmpty { "—" }, "总学分" to AcademicRecords.creditText(sum.credits)))
    NText("按学分加权，等级制成绩不计入均分，补考和重修逐条计入。均分和绩点按 ${AcademicRecords.creditText(sum.countedCredits)} 学分加权，共 ${sum.total} 条、${sum.counted} 条计入。这是估算，不是学校认定的绩点。", 12, muted = true)
    rows.forEach { grade -> AcademicGradeRow(grade) }
}

@Composable
private fun AcademicGradeRow(grade: AcademicGrade) {
    val colors = LocalNativeColors.current
    val score = grade.score.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val leaf = academicLeafColor()
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NText(grade.title, 16, true)
            NText(AcademicRecords.semesterLabel(grade.semester) + (grade.credits?.let { " · ${academicExactNumber(it)} 学分" } ?: "") + (grade.gpa?.let { " · 绩点 ${academicExactNumber(it)}" } ?: "") + if (grade.examType.isNotEmpty() && grade.examType != "正常考试") " · ${grade.examType}" else "", 12, muted = true)
        }
        AcademicText(grade.score.ifEmpty { "—" }, when { score == null -> colors.muted; score < 60 -> colors.danger; score >= 85 -> leaf; else -> colors.ink }, if (score == null) 16 else 20, bold = true)
    }
}

private fun academicExactNumber(value: Double): String = if (value % 1 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun AcademicScrollableChoices(options: List<Pair<String, String>>, current: String, onSelect: (String) -> Unit) {
    val colors = LocalNativeColors.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
                .background(if (key == current) colors.brand.copy(alpha = .13f) else colors.surface)
                .selectable(key == current, role = Role.RadioButton, onClick = { if (key != current) { onSelect(key) } })
                .padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) { NText(label, 14, key == current) }
        }
    }
}
