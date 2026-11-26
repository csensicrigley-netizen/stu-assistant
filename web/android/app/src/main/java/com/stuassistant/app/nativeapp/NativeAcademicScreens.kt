package com.stuassistant.app.nativeapp

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import com.stuassistant.app.glass.liquidGlassThemeProgress
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
private fun academicNow(interval: Long = 30_000): AcademicMoment {
    var now by remember { mutableStateOf(AcademicClock.moment()) }
    LaunchedEffect(interval) {
        while (true) {
            now = AcademicClock.moment()
            delay(interval)
        }
    }
    return now
}

private fun academicError(error: Exception): String {
    if (error is CancellationException) throw error
    return formatLocalError(error)
}

@Composable
private fun AcademicSection(
    title: String,
    note: String = "",
    action: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalNativeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.5.dp).height(16.dp).clip(Capsule()).background(c.brand.copy(alpha = 0.8f)))
            Box(Modifier.weight(1f)) { NText(title, 17, bold = true, maxLines = 1, softWrap = false) }
            if (note.isNotEmpty()) NText(note, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
            if (action != null && onAction != null) {
                val source = remember { MutableInteractionSource() }
                val release = NativePressFeedback(source, haptic = NativeButtonHaptic.Navigation)
                Row(
                    Modifier
                        .clip(Capsule())
                        .background(c.brand.copy(alpha = 0.10f))
                        .border(0.75.dp, c.brand.copy(alpha = 0.20f), Capsule())
                        .clickable(role = Role.Button, interactionSource = source, indication = null) {
                            release()
                            onAction()
                        }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    AcademicText(action, c.brand, 12, bold = true, maxLines = 1, softWrap = false)
                    NativeIcon("chevron-right", c.brand, size = 13.dp, strokeWidth = 2.0f)
                }
            }
        }
        content()
    }
}

@Composable
private fun AcademicEmpty(
    title: String,
    text: String = "",
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LocalNativeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(22)
            .padding(horizontal = 24.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val iconName = when {
            title.contains("考试") -> "calendar"
            title.contains("作业") || title.contains("成绩") -> "study"
            else -> "today"
        }
        NIconBadge(icon = iconName, tint = c.brand, sizeDp = 46, iconDp = 22)
        AcademicText(title, c.ink, 16, bold = true, centered = true)
        if (text.isNotEmpty()) AcademicText(text, c.muted, 13, centered = true)
        content?.invoke(this)
    }
}

@Composable
private fun AcademicText(
    text: String,
    color: Color,
    fontSize: Int = 15,
    bold: Boolean = false,
    crossed: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    softWrap: Boolean = true,
    tabularNums: Boolean = false,
    centered: Boolean = false,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = nativeTextStyle(
            color = color,
            sizeSp = fontSize.toFloat(),
            lineHeightSp = fontSize * if (bold) 1.34f else 1.52f,
            weight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            align = if (centered) TextAlign.Center else TextAlign.Start,
            decoration = if (crossed) TextDecoration.LineThrough else TextDecoration.None,
            tabularNums = tabularNums,
        ),
        maxLines = maxLines,
        softWrap = softWrap,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AcademicProgress(value: Float, color: Color, modifier: Modifier = Modifier) {
    val safeFraction = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(Capsule())
            .background(color.copy(alpha = .16f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(safeFraction)
                .clip(Capsule())
                .background(color),
        )
    }
}

@Composable
private fun AcademicBlockButton(label: String, onClick: () -> Unit, enabled: Boolean = true, primary: Boolean = false) {
    NButton(label, onClick, enabled, primary, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Utility)
}

@Composable
private fun AcademicScoreboard(entries: List<Pair<String, String>>) {
    val c = LocalNativeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .nativeGlassCard(22),
    ) {
        entries.forEachIndexed { index, (label, value) ->
            if (index > 0) Box(Modifier.width(0.75.dp).fillMaxHeight().padding(vertical = 14.dp).background(c.border))
            Column(
                Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NText(label, 12, muted = true, maxLines = 1, softWrap = false)
                AcademicText(value, c.ink, 22, bold = true, maxLines = 1, softWrap = false, tabularNums = true, centered = true)
            }
        }
    }
}

@Composable
private fun AcademicIcon(name: String, color: Color, dimension: Int = 20) {
    NativeIcon(name = name, color = color, size = dimension.dp, strokeWidth = 1.85f)
}

private data class AcademicTones(val soft: Color, val softInk: Color, val amber: Color, val dueInk: Color)

@Composable
private fun academicTones(): AcademicTones {
    val sem = currentNativeSemanticTones()
    return AcademicTones(sem.soft, sem.softInk, sem.amber, sem.dangerInk)
}

@Composable
private fun AcademicCta(title: String, subtitle: String, icon: String, onClick: () -> Unit) {
    val c = LocalNativeColors.current
    val tones = academicTones()
    val cardShape = RoundedRectangle(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .nativeCardAction(onClick, shape = cardShape)
            .nativeGlassCard(20, tint = tones.soft, elevated = false)
            .border(1.dp, c.brand.copy(alpha = 0.20f), cardShape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        NIconBadge(icon = icon, tint = c.brand, sizeDp = 40, iconDp = 20, filled = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AcademicText(title, c.ink, 15, bold = true, maxLines = 1)
            AcademicText(subtitle, tones.softInk, 12, maxLines = 2)
        }
        AcademicIcon("chevron-right", tones.softInk, 18)
    }
}

@Composable
private fun AcademicDuePreview(task: AcademicTask, now: Long) {
    val c = LocalNativeColors.current
    val tones = academicTones()
    val soon = AcademicRecords.bucket(task, now) == "soon"
    val tone = if (soon) tones.amber else c.danger
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(34.dp)
                .clip(Capsule())
                .background(tone),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            AcademicText(task.title, c.ink, 15, bold = true, maxLines = 2)
            NText(
                "${task.course} · ${AcademicRecords.source(task.source)}",
                12,
                muted = true,
                maxLines = 1,
            )
        }
        NTag(text = AcademicRecords.todayDue(task, now), color = if (soon) tones.amber else tones.dueInk)
    }
}

@Composable
private fun AcademicNoticePreview(notice: AcademicNotice) {
    val c = LocalNativeColors.current
    val context = LocalContext.current
    val rowShape = RoundedRectangle(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                notice.url?.let { url ->
                    Modifier.nativeCardAction({ nativeOpenLink(context, url) }, shape = rowShape)
                } ?: Modifier,
            )
            .nativeGlassCard(18)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AcademicText(notice.title, c.ink, 15, bold = true, maxLines = 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NTag(AcademicRecords.source(notice.source), c.brand)
                val meta = listOf(notice.section, notice.publishedRaw).filter { it.isNotEmpty() }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    NText(meta, 12, muted = true, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        if (notice.url != null) AcademicIcon("external", c.muted, 16)
    }
}

@Composable
private fun AcademicMilestones(items: List<AcademicMilestone>) {
    val c = LocalNativeColors.current
    val dueInk = academicTones().dueInk
    val leaf = academicLeafColor()
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            val accent = when {
                item.days <= 0 -> c.brand
                item.kind == "exam" -> dueInk
                item.kind == "break" -> leaf
                else -> c.brand
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .nativeGlassCard(20)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AcademicText(
                        text = if (item.days <= 0) "进行中" else item.days.toString(),
                        color = accent,
                        fontSize = if (item.days <= 0) 18 else 28,
                        bold = true,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                    )
                    if (item.days > 0) {
                        NText("天后", 12, muted = true, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NText(item.label, 14, bold = true, maxLines = 1)
                    NText(
                        AcademicClock.monthDay(item.from) + if (item.to != item.from) " – ${AcademicClock.monthDay(item.to)}" else "",
                        12,
                        muted = true,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun AcademicMode(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val label = when {
        s.syncing -> "同步中…"
        s.loading -> "读取中…"
        s.sample -> "示例数据 ›"
        s.mode == "cached" -> s.syncedAt?.let { "离线 · ${AcademicClock.ago(it)}" } ?: "未连接"
        else -> s.syncedAt?.let { "${AcademicClock.ago(it)}更新" } ?: "已更新"
    }
    Column(horizontalAlignment = Alignment.End) {
        NButton(
            label,
            {
                if (s.sample) onConnect() else scope.launch {
                    try { store.refresh("sync") } catch (e: Exception) { error = academicError(e) }
                }
            },
            enabled = !s.loading && !s.syncing,
            haptic = if (s.sample) NativeButtonHaptic.Navigation else NativeButtonHaptic.Submit,
        )
        NError(error)
    }
}

/** Unified course recipes across all campus palettes × light/dark. */
private typealias AcademicCourseColors = NativeCourseSwatch

@Composable
private fun academicCourseColors(hue: Int): AcademicCourseColors = currentNativeCourseSwatch(hue)

@Composable
internal fun academicCourseColor(hue: Int): Color = academicCourseColors(hue).mark

@Composable
private fun academicLeafColor(): Color {
    return currentNativeSemanticTones().leaf
}

@Composable
private fun AcademicRibbon(
    blocks: List<AcademicBlock>,
    nowMinute: Int? = null,
    compact: Boolean = false,
    labelled: Boolean = !compact,
    scaleOnly: Boolean = false,
) {
    val colors = LocalNativeColors.current
    val courseMarks = blocks.map { currentNativeCourseSwatch(it.course.hue).mark }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        if (!scaleOnly) Canvas(Modifier.fillMaxWidth().height(if (compact) 16.dp else 22.dp)) {
            val span = (AcademicTimetable.gridEnd - AcademicTimetable.gridStart).toFloat()
            fun x(minute: Int) = (minute - AcademicTimetable.gridStart) / span * size.width
            drawRoundRect(colors.border.copy(alpha = .50f), cornerRadius = CornerRadius(6.dp.toPx()))
            listOf(755 to 840, 1115 to 1160).forEach { (from, to) ->
                clipRect(x(from), 0f, x(to), size.height) {
                    var offset = x(from) - size.height
                    while (offset < x(to)) {
                        drawLine(colors.muted.copy(alpha = .16f), Offset(offset, size.height), Offset(offset + size.height, 0f), 1.dp.toPx())
                        offset += 6.dp.toPx()
                    }
                }
            }
            blocks.forEachIndexed { index, block ->
                val color = courseMarks[index]
                val width = (x(block.endMin) - x(block.startMin)).coerceAtLeast(2f)
                drawRoundRect(
                    color.copy(alpha = if (nowMinute != null && block.endMin <= nowMinute) .42f else .88f),
                    Offset(x(block.startMin), 1.dp.toPx()),
                    Size(width, size.height - 2.dp.toPx()),
                    CornerRadius(5.dp.toPx()),
                )
                if (block.conflict) {
                    drawRoundRect(
                        colors.danger,
                        Offset(x(block.startMin), 1.dp.toPx()),
                        Size(width, size.height - 2.dp.toPx()),
                        CornerRadius(5.dp.toPx()),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            if (nowMinute != null && nowMinute in AcademicTimetable.gridStart..AcademicTimetable.gridEnd) {
                drawLine(colors.danger, Offset(x(nowMinute), 0f), Offset(x(nowMinute), size.height), 2.dp.toPx())
            }
        }
        if (labelled) BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
            val safeSpan = (maxWidth.value - 18f).coerceAtLeast(1f)
            listOf(8, 10, 12, 14, 16, 18, 20, 22).forEach { hour ->
                val fraction = ((hour * 60).coerceAtMost(AcademicTimetable.gridEnd) - AcademicTimetable.gridStart).toFloat() /
                    (AcademicTimetable.gridEnd - AcademicTimetable.gridStart)
                Box(Modifier.offset(x = (safeSpan * fraction).dp)) {
                    NText("$hour", 11, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
                }
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
    val tasks = s.tasks.map(AcademicRecords::task)
        .filter { it.id !in s.done && AcademicRecords.bucket(it, now.ms) in listOf("overdue", "today", "soon") }
        .sortedBy { it.dueMs ?: 0 }
        .take(4)
    val notices = s.notices.map(AcademicRecords::notice).sortedByDescending { it.publishedMs ?: 0 }.take(4)
    val milestones = AcademicCalendar.milestones(now.date, 2)
    var open by remember { mutableStateOf<AcademicCourseSelection?>(null) }
    var showDigest by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val openComposer = LocalCalendarComposer.current

    val dateLine = "${AcademicClock.monthDay(now.date)} ${AcademicClock.weekdayText(now.weekday)}" +
        if (pos != null) " · 第 ${pos.week} 周 · ${pos.phase?.label.orEmpty()}" else ""

    NPage(
        title = "今天",
        eyebrow = dateLine,
        actions = { AcademicMode(store, s, onConnect) },
    ) {
        val reconnectSources = run {
            val bound = s.boundSources.ifEmpty { academicStrings(s.user?.optJSONArray("bound")).toSet() }
            s.sources.filter { it.optString("state") == "need_login" && it.optString("source") in bound }
                .map { it.optString("source") }
        }
        val reconnect = reconnectSources.map { AcademicRecords.source(it) }
        if (reconnect.isNotEmpty()) NCard {
            val onlyMed = reconnectSources.all { it == "med" }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NIconBadge("shield", LocalNativeColors.current.danger, sizeDp = 36, iconDp = 18)
                Box(Modifier.weight(1f)) {
                    NText("${reconnect.joinToString("、")}的登录已过期，课表和作业暂时停在上次同步。", 14)
                }
            }
            NButton(
                if (onlyMed) "重新连接医学院教务" else "重新连接",
                { if (onlyMed) onNavigate("medical") else onConnect() },
                primary = true,
                haptic = NativeButtonHaptic.Navigation,
            )
        }

        if (waiting) NCard {
            val busy = s.syncing || s.loading
            NTag(if (busy) "正在本机直连读取" else "尚未连接课表")
            NText(
                if (busy) "正在直连学校系统读取课表…" else if (s.error != null) "这次同步没有完成" else "还没有课表数据",
                18,
                bold = true,
            )
            NText(
                if (busy) "读完后这里会显示你现在该去的教室，全程仅保存在本机。"
                else "选择任一来源即可在本机离线看课表，校园账号与课表均仅保存在本机。",
                14,
                muted = true,
            )
            if (busy) NLoading() else {
                if (s.boundSources.isNotEmpty()) {
                    NButton(
                        "再同步一次",
                        { scope.launch { try { store.refresh("force") } catch (e: Exception) { error = academicError(e) } } },
                        haptic = NativeButtonHaptic.Submit,
                    )
                }
                NButton("本地连接本部校园账号", onConnect, primary = true, haptic = NativeButtonHaptic.Navigation)
                NButton("连医学院教务", { onNavigate("medical") }, haptic = NativeButtonHaptic.Navigation)
                NButton("手动导入课表", { onNavigate("import") }, haptic = NativeButtonHaptic.Navigation)
            }
        } else {
            AcademicNowCard(current, next, later, blocks, now.minutes, plan.status) {
                open = AcademicCourseSelection(it, plan.date)
            }
        }

        TodayActionRow(
            activeBlock = current ?: next ?: later?.block,
            activeDate = if (current != null || next != null) plan.date else later?.date ?: plan.date,
            urgentTasks = tasks.size,
            onOpenBlock = { block, date -> open = AcademicCourseSelection(block, date) },
            onOpenWeek = { onNavigate("week") },
            onOpenItems = { onNavigate("items") },
            onAdd = {
                if (s.preview) onConnect()
                else openComposer(CalendarComposerDraft("", "", ItemsPriority.NORMAL, now.date, null, null))
            },
        )

        NError(error)
        if (s.sample) AcademicCta("这是示例课表", "本地直连校园账号或手动导入，换成你的真实课表", "shield", onConnect)

        if (blocks.isNotEmpty()) AcademicSection("今天的时间") {
            NCard {
                AcademicDayOverview(blocks, now.minutes)
                NDivider()
                AcademicAgenda(blocks, now.minutes, compact = true) { open = AcademicCourseSelection(it, plan.date) }
            }
        }

        AcademicSection("快到期", action = if (tasks.isNotEmpty()) "全部作业" else null, onAction = { onNavigate("items:school") }) {
            when {
                waiting -> NLoading()
                tasks.isEmpty() -> Row(
                    Modifier
                        .fillMaxWidth()
                        .nativeGlassCard(18)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NIconBadge("check", academicLeafColor(), sizeDp = 32, iconDp = 16)
                    NText("未来 4 天没有到期的作业，安心推进自己的安排吧", 13, muted = true, modifier = Modifier.weight(1f))
                }
                else -> NCard {
                    tasks.forEachIndexed { idx, task ->
                        if (idx > 0) NDivider()
                        AcademicDuePreview(task, now.ms)
                    }
                }
            }
        }

        if (milestones.isNotEmpty() || notices.isNotEmpty()) {
            val digestParts = buildList {
                if (milestones.isNotEmpty()) add("${milestones.size} 个校历节点")
                if (notices.isNotEmpty()) add("${notices.size} 条新通知")
            }.joinToString(" · ")
            NCard {
                NRow(
                    title = "校历与通知",
                    subtitle = digestParts,
                    trailing = if (showDigest) "收起" else "展开",
                    trailingIcon = if (showDigest) "chevron-up" else "chevron-down",
                    leadingIcon = "bell",
                    onClick = { showDigest = !showDigest },
                )
            }
            if (showDigest) {
                if (milestones.isNotEmpty()) AcademicSection("校历", action = "查看校历", onAction = { onNavigate("campus:calendar") }) {
                    AcademicMilestones(milestones)
                }
                if (notices.isNotEmpty()) AcademicSection("新通知", action = "全部通知", onAction = { onNavigate("campus:notices") }) {
                    notices.forEach { notice -> AcademicNoticePreview(notice) }
                }
            }
        }
    }
    open?.let { AcademicCourseSheet(it, now) { open = null } }
}

@Composable
private fun TodayActionRow(
    activeBlock: AcademicBlock?,
    activeDate: String,
    urgentTasks: Int,
    onOpenBlock: (AcademicBlock, String) -> Unit,
    onOpenWeek: () -> Unit,
    onOpenItems: () -> Unit,
    onAdd: () -> Unit,
) {
    val c = LocalNativeColors.current
    val items = listOf(
        Triple(
            if (activeBlock != null) "本节详情" else "记一件事",
            if (activeBlock != null) "pin" else "plus",
        ) {
            if (activeBlock != null) onOpenBlock(activeBlock, activeDate) else onAdd()
        },
        Triple("本周课表", "week", onOpenWeek),
        Triple(
            if (urgentTasks > 0) "$urgentTasks 项快到期" else "记一件事",
            if (urgentTasks > 0) "items" else "plus",
            if (urgentTasks > 0) onOpenItems else onAdd,
        ),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (label, icon, action) ->
            val shape = RoundedRectangle(18.dp)
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .nativeCardAction(action, shape = shape)
                    .nativeGlassCard(18)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NativeIcon(icon, c.brand, size = 17.dp, strokeWidth = 2.0f)
                AcademicText(label, c.ink, 13, bold = true, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun AcademicDayOverview(blocks: List<AcademicBlock>, nowMinute: Int? = null) {
    val c = LocalNativeColors.current
    val first = blocks.minByOrNull { it.startMin }
    val last = blocks.maxByOrNull { it.endMin }
    val totalMinutes = AcademicTimetable.minutes(blocks)
    val hops = AcademicTimetable.hops(blocks)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val windowText = if (first != null && last != null) "${first.start}–${last.end}" else "今日课程"
            AcademicText(windowText, c.ink, 14, bold = true, maxLines = 1, softWrap = false, tabularNums = true)
            val metaText = "${blocks.size} 节 · ${AcademicClock.span(totalMinutes)}" +
                if (hops.isNotEmpty()) " · 换 ${hops.size} 次教室" else ""
            NText(metaText, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
        }
        AcademicRibbon(blocks, nowMinute, compact = true, labelled = true)
    }
}

private data class AcademicNowContent(
    val block: AcademicBlock?,
    val inProgress: Boolean,
    val later: AcademicNextClass?,
    val finished: Boolean,
    val status: AcademicDayStatus,
    val previousRoom: AcademicBlock?,
)

@Composable
private fun AcademicNowCard(
    current: AcademicBlock?,
    upcoming: AcademicBlock?,
    later: AcademicNextClass?,
    blocks: List<AcademicBlock>,
    minute: Int,
    status: AcademicDayStatus,
    onOpen: (AcademicBlock) -> Unit,
) {
    val block = if (status.kind == "closed") null else current ?: upcoming
    val previousRoom = if (current == null && block != null && block.location.isNotEmpty()) {
        blocks.getOrNull(blocks.indexOf(block) - 1)?.takeIf {
            it.location.isNotEmpty() && it.location != block.location && block.startMin - it.endMin <= 30
        }
    } else null
    val content = AcademicNowContent(block, current != null, later, blocks.isNotEmpty(), status, previousRoom)
    val reduced = LocalLiquidGlassReducedMotion.current
    val themeProgress = liquidGlassThemeProgress()
    val shape = RoundedRectangle(26.dp)
    val shadowTint = if (block != null) Color(0xFF0A1424).copy(alpha = 0.24f) else Color.Transparent

    Box(
        Modifier.fillMaxWidth()
            .shadow(if (block != null) 12.dp else 0.dp, shape, ambientColor = shadowTint, spotColor = shadowTint)
            .then(if (block != null) Modifier.nativeCardAction({ onOpen(block) }, shape = shape) else Modifier),
    ) {
        AnimatedContent(
            targetState = block?.course?.hue,
            modifier = Modifier.matchParentSize(),
            transitionSpec = {
                if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                else (fadeIn(tween(180)) togetherWith fadeOut(tween(140))).using(null)
            },
            label = "today card surface",
        ) { hue ->
            if (hue == null) {
                Box(Modifier.fillMaxSize().nativeGlassCard(22))
            } else {
                val mark = academicCourseColors(hue).mark
                val heroSurface = lerp(Color(0xFF132238), Color(0xFF1E3250), themeProgress)
                val heroTop = lerp(heroSurface, mark, 0.22f)
                Box(
                    Modifier.fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(heroTop, heroSurface),
                                start = Offset.Zero,
                                end = Offset(900f, 700f),
                            ),
                            shape,
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
                ) {
                    AcademicHeroDecoration(mark, Modifier.matchParentSize())
                }
            }
        }
        AnimatedContent(
            targetState = content,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                else (fadeIn(tween(170)) togetherWith fadeOut(tween(125)))
                    .using(SizeTransform { _, _ -> tween(220) })
            },
            // Countdown and progress use the current minute without restarting the card transition.
            contentKey = {
                listOf(it.block?.key, it.inProgress, it.later?.date, it.later?.block?.key,
                    it.finished, it.status.kind, it.status.note.isNotEmpty(), it.previousRoom?.key)
            },
            label = "today course status",
        ) { shown ->
            val lesson = shown.block
            if (lesson == null) {
                AcademicIdleHero(shown.status, shown.finished, shown.later)
            } else {
                AcademicLessonHero(
                    lesson, shown.inProgress, minute, shown.previousRoom,
                    shown.status.note.takeIf { shown.status.kind == "makeup" }.orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun AcademicIdleHero(status: AcademicDayStatus, finished: Boolean, later: AcademicNextClass?) {
    val colors = LocalNativeColors.current
    val closed = status.kind == "closed"
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tint = if (finished && !closed) academicLeafColor() else colors.brand
            NIconBadge(
                icon = when {
                    closed -> "calendar"
                    finished -> "check"
                    else -> "spark"
                },
                tint = tint,
                sizeDp = 36,
                iconDp = 18,
            )
            NTag(
                text = when {
                    closed -> "今日停课"
                    finished -> "今天的课上完了"
                    else -> "今天没有课"
                },
                color = tint,
            )
        }
        when {
            closed -> {
                NText(status.label, 20, bold = true)
                NText("按教务处校历，今天没有课。", 14, muted = true)
            }
            later != null -> {
                val whenPrefix = if (later.offset == 1) "明天" else "${AcademicClock.monthDay(later.date)} ${AcademicClock.weekdayText(later.plan.weekday)}"
                NText("$whenPrefix ${later.block.start} · ${later.block.course.title}", 18, bold = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NativeIcon("pin", colors.muted, size = 14.dp)
                    NText(
                        later.block.location.ifEmpty { "地点未标注" } +
                            if (later.offset == 1 && later.block.startMin <= 480) " · 早八记得定闹钟" else "",
                        13,
                        muted = true,
                    )
                }
            }
            else -> NText("接下来两周没有排课", 18, bold = true)
        }
    }
}

@Composable
private fun AcademicLessonHero(
    block: AcademicBlock,
    inProgress: Boolean,
    minute: Int,
    previous: AcademicBlock?,
    makeupNote: String,
) {
    val mark = academicCourseColors(block.course.hue).mark
    val heroPrimary = Color(0xFFF8FAFC)
    val heroSecondary = Color(0xFFCBD5E1)
    val heroChipBg = Color.White.copy(alpha = 0.12f)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(mark))
                AcademicText(
                    text = if (inProgress) {
                        "正在上课 · 还剩 ${AcademicClock.span((block.endMin - minute).coerceIn(0, block.span))}"
                    } else {
                        "下一节 · ${if (block.startMin <= minute) "马上开始" else "还有 ${AcademicClock.span(block.startMin - minute)}"}"
                    },
                    color = heroSecondary,
                    fontSize = 13,
                    bold = true,
                    maxLines = 1,
                    softWrap = false,
                    tabularNums = true,
                )
            }
            AcademicText(
                AcademicTimetable.periodsText(block.periods),
                heroSecondary,
                12,
                maxLines = 1,
                softWrap = false,
                tabularNums = true,
            )
        }

        AcademicText(block.course.title, heroPrimary, 24, bold = true, maxLines = 2)

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AcademicText(block.start, heroPrimary, 40, bold = true, maxLines = 1, softWrap = false, tabularNums = true)
            AcademicText(
                "– ${block.end}",
                heroSecondary,
                20,
                bold = true,
                maxLines = 1,
                softWrap = false,
                tabularNums = true,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Box(Modifier.fillMaxWidth().height(0.75.dp).background(Color.White.copy(alpha = 0.12f)))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NativeIcon("pin", mark, size = 15.dp)
            AcademicText(
                block.location.ifEmpty { "教务未标注地点" },
                heroPrimary,
                14,
                bold = true,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (block.teacher.isNotEmpty()) {
                AcademicText("· ${block.teacher}", heroSecondary, 13, maxLines = 1)
            }
        }

        if (previous != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedRectangle(12.dp))
                    .background(heroChipBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NativeIcon("spark", mark, size = 14.dp)
                AcademicText(
                    "换教室：上一节在 ${previous.location}，课间 ${AcademicClock.span(block.startMin - previous.endMin)}",
                    heroPrimary,
                    12,
                    bold = true,
                )
            }
        }
        if (makeupNote.isNotEmpty()) AcademicText(makeupNote, heroSecondary, 13, bold = true)
        if (inProgress) {
            AcademicProgress((minute - block.startMin).toFloat() / block.span, mark, Modifier.padding(top = 2.dp))
        }
    }
}

private var academicHeroSeen = false

@Composable
private fun AcademicHeroDecoration(mark: Color, modifier: Modifier) {
    val allowed = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    val kind = remember { if (!academicHeroSeen && allowed) Random.nextInt(3) else -1 }
    val opening = remember { Animatable(if (kind >= 0) 0f else 1f) }
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(kind) {
        if (kind >= 0) {
            academicHeroSeen = true
            opening.animateTo(1f, tween(3600, easing = LinearEasing))
        }
    }
    LaunchedEffect(mark) {
        if (allowed) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(1150, easing = FastOutSlowInEasing))
        }
    }
    if (!allowed) return
    Canvas(modifier) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(mark.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.16f),
                radius = (size.width * 0.65f).coerceAtLeast(1f),
            ),
            radius = size.width * 0.65f,
            center = Offset(size.width * 0.88f, size.height * 0.16f),
        )
        val p = opening.value
        if (kind >= 0 && p < 1f) {
            val life = sin(p * PI).toFloat().coerceAtLeast(0f)
            when (kind) {
                0 -> {
                    val path = Path().apply {
                        moveTo(size.width, size.height)
                        cubicTo(size.width * .72f, size.height * .8f, size.width * .92f, size.height * .2f, size.width * .65f, 0f)
                    }
                    drawPath(path, mark.copy(alpha = .13f * life), style = Stroke(8.dp.toPx()))
                    repeat(6) { i ->
                        val x = size.width * (.78f + .07f * sin(i.toFloat()))
                        val y = size.height * i / 6
                        drawOval(mark.copy(alpha = .13f * life), Offset(x, y), Size(26.dp.toPx() * life, 12.dp.toPx() * life))
                    }
                }
                1 -> {
                    val x = size.width * .8f
                    val y = size.height * .68f
                    drawRoundRect(mark.copy(alpha = .13f * life), Offset(x - 25.dp.toPx(), y), Size(50.dp.toPx(), 37.dp.toPx()), CornerRadius(6.dp.toPx()))
                    repeat(3) { i ->
                        val theta = p * PI * 6 + i * PI * 2 / 3
                        drawCircle(mark.copy(alpha = .15f * life), 5.dp.toPx(), Offset(x + cos(theta).toFloat() * 55.dp.toPx(), y + sin(theta).toFloat() * 33.dp.toPx()))
                    }
                }
                else -> {
                    val surface = size.height * (1 - life * .65f)
                    val water = Path().apply {
                        moveTo(0f, size.height)
                        lineTo(0f, surface)
                        var x = 0f
                        while (x <= size.width) {
                            lineTo(x, surface + sin(x / 30 + p * 10) * 8.dp.toPx())
                            x += 6.dp.toPx()
                        }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(water, mark.copy(alpha = .11f))
                }
            }
        }
        val s = sweep.value
        if (s > 0.001f && s < 0.999f) {
            val x = -size.width * 0.5f + size.width * 2.1f * s
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.15f), mark.copy(alpha = 0.09f), Color.Transparent),
                    Offset(x, 0f),
                    Offset(x + size.width * 0.52f, size.height),
                ),
            )
        }
    }
}

@Composable
private fun AcademicAgenda(blocks: List<AcademicBlock>, minute: Int, compact: Boolean = false, onOpen: (AcademicBlock) -> Unit) {
    val colors = LocalNativeColors.current
    val entries = blocks.map { Triple(it.startMin, it, null as AcademicGap?) } +
        AcademicTimetable.gaps(blocks).map { Triple(it.startMin, null as AcademicBlock?, it) }
    entries.sortedBy { it.first }.forEach { (_, block, gap) ->
        if (block != null) {
            val status = when {
                minute < 0 -> ""
                minute >= block.endMin -> "past"
                minute >= block.startMin -> "live"
                else -> "next"
            }
            AcademicLesson(block, onOpen, status, compact)
        } else if (gap != null && compact) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(54.dp)) { NText(gap.start, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true) }
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Canvas(Modifier.width(12.dp).height(24.dp)) {
                        drawLine(
                            colors.muted.copy(alpha = .4f),
                            Offset(6.dp.toPx(), 2.dp.toPx()),
                            Offset(6.dp.toPx(), 22.dp.toPx()),
                            1.6.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
                        )
                    }
                    NText("${gap.label} ${AcademicClock.span(gap.span)}", 12, muted = true, maxLines = 1)
                }
            }
        } else if (gap != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Canvas(Modifier.weight(1f).height(1.dp)) {
                    drawLine(
                        colors.muted.copy(alpha = .35f),
                        Offset.Zero,
                        Offset(size.width, 0f),
                        1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    )
                }
                NText("${gap.label} ${AcademicClock.span(gap.span)}", 12, muted = true, maxLines = 1, softWrap = false)
                Canvas(Modifier.weight(1f).height(1.dp)) {
                    drawLine(
                        colors.muted.copy(alpha = .35f),
                        Offset.Zero,
                        Offset(size.width, 0f),
                        1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                    )
                }
            }
        }
    }
}

@Composable
private fun AcademicLesson(block: AcademicBlock, onOpen: (AcademicBlock) -> Unit, status: String = "", compact: Boolean = false) {
    val c = LocalNativeColors.current
    val recipe = academicCourseColors(block.course.hue)
    val mark = recipe.mark
    val lessonShape = RoundedRectangle(18.dp)
    if (compact) {
        val isLive = status == "live"
        Row(
            Modifier
                .fillMaxWidth()
                .nativeCardAction({ onOpen(block) }, shape = lessonShape)
                .then(
                    if (isLive) {
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(recipe.soft.copy(alpha = 0.72f))
                            .border(1.dp, mark.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    } else {
                        Modifier.padding(vertical = 8.dp)
                    },
                ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AcademicText(block.start, if (status == "past") c.muted else c.ink, 15, bold = true, maxLines = 1, softWrap = false, tabularNums = true)
                NText(block.end, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
            }
            Box(
                Modifier
                    .width(3.5.dp)
                    .height(38.dp)
                    .clip(Capsule())
                    .background(mark.copy(alpha = if (status == "past") .38f else 1f)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.weight(1f)) {
                        AcademicText(block.course.title, if (status == "past") c.muted else c.ink, 16, bold = true, maxLines = 2)
                    }
                    if (!isLive) {
                        NativeIcon("chevron-right", c.muted, size = 16.dp)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    NativeIcon("pin", if (isLive) mark else c.muted, size = 12.dp)
                    NText(
                        block.location.ifEmpty { "地点未标注" } + if (block.teacher.isNotEmpty()) " · ${block.teacher}" else "",
                        13,
                        muted = true,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (isLive) {
                        NTag("上课中", mark)
                    }
                }
                if (block.conflict) NText("与其他课时间重叠", 12, color = c.danger, bold = true)
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .nativeCardAction({ onOpen(block) }, shape = lessonShape)
            .nativeGlassCard(18)
            .then(if (status == "live") Modifier.border(1.dp, mark.copy(alpha = .5f), lessonShape) else Modifier)
            .padding(vertical = 13.dp, horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(46.dp)
                .clip(Capsule())
                .background(mark.copy(alpha = if (status == "past") .4f else 1f)),
        )
        Column(Modifier.width(52.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NText(block.start, 15, bold = true, maxLines = 1, softWrap = false, tabularNums = true)
            NText(block.end, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(block.course.title, 16, bold = true, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                NativeIcon("pin", c.muted, size = 13.dp)
                NText(block.location.ifEmpty { "地点未标注" }, 13, muted = true, maxLines = 1)
            }
            NText(
                (if (block.teacher.isNotEmpty()) "${block.teacher} · " else "") + AcademicTimetable.periodsText(block.periods),
                12,
                muted = true,
                maxLines = 1,
            )
            if (block.conflict) NText("与其他课时间重叠", 12, color = c.danger, bold = true)
        }
        NativeIcon("chevron-right", c.muted, size = 17.dp)
    }
}

@Composable
private fun AcademicWeekStrip(
    plans: List<AcademicDayPlan>,
    selectedDay: Int,
    todayWeekday: Int?,
    onSelectDay: (Int) -> Unit,
) {
    val c = LocalNativeColors.current
    val tick = nSelectionFeedback()
    Row(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(20)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        plans.forEachIndexed { index, plan ->
            val wd = index + 1
            val selected = selectedDay == wd
            val isToday = todayWeekday == wd
            val dayLabel = listOf("一", "二", "三", "四", "五", "六", "日")[index]
            val dateNum = plan.date.takeLast(2).toIntOrNull()?.toString() ?: plan.date.takeLast(2)
            val shape = RoundedCornerShape(14.dp)
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale = rememberPressScale(pressed, NativeMotion.PressCell)
            val cellBg by animateColorAsState(
                targetValue = when {
                    selected -> c.ink
                    isToday -> c.brand.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
                animationSpec = NativeMotion.select(),
                label = "week-strip-bg",
            )
            val labelInk by animateColorAsState(
                targetValue = when {
                    selected -> c.bg.copy(alpha = 0.78f)
                    isToday -> c.brand
                    else -> c.muted
                },
                animationSpec = NativeMotion.select(),
                label = "week-strip-label",
            )
            val dateInk by animateColorAsState(
                targetValue = when {
                    selected -> c.bg
                    isToday -> c.brand
                    else -> c.ink
                },
                animationSpec = NativeMotion.select(),
                label = "week-strip-date",
            )
            Column(
                Modifier
                    .weight(1f)
                    .nativeScale(scale)
                    .clip(shape)
                    .background(cellBg, shape)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                    ) {
                        if (!selected) {
                            tick()
                            onSelectDay(wd)
                        }
                    }
                    .semantics {
                        contentDescription = "${AcademicClock.weekdayText(wd)} ${AcademicClock.slash(plan.date)}，${
                            if (plan.status.kind == "closed") "停课"
                            else if (plan.blocks.isEmpty()) "没有课"
                            else "${plan.blocks.size} 节课"
                        }"
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AcademicText(
                    text = dayLabel,
                    color = labelInk,
                    fontSize = 11,
                    bold = true,
                    maxLines = 1,
                    softWrap = false,
                )
                AcademicText(
                    text = dateNum,
                    color = dateInk,
                    fontSize = 16,
                    bold = true,
                    maxLines = 1,
                    softWrap = false,
                    tabularNums = true,
                )
                Row(
                    Modifier.height(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val count = plan.blocks.size.coerceAtMost(4)
                    if (count == 0) {
                        Box(Modifier.size(4.dp))
                    } else {
                        plan.blocks.take(4).forEach { block ->
                            val dotColor = if (selected) c.bg else academicCourseColor(block.course.hue)
                            Box(
                                Modifier
                                    .size(4.5.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NativeWeekScreen(store: NativeStore, s: NativeState, onNavigate: (String) -> Unit, onConnect: () -> Unit) {
    val now = academicNow()
    val position = AcademicCalendar.position(now.date)
    val term = position?.term ?: AcademicCalendar.currentOrNext(now.date)
    val pager = key(term.id) {
        rememberPagerState(initialPage = (position?.week ?: 1) - 1) { term.totalWeeks }
    }
    val week = pager.settledPage + 1
    var day by rememberSaveable(term.id) { mutableStateOf(if (position != null) now.weekday else 1) }
    var open by remember { mutableStateOf<AcademicCourseSelection?>(null) }
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var showWeekSpectrum by rememberSaveable { mutableStateOf(false) }
    val doc = remember(s.timetable) { AcademicTimetable.normalize(s.timetable) }
    val colors = LocalNativeColors.current
    val reduced = LocalLiquidGlassReducedMotion.current
    val scope = rememberCoroutineScope()
    val selectionFeedback by rememberUpdatedState(nSelectionFeedback())
    var swiping by remember(pager) { mutableStateOf(false) }

    LaunchedEffect(pager) {
        pager.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> swiping = true
                is DragInteraction.Cancel -> swiping = false
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect {
            if (swiping) selectionFeedback()
            swiping = false
        }
    }

    fun goToPage(page: Int) {
        if (page !in 0 until term.totalWeeks) return
        swiping = false
        scope.launch {
            if (reduced) pager.scrollToPage(page) else pager.animateScrollToPage(page)
        }
    }

    NPage("课表", term.label, actions = { AcademicMode(store, s, onConnect) }) {
        if (!s.sample && doc == null) {
            AcademicEmpty(
                if (s.syncing || s.loading) "正在读取课表…" else "还没有课表",
                if (s.syncing || s.loading) "第一次同步通常要半分钟左右。"
                else "连接校园账号，或在「我的 · 课表与账号」中绑定医学院教务 / 手动导入课表后，这里会显示每周课程。",
            ) {
                if (!s.syncing && !s.loading) {
                    NButton("去「我的」配置数据来源", { onNavigate("me") }, primary = true, haptic = NativeButtonHaptic.Navigation)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val headerPlans = remember(doc, term.id, week) {
                (1..7).map { AcademicTimetable.planFor(doc, AcademicCalendar.dateOf(term, week, it)) }
            }
            val total = headerPlans.sumOf { AcademicTimetable.minutes(it.blocks) }
            val isCurrent = position?.week == week
            val phaseLabel = AcademicCalendar.phase(term, week)?.label
            val rangeText = "${AcademicClock.slash(AcademicCalendar.monday(term, week))}–${AcademicClock.slash(AcademicCalendar.dateOf(term, week, 7))}"

            NativeStepSwitcherBar(
                title = "第 $week 周" + (phaseLabel?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                order = week.toLong(),
                previousLabel = "上一周",
                nextLabel = "下一周",
                canPrevious = pager.targetPage > 0,
                canNext = pager.targetPage < term.totalWeeks - 1,
                onPrevious = { goToPage(pager.targetPage - 1) },
                onNext = { goToPage(pager.targetPage + 1) },
                idleSubtitle = (if (isCurrent) "本周 · $rangeText" else rangeText) +
                    (if (total > 0) " · ${AcademicClock.span(total)}" else " · 左右轻滑切换周次"),
                resetLabel = if (position != null && !isCurrent) "回到本周" else null,
                resetPrefix = if (position != null && !isCurrent) rangeText else null,
                onReset = if (position != null && !isCurrent) ({
                    day = now.weekday
                    goToPage(position.week - 1)
                }) else null,
            )

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pager,
                    snapPositionalThreshold = 0.28f,
                    snapAnimationSpec = if (reduced) snap() else spring(dampingRatio = 0.88f, stiffness = 370f),
                ),
            ) { page ->
                val targetWeek = page + 1
                val plans = remember(doc, term.id, targetWeek) {
                    (1..7).map { AcademicTimetable.planFor(doc, AcademicCalendar.dateOf(term, targetWeek, it)) }
                }
                val targetIsCurrent = position?.week == targetWeek
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    AcademicWeekStrip(
                        plans = plans,
                        selectedDay = day,
                        todayWeekday = if (targetIsCurrent) now.weekday else null,
                        onSelectDay = { day = it },
                    )
                    if (showWeekSpectrum) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .nativeGlassCard(22)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Spacer(Modifier.width(52.dp))
                                Box(Modifier.weight(1f)) {
                                    AcademicRibbon(emptyList(), labelled = true, compact = true, scaleOnly = true)
                                }
                                Spacer(Modifier.width(5.dp))
                            }
                            val max = plans.maxOfOrNull { AcademicTimetable.minutes(it.blocks) }?.coerceAtLeast(1) ?: 1
                            plans.forEachIndexed { index, plan ->
                                val wd = index + 1
                                val on = day == wd
                                val isTodayRow = targetIsCurrent && wd == now.weekday
                                val emphasis by animateFloatAsState(
                                    targetValue = if (on) 1f else 0f,
                                    animationSpec = if (reduced) snap() else tween(150),
                                    label = "selected weekday",
                                )
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedRectangle(14.dp))
                                        .drawBehind { drawRect(colors.brand.copy(alpha = 0.11f * emphasis)) }
                                        .selectable(selected = on, role = Role.RadioButton, onClick = { if (!on) day = wd })
                                        .semantics {
                                            contentDescription = "${AcademicClock.weekdayText(wd)} ${AcademicClock.slash(plan.date)}，${
                                                if (plan.status.kind == "closed") "停课"
                                                else if (plan.blocks.isEmpty()) "没有课"
                                                else "${plan.blocks.size} 节，${AcademicClock.span(AcademicTimetable.minutes(plan.blocks))}"
                                            }"
                                        }
                                        .heightIn(min = 44.dp)
                                        .padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        Modifier.width(52.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        NText(
                                            listOf("一", "二", "三", "四", "五", "六", "日")[index],
                                            14,
                                            bold = true,
                                            color = if (isTodayRow) colors.brand else lerp(colors.ink, colors.brand, emphasis),
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        NText(
                                            plan.date.takeLast(2).toIntOrNull()?.toString() ?: plan.date.takeLast(2),
                                            12,
                                            muted = !isTodayRow,
                                            color = if (isTodayRow) colors.brand else null,
                                            maxLines = 1,
                                            softWrap = false,
                                            tabularNums = true,
                                        )
                                    }
                                    Box(Modifier.weight(1f)) {
                                        if (plan.status.kind == "closed") {
                                            NText(plan.status.label, 12, muted = true, maxLines = 1)
                                        } else {
                                            AcademicRibbon(
                                                plan.blocks,
                                                if (isTodayRow) now.minutes else null,
                                                compact = true,
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .width(5.dp)
                                            .height(22.dp)
                                            .clip(Capsule())
                                            .background(colors.border.copy(alpha = 0.65f)),
                                        contentAlignment = Alignment.BottomCenter,
                                    ) {
                                        val barFraction = (AcademicTimetable.minutes(plan.blocks).toFloat() / max)
                                            .let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(barFraction)
                                                .clip(Capsule())
                                                .background(lerp(colors.muted.copy(alpha = 0.7f), colors.brand, emphasis)),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    AnimatedContent(
                        targetState = day,
                        transitionSpec = {
                            if (reduced) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                            else (fadeIn(tween(150)) togetherWith fadeOut(tween(110)))
                                .using(SizeTransform { _, _ -> tween(180) })
                        },
                        label = "weekday agenda",
                    ) { selectedDay ->
                        val selected = plans[selectedDay - 1]
                        val isSelectedToday = targetIsCurrent && selectedDay == now.weekday
                        AcademicSection(
                            "${AcademicClock.weekdayText(selectedDay)} · ${AcademicClock.monthDay(selected.date)}",
                            if (selected.blocks.isNotEmpty()) "${selected.blocks.size} 节 · ${AcademicClock.span(AcademicTimetable.minutes(selected.blocks))}" else "",
                            action = if (showWeekSpectrum) "收起全周光谱" else "全周光谱",
                            onAction = { showWeekSpectrum = !showWeekSpectrum },
                        ) {
                            when {
                                selected.status.kind == "closed" -> AcademicEmpty("${selected.status.label} · 停课", "按教务处校历，这一天不上课。")
                                selected.blocks.isEmpty() -> AcademicEmpty("这天没有理论课", "实验课和临时调课以学院通知为准。")
                                else -> NCard {
                                    AcademicDayOverview(selected.blocks, if (isSelectedToday) now.minutes else null)
                                    NDivider()
                                    AcademicAgenda(selected.blocks, if (isSelectedToday) now.minutes else -1, compact = true) { block ->
                                        if (selectedDay == day) open = AcademicCourseSelection(block, selected.date)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (doc != null && (doc.pending.isNotEmpty() || doc.unarranged.isNotEmpty())) AcademicSection("未排入网格的课程") {
            NCard {
                doc.pending.forEach { NText(it, 14, color = colors.danger) }
                if (doc.unarranged.isNotEmpty()) {
                    NText("教务列出但没有排时间的课程：", 13, muted = true)
                    doc.unarranged.forEach { NText(it.title, 14, bold = true) }
                }
            }
        }

        NCard {
            NRow(
                title = "课表说明",
                subtitle = "数据源 · 时间网格 · 换教室提醒",
                trailing = if (showNotes) "收起" else "展开",
                trailingIcon = if (showNotes) "chevron-up" else "chevron-down",
                leadingIcon = "info",
                onClick = { showNotes = !showNotes },
            )
            if (showNotes) {
                NDivider()
                NText("只含教务系统里的理论课。节次时刻按教务处作息表：第二、四、五大节各 155 分钟，晚课 19:20 开始。", 12, muted = true)
            }
        }
    }
    open?.let { AcademicCourseSheet(it, now) { open = null } }
}

private data class AcademicCourseSelection(val block: AcademicBlock, val date: String)

@Composable
internal fun AcademicCourseSheetSummary(block: AcademicBlock, date: String, now: AcademicMoment) {
    val mark = academicCourseColor(block.course.hue)
    val currentWeek = AcademicCalendar.position(now.date)?.week ?: 0
    val (done, total) = AcademicTimetable.progress(block.course, currentWeek, now.weekday, now.minutes)
    val day = AcademicClock.weekdayText(AcademicClock.weekday(date))
    NText(
        "${AcademicClock.monthDay(date)} $day · ${block.start}–${block.end} · ${AcademicClock.span(block.span)}",
        13, muted = true, tabularNums = true,
    )
    NRow("地点", block.location.ifEmpty { "教务未标注地点" }, leadingIcon = "pin", leadingTint = mark)
    NRow("教师", block.teacher.ifEmpty { block.course.teacher }.ifEmpty { "未标注" }, leadingIcon = "me", leadingTint = mark)
    if (total > 0) {
        NRow("本学期进度", trailing = "$done / $total 次")
        AcademicProgress(done.toFloat() / total, mark)
    }
}

@Composable
private fun AcademicCourseSheet(selection: AcademicCourseSelection, now: AcademicMoment, onDismiss: () -> Unit) {
    val block = selection.block
    val slots = AcademicTimetable.slots(block.course)
    NSheet(block.course.title, onDismiss) {
        AcademicCourseSheetSummary(block, selection.date, now)
        NText("每周安排", 16, bold = true)
        slots.forEach { slot ->
            NCard {
                NText(
                    "${AcademicClock.weekdayText(slot.weekday)} ${AcademicTimetable.periodByIndex.getValue(slot.periods.first()).start}–${AcademicTimetable.periodByIndex.getValue(slot.periods.last()).end}",
                    15,
                    bold = true,
                    tabularNums = true,
                )
                NText("${AcademicTimetable.periodsText(slot.periods)} · ${AcademicTimetable.weeksText(slot.weeks)}", 14)
                NText(slot.location.ifEmpty { "地点未标注" } + if (slot.teacher.isNotEmpty()) " · ${slot.teacher}" else "", 13, muted = true)
                slot.notes.forEach { NText(it, 13) }
                slot.online.forEach { NText("线上：$it", 13) }
                if (slot.rawTimes.isNotEmpty()) NText("教务原文 ${slot.rawTimes.joinToString("；")}", 12, muted = true)
            }
        }
        NText(
            "时刻按教务处《课堂作息时间表》换算。${if (slots.any { slot -> slot.periods.any { it >= 10 } }) "第 0、A–C 节的编号对应尚未用真实课表核对，" else ""}实际以任课教师通知为准。",
            12,
            muted = true,
        )
    }
}

@Composable
internal fun AcademicExams(exams: List<AcademicExam>, now: Long) {
    val c = LocalNativeColors.current
    val tones = academicTones()
    val sorted = exams.sortedBy { it.startMs ?: Long.MAX_VALUE }
    val upcoming = sorted.filter { it.startMs == null || it.startMs > now - 3 * 3_600_000 }
    val past = sorted.filter { it.startMs != null && it.startMs <= now - 3 * 3_600_000 }
    var sessions by rememberSaveable { mutableStateOf(false) }
    if (exams.isEmpty()) {
        AcademicEmpty("还没有考试安排", "教务公布后，同步一次就会出现在这里。")
        return
    }
    upcoming.firstOrNull { it.date != null }?.let { first ->
        val heroShape = RoundedRectangle(24.dp)
        val bannerBrush = Brush.linearGradient(
            listOf(lerp(c.brand, Color.Black, 0.10f), lerp(c.brand, c.ink, 0.45f)),
            start = Offset.Zero,
            end = Offset(800f, 400f),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(6.dp, heroShape, ambientColor = c.brand.copy(alpha = 0.25f), spotColor = c.brand.copy(alpha = 0.25f))
                .clip(heroShape)
                .background(bannerBrush)
                .border(1.dp, Color.White.copy(alpha = 0.22f), heroShape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AcademicText("距离最近一场考试", Color.White.copy(alpha = .82f), 12, bold = true)
                AcademicText(first.title, Color.White, 18, bold = true, maxLines = 2)
                AcademicText(first.timeRaw, Color.White.copy(alpha = .82f), 13, maxLines = 1, tabularNums = true)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                AcademicText(
                    AcademicClock.daysUntil(first.date!!, now).coerceAtLeast(0).toString(),
                    Color.White,
                    44,
                    bold = true,
                    maxLines = 1,
                    softWrap = false,
                    tabularNums = true,
                )
                AcademicText("天", Color.White.copy(alpha = .85f), 13, bold = true, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
    AcademicSection("考试安排", "${upcoming.size} 场") {
        upcoming.forEach { exam ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .nativeGlassCard(18)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier
                        .width(54.dp)
                        .height(58.dp)
                        .clip(RoundedRectangle(14.dp))
                        .background(if (exam.date != null) tones.soft else c.bg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val dateParts = exam.date?.split('-')?.takeIf { it.size == 3 }
                    val monthNum = dateParts?.get(1)?.toIntOrNull()
                    val dayNum = dateParts?.get(2)?.toIntOrNull()
                    if (monthNum != null && dayNum != null) {
                        AcademicText("${monthNum}月", tones.softInk, 11, bold = true, maxLines = 1, softWrap = false)
                        AcademicText(dayNum.toString(), tones.softInk, 19, bold = true, maxLines = 1, softWrap = false, tabularNums = true)
                    } else {
                        NText("待定", 12, muted = true, maxLines = 1, softWrap = false)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    AcademicText(exam.title, c.ink, 15, bold = true, maxLines = 2)
                    NText(exam.timeRaw.ifEmpty { "时间未公布" }, 12, muted = true, maxLines = 1, tabularNums = true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        NativeIcon("pin", c.muted, size = 12.dp)
                        NText(
                            (exam.location.ifEmpty { "考场未公布" }) +
                                if (exam.seat.isNotEmpty()) " · 座位 ${exam.seat}" else "",
                            12,
                            muted = true,
                            maxLines = 1,
                        )
                    }
                }
                exam.date?.let {
                    val d = AcademicClock.daysUntil(it, now)
                    NTag(if (d == 0) "今天" else "$d 天", if (d <= 3) c.danger else c.brand)
                }
            }
        }
    }
    if (past.isNotEmpty()) NText("已结束 ${past.size} 场", 13, muted = true)
    AcademicBlockButton("学校统一考试场次 ${if (sessions) "收起" else "展开"}", { sessions = !sessions })
    if (sessions) NCard {
        listOf("08:00–10:00", "10:30–12:30", "13:30–15:30", "16:00–18:00", "19:00–21:30").forEachIndexed { i, time ->
            if (i > 0) NDivider()
            NRow("第${listOf("一", "二", "三", "四", "五")[i]}场", trailing = time)
        }
        NText("来源：2026–2027 学年校历所附考试时间表。具体科目以教务通知为准。", 12, muted = true)
    }
    NText("考试时间按教务原文显示，倒计时只按日期估算。", 12, muted = true)
}

@Composable
internal fun AcademicGrades(grades: List<AcademicGrade>) {
    var semester by rememberSaveable { mutableStateOf("all") }
    val semesters = grades.map { it.semester }.filter { it.isNotEmpty() }.distinct().sortedDescending()
    val rows = if (semester == "all") grades else grades.filter { it.semester == semester }
    val sum = AcademicRecords.summarize(rows)
    if (grades.isEmpty()) {
        AcademicEmpty("还没有成绩记录", "同步教务后会出现在这里。")
        return
    }
    AcademicScrollableChoices(
        listOf("all" to "全部学期") + semesters.map { it to AcademicRecords.semesterLabel(it) },
        semester,
    ) { semester = it }
    AcademicScoreboard(
        listOf(
            "加权均分" to sum.weightedScore?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty().ifEmpty { "—" },
            "加权绩点" to sum.weightedGpa?.let { String.format(Locale.ROOT, "%.2f", it) }.orEmpty().ifEmpty { "—" },
            "总学分" to AcademicRecords.creditText(sum.credits),
        ),
    )
    NText(
        "按学分加权，等级制成绩不计入均分，补考和重修逐条计入。均分和绩点按 ${AcademicRecords.creditText(sum.countedCredits)} 学分加权，共 ${sum.total} 条、${sum.counted} 条计入。这是估算，不是学校认定的绩点。",
        12,
        muted = true,
    )
    rows.forEach { grade -> AcademicGradeRow(grade) }
}

@Composable
private fun AcademicGradeRow(grade: AcademicGrade) {
    val colors = LocalNativeColors.current
    val score = grade.score.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val leaf = academicLeafColor()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .nativeGlassCard(18)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(grade.title, 15, bold = true, maxLines = 2)
            NText(
                AcademicRecords.semesterLabel(grade.semester) +
                    (grade.credits?.let { " · ${academicExactNumber(it)} 学分" } ?: "") +
                    (grade.gpa?.let { " · 绩点 ${academicExactNumber(it)}" } ?: "") +
                    if (grade.examType.isNotEmpty() && grade.examType != "正常考试") " · ${grade.examType}" else "",
                12,
                muted = true,
                maxLines = 1,
            )
        }
        AcademicText(
            grade.score.ifEmpty { "—" },
            when {
                score == null -> colors.muted
                score < 60 -> colors.danger
                score >= 85 -> leaf
                else -> colors.ink
            },
            if (score == null) 16 else 20,
            bold = true,
            maxLines = 1,
            softWrap = false,
            tabularNums = true,
        )
    }
}

private fun academicExactNumber(value: Double): String =
    if (value % 1 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun AcademicScrollableChoices(
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
) {
    val colors = LocalNativeColors.current
    val tick = nSelectionFeedback()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, label) ->
            val selected = key == current
            val shape = Capsule()
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(shape)
                    .background(if (selected) colors.brand.copy(alpha = .14f) else colors.surface)
                    .border(1.dp, if (selected) colors.brand.copy(alpha = 0.45f) else colors.border, shape)
                    .selectable(selected, role = Role.RadioButton, onClick = {
                        if (key != current) {
                            tick()
                            onSelect(key)
                        }
                    })
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                NText(
                    label,
                    13,
                    bold = selected,
                    color = if (selected) colors.brand else colors.ink,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
