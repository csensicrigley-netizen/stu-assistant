package com.stuassistant.app.nativeapp

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassDarkTheme
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/** Function-for-function presentation port of Campus.tsx, BusBoard.tsx and lib/live.ts. */
@Composable
fun NativeCampusScreen(
    store: NativeStore,
    s: NativeState,
    initialSection: String = "notices",
    onConnect: () -> Unit = {},
    onSectionChange: (String) -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(initialSection) }
    LaunchedEffect(initialSection) {
        section = initialSection.takeIf { it in listOf("notices", "life", "calendar") } ?: "notices"
    }
    val active = campusForeground()
    val now = campusNow(active)
    val sectionOrder = listOf("notices", "life", "calendar")
    val currentIdx = sectionOrder.indexOf(section).coerceAtLeast(0)
    fun selectSection(next: String) {
        if (next != section) {
            section = next
            onSectionChange(next)
        }
    }
    NPage("校园", "汕头大学", actions = { CampusModePill(store, s, onConnect) }) {
        NChips(
            options = listOf("notices" to "通知", "life" to "生活", "calendar" to "校历"),
            value = section,
            onSelect = { selectSection(it) },
            haptic = "selection",
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .ergonomicHorizontalSwipe(
                    canSwipePrevious = currentIdx > 0,
                    canSwipeNext = currentIdx < sectionOrder.lastIndex,
                    onSwipePrevious = { selectSection(sectionOrder[currentIdx - 1]) },
                    onSwipeNext = { selectSection(sectionOrder[currentIdx + 1]) },
                ),
        ) {
            NativeDirectionalContent(
                targetState = section,
                orderOf = { sectionOrder.indexOf(it).coerceAtLeast(0) },
                label = "campus-section-pager",
            ) { targetSection ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    when (targetSection) {
                        "life" -> CampusLife(store, s, now, active)
                        "calendar" -> CampusCalendar(now)
                        else -> CampusNotices(s, now)
                    }
                }
            }
        }
    }
}

/** Native equivalent of the visible-page polling guard; no background network timer. */
@Composable
private fun campusForeground(): Boolean {
    val view = LocalView.current
    var foreground by remember(view) { mutableStateOf(view.isShown && view.hasWindowFocus()) }
    DisposableEffect(view) {
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener {
            foreground = it && view.isShown && view.windowVisibility == View.VISIBLE
        }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(focusListener)
        foreground = view.isShown && view.hasWindowFocus() && view.windowVisibility == View.VISIBLE
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
        }
    }
    return foreground
}

@Composable
private fun campusNow(active: Boolean): AcademicMoment {
    var now by remember { mutableStateOf(AcademicClock.moment()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = AcademicClock.moment()
            delay(60_000)
        }
    }
    return now
}

@Composable
private fun CampusModePill(store: NativeStore, s: NativeState, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val busy = s.loading || s.syncing
    val label = when {
        s.syncing -> "同步中"
        s.loading -> "读取中"
        s.sample -> "示例数据 ›"
        s.mode == "cached" -> s.syncedAt?.let { "离线 · " + AcademicClock.ago(it) } ?: "未连接"
        else -> s.syncedAt?.let { AcademicClock.ago(it) + "更新" } ?: "已更新"
    }
    NButton(
        label = label,
        onClick = {
            if (s.sample) onConnect()
            else scope.launch {
                try {
                    store.refresh("sync")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    /* The store publishes the refresh error. */
                }
            }
        },
        enabled = !busy,
        haptic = if (s.sample) NativeButtonHaptic.Navigation else NativeButtonHaptic.Submit,
    )
}

@Composable
private fun CampusSection(
    title: String,
    note: String = "",
    icon: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalNativeColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                NativeIcon(icon, c.brand, size = 18.dp, strokeWidth = 1.95f)
            }
            Box(Modifier.weight(1f)) {
                NText(title, 19, bold = true, maxLines = 1, softWrap = false)
            }
            if (note.isNotEmpty()) {
                NTag(note, color = c.muted)
            }
        }
        content()
    }
}

@Composable
private fun CampusFootnote(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NativeIcon("info", LocalNativeColors.current.muted, size = 14.dp, modifier = Modifier.padding(top = 2.dp))
        Box(Modifier.weight(1f)) {
            NText(text, 12, muted = true)
        }
    }
}

@Composable
private fun CampusNotices(s: NativeState, now: AcademicMoment) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("all") }
    val selectTick = nSelectionFeedback()
    val notices = remember(s.notices) { s.notices.map(AcademicRecords::notice) }
    val sources = remember(notices) { notices.map { it.source }.distinct() }
    val shown = remember(notices, source, query) {
        val q = query.trim()
        notices.filter {
            (source == "all" || it.source == source) &&
                (q.isEmpty() || it.title.contains(q, ignoreCase = true) || it.section.contains(q, ignoreCase = true))
        }
    }
    val groups = remember(shown, now.date) {
        val list = mutableListOf<Pair<String, MutableList<AcademicNotice>>>()
        for (notice in shown) {
            val date = notice.publishedMs?.let { AcademicClock.moment(it + 3_600_000).date }
            val label = when {
                date == null -> "日期未标注"
                date == now.date -> "今天"
                else -> AcademicClock.monthDay(date)
            }
            if (list.lastOrNull()?.first == label) list.last().second.add(notice)
            else list.add(label to mutableListOf(notice))
        }
        list
    }

    CampusNoticeSearch(query) { query = it }

    if (sources.size > 1) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf("all") + sources).forEach { key ->
                val c = LocalNativeColors.current
                val selected = source == key
                val shape = Capsule()
                Box(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clip(shape)
                        .background(if (selected) c.brand.copy(alpha = 0.14f) else c.surface)
                        .border(if (selected) 1.25.dp else 1.dp, if (selected) c.brand else c.border, shape)
                        .selectable(selected = selected, role = Role.RadioButton) {
                            if (source != key) {
                                selectTick()
                                source = key
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NText(
                        text = if (key == "all") "全部" else AcademicRecords.source(key),
                        size = 13,
                        bold = selected,
                        color = if (selected) c.brand else c.ink,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }

    if (shown.isEmpty()) {
        NCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NIconBadge("bell")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NText(if (query.isNotEmpty()) "没有匹配的通知" else "缓存里还没有通知", 17, bold = true)
                    NText(
                        if (query.isNotEmpty()) "换个关键词试试。只搜索已同步到这里的通知。"
                        else "同步后会出现在这里。没有记录不代表学校没有发布。",
                        13,
                        muted = true,
                    )
                }
            }
        }
    } else {
        val context = LocalContext.current
        groups.forEach { (label, items) ->
            CampusSection(label, "${items.size} 条", icon = "bell") {
                NCard {
                    items.forEachIndexed { index, notice ->
                        if (index > 0) NDivider()
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val reduced = LocalLiquidGlassReducedMotion.current
                        val rowScale by animateFloatAsState(
                            if (notice.url != null && pressed && !reduced) 0.986f else 1f,
                            if (reduced) snap() else spring(dampingRatio = 0.74f, stiffness = 680f),
                            label = "notice-press",
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { scaleX = rowScale; scaleY = rowScale }
                                .then(
                                    notice.url?.let { url ->
                                        Modifier.clickable(
                                            interactionSource = interaction,
                                            indication = null,
                                            role = Role.Button,
                                        ) { nativeOpenLink(context, url) }
                                    } ?: Modifier,
                                )
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NTag(AcademicRecords.source(notice.source))
                                if (notice.publishedRaw.isNotEmpty()) {
                                    NText(
                                        notice.publishedRaw,
                                        12,
                                        muted = true,
                                        maxLines = 1,
                                        softWrap = false,
                                        tabularNums = true,
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    NText(notice.title, 15, bold = true)
                                }
                                if (notice.url != null) {
                                    NativeIcon("external", LocalNativeColors.current.muted, size = 15.dp)
                                }
                            }
                            NText(
                                notice.section.ifEmpty { "未标注栏目" },
                                12,
                                muted = true,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
    CampusFootnote("标题和日期按学校原文显示，正式要求以学校原文为准。")
}

@Composable
private fun CampusNoticeSearch(query: String, onChange: (String) -> Unit) {
    val c = LocalNativeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .nativeGlassCard(18, elevated = false)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NativeIcon("search", c.brand, size = 18.dp)
        BasicTextField(
            value = query,
            onValueChange = onChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 11.dp)
                .semantics { contentDescription = "搜索通知" },
            textStyle = nativeTextStyle(color = c.ink, sizeSp = 15f, lineHeightSp = 22f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            cursorBrush = SolidColor(c.brand),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        NText(
                            "搜索通知标题，例如「竞赛」「奖学金」",
                            14,
                            muted = true,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    field()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(Capsule())
                    .clickable(role = Role.Button) { onChange("") }
                    .semantics { contentDescription = "清除搜索" },
                contentAlignment = Alignment.Center,
            ) {
                NativeIcon("close", c.muted, size = 16.dp)
            }
        }
    }
}

internal data class CampusDevice(val code: String, val name: String, val location: String, val capacity: Int, val verified: Boolean)
private data class CampusPort(val num: Int, val state: Int, val label: String)
private data class CampusCharge(
    val device: CampusDevice, val total: Int, val free: Int, val occupied: Int, val fault: Int,
    val ports: List<CampusPort>, val live: Boolean, val at: Long?, val unavailable: String? = null,
)
private data class CampusPlace(val name: String, var free: Int = 0, var total: Int = 0, var live: Boolean = true, var at: Long? = null, var missing: Int = 0)

private suspend fun campusFetchCharging(store: NativeStore, server: String): List<CampusCharge> {
    var answered = false
    val rows = try {
        if (server.isBlank()) emptyList() else {
            val response = store.request("/v1/charging", auth = false)
            answered = true
            academicObjects(response.optJSONArray("devices"))
        }
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { emptyList() }
    val byCode = rows.associateBy { it.optJSONObject("device")?.optString("code").orEmpty() }
    return campusDevices.map { device ->
        val hit = byCode[device.code]
        val state = hit?.optJSONObject("state")
        val ports = academicObjects(state?.optJSONArray("ports")).map {
            CampusPort(it.optInt("num"), it.optInt("state"), academicString(it.opt("label")))
        }
        if (ports.isNotEmpty()) {
            val counts = state?.optJSONObject("counts")
            val at = state?.optString("collected_at")?.takeIf { it.isNotBlank() }?.let(AcademicClock::parseTime) ?: AcademicClock.nowMillis()
            CampusCharge(
                device,
                counts?.optInt("total", device.capacity) ?: device.capacity,
                counts?.optInt("free", ports.count { it.state == 1 }) ?: ports.count { it.state == 1 },
                counts?.optInt("occupied", ports.count { it.state == 2 }) ?: ports.count { it.state == 2 },
                counts?.optInt("fault", ports.count { it.state !in 1..2 }) ?: ports.count { it.state !in 1..2 },
                ports,
                true,
                at,
            )
        } else if (answered) {
            CampusCharge(
                device, device.capacity, 0, 0, 0, emptyList(), false, null,
                hit?.optString("error")?.takeIf { it.isNotBlank() } ?: "no_data",
            )
        } else campusSampleCharging(device)
    }
}

private fun campusSampleCharging(device: CampusDevice): CampusCharge {
    val bucket = AcademicClock.nowMillis() / 600_000
    fun hash(text: String): Int {
        var value = 0
        text.forEach { value = (value * 31 + it.code) % 1_000_003 }
        return value
    }
    val ports = (0 until device.capacity).map { index ->
        val r = hash(device.code + "|" + index + "|" + bucket) % 100
        val state = if (r < 24) 1 else if (r < 97) 2 else 5
        CampusPort(index + 1, state, when (state) { 1 -> "空闲"; 2 -> "占用"; else -> "过温保护" })
    }
    return CampusCharge(
        device, device.capacity, ports.count { it.state == 1 }, ports.count { it.state == 2 },
        ports.count { it.state !in 1..2 }, ports, false, null,
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CampusLife(store: NativeStore, s: NativeState, now: AcademicMoment, active: Boolean) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val tones = remember(dark, palette) { nativeSemanticTones(dark, palette) }
    val selectTick = nSelectionFeedback()

    var charging by remember { mutableStateOf<List<CampusCharge>>(emptyList()) }
    var place by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(store, s.server, s.token, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            charging = campusFetchCharging(store, s.server)
            delay(120_000)
        }
    }
    val places = linkedMapOf<String, CampusPlace>()
    charging.forEach { charge ->
        val group = places.getOrPut(charge.device.location) { CampusPlace(charge.device.location) }
        if (charge.unavailable != null) {
            group.missing++
            group.live = false
        } else {
            group.free += charge.free
            group.total += charge.total
            group.live = group.live && charge.live
            charge.at?.let { if (group.at == null || it < group.at!!) group.at = it }
        }
    }
    val sortedPlaces = places.values.sortedByDescending { it.free }
    val liveCount = charging.count { it.live }
    val allLive = charging.isNotEmpty() && liveCount == charging.size
    val hasSamples = charging.any { !it.live && it.unavailable == null }
    val context = LocalContext.current

    CampusSection("宿舍水电", "需校园网", icon = "bolt") {
        NCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                NIconBadge("bolt")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NText("查电费、看读数、自助缴费", 16, bold = true)
                    NText("校园网直连，校外先登录 WebVPN", 12, muted = true, maxLines = 1, softWrap = false)
                }
            }
            NText("学校的水电系统只在校园网内开放。在宿舍或教学区连上校园 Wi-Fi 可直接打开；在校外，先登录 WebVPN，再从 WebVPN 门户进入。", 14, muted = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NButton("水电查询", { nativeOpenLink(context, "http://power.stu.edu.cn/") }, primary = true, haptic = NativeButtonHaptic.Navigation)
                NButton("缴费平台", { nativeOpenLink(context, "http://192.168.48.49:8080/hpao/main") }, haptic = NativeButtonHaptic.Navigation)
                NButton("WebVPN", { nativeOpenLink(context, campusWebVpn) }, haptic = NativeButtonHaptic.Navigation)
            }
            NText("两个页面都是学校的 HTTP 地址，登录时注意确认地址栏。助手只帮你打开入口，不读取账单，也不会代你付款。", 12, muted = true)
        }
    }

    val chargeNote = when {
        charging.isEmpty() -> ""
        allLive -> "实时"
        liveCount > 0 -> charging.count { it.unavailable != null }.toString() + " 台暂无数据"
        hasSamples -> "示意数据"
        else -> "暂无数据"
    }
    CampusSection("充电桩", chargeNote, icon = "spark") {
        if (charging.isEmpty()) {
            CampusSkeletonGrid(6, 2, 92)
        } else {
            sortedPlaces.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { p ->
                        val selected = place == p.name
                        val shape = RoundedRectangle(18.dp)
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val reduced = LocalLiquidGlassReducedMotion.current
                        val scale by animateFloatAsState(
                            if (pressed && !reduced) 0.975f else 1f,
                            if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 680f),
                            label = "charge-card-scale",
                        )
                        val cardTint = if (selected) lerp(c.surface, c.brand, if (dark) 0.18f else 0.09f) else null
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 94.dp)
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .nativeGlassCard(18, tint = cardTint)
                                .then(
                                    if (selected) Modifier.border(1.4.dp, c.brand.copy(alpha = 0.65f), shape)
                                    else Modifier,
                                )
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    role = Role.Button,
                                ) {
                                    selectTick()
                                    place = if (selected) null else p.name
                                }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .semantics {
                                    this.selected = selected
                                    contentDescription = p.name + if (p.total == 0) "，暂无数据" else "，" + p.total + " 个口中 " + p.free + " 个空闲"
                                },
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    NText(p.name, 14, bold = true, maxLines = 1, softWrap = false)
                                }
                                NativeIcon(
                                    if (selected) "chevron-up" else "chevron-down",
                                    if (selected) c.brand else c.muted,
                                    size = 14.dp,
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                if (p.total == 0) {
                                    NText("暂无", 21, bold = true, muted = true, maxLines = 1, softWrap = false)
                                } else {
                                    NText(
                                        p.free.toString(),
                                        24,
                                        bold = true,
                                        color = if (p.free == 0) c.muted else tones.leaf,
                                        maxLines = 1,
                                        softWrap = false,
                                        tabularNums = true,
                                    )
                                    NText(
                                        "/${p.total} 空闲",
                                        12,
                                        muted = true,
                                        maxLines = 1,
                                        softWrap = false,
                                        tabularNums = true,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(Capsule())
                                    .background(c.border.copy(alpha = 0.7f)),
                            ) {
                                if (p.total > 0 && p.free > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((p.free.toFloat() / p.total).coerceIn(0f, 1f))
                                            .height(5.dp)
                                            .clip(Capsule())
                                            .background(tones.leaf),
                                    )
                                }
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (place != null && places.containsKey(place)) {
                charging.filter { it.device.location == place }.forEach { device ->
                    NCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.weight(1f)) {
                                NText(
                                    device.device.name,
                                    15,
                                    bold = true,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                            if (device.unavailable != null) {
                                NTag("暂无数据", c.muted)
                            } else {
                                NTag(
                                    "空 ${device.free} · 占 ${device.occupied}" + if (device.fault > 0) " · 障 ${device.fault}" else "",
                                    if (device.free > 0) tones.leaf else c.muted,
                                )
                            }
                        }
                        if (device.unavailable != null) {
                            NText(
                                if (device.unavailable == "device_offline") "平台说这台设备当前不在线，等它上线再刷新。"
                                else "这一轮平台没有回数据，稍后自动重试。",
                                13,
                                muted = true,
                            )
                        } else {
                            CampusPorts(device, tones.leaf)
                        }
                    }
                }
            }
        }
    }

    CampusSection("图书馆", icon = "book") {
        NCard {
            listOf("桑浦山校区图书馆", "东海岸校区图书馆").forEachIndexed { index, name ->
                if (index > 0) NDivider()
                val open = now.minutes in 480 until 1320
                val stateText = when {
                    now.minutes < 480 -> "08:00 开馆"
                    now.minutes >= 1320 -> "明天 08:00 开馆"
                    1320 - now.minutes <= 60 -> (1320 - now.minutes).toString() + " 分钟后闭馆"
                    else -> "开到 22:00"
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(Capsule())
                            .background(if (open) tones.leaf else c.border),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NText(name, 15, bold = true, maxLines = 1, softWrap = false)
                        NText(stateText, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
                    }
                    NTag(if (open) "开放中" else "已闭馆", color = if (open) tones.leaf else c.muted)
                }
            }
            NDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "特藏室：周一至周五 8:00–12:00、14:30–17:30",
                    "考试月部分自习室 24 小时开放",
                    "假期时间以图书馆公告为准",
                ).forEach { NText("• $it", 12, muted = true) }
            }
        }
    }

    CampusSection("实时公交", icon = "bus") { CampusBusBoard(store, s, now, active, tones.leaf) }

    CampusSection("到桑浦山校区", icon = "pin") {
        NCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("6", "17", "21", "27", "39", "K3").forEach { line ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedRectangle(11.dp))
                            .background(c.brand.copy(alpha = 0.11f))
                            .border(0.75.dp, c.brand.copy(alpha = 0.22f), RoundedRectangle(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        NText(
                            line,
                            15,
                            bold = true,
                            color = c.brand,
                            maxLines = 1,
                            softWrap = false,
                            tabularNums = true,
                            align = TextAlign.Center,
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "39 路终点在汕头大学正门",
                    "潮汕站可乘 181b 路",
                    "线路会调整，出发前以公交官方信息为准",
                ).forEach { NText("• $it", 13, muted = true) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NButton("车来了查实时 ↗", { nativeOpenLink(context, "https://www.chelaile.net.cn/") }, haptic = NativeButtonHaptic.Navigation)
                NButton("高德地图查实时 ↗", { nativeOpenLink(context, "https://www.amap.com/") }, haptic = NativeButtonHaptic.Navigation)
            }
        }
        CampusFootnote("线路来自学校 2023 年来访交通指引（仅供参考）。助手不提供实时到站：唯一找到的实时数据源不允许第三方转用。")
    }

    CampusSection("常用入口", icon = "grid") { CampusEntrances() }
}

@Composable
private fun CampusPorts(device: CampusCharge, leafColor: Color) {
    val c = LocalNativeColors.current
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = device.device.name + "：" + device.free + " 个空闲，" + device.occupied + " 个占用"
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        device.ports.chunked(10).forEach { ports ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ports.forEach { port ->
                    val color = when (port.state) {
                        1 -> leafColor
                        2 -> c.border
                        else -> c.danger
                    }
                    Canvas(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedRectangle(6.dp))
                            .background(if (port.state in 1..2) color else color.copy(alpha = 0.10f))
                            .clickable(role = Role.Button) {
                                android.widget.Toast.makeText(
                                    context,
                                    port.num.toString() + " 号 · " + port.label,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .semantics { contentDescription = port.num.toString() + " 号 · " + port.label },
                    ) {
                        if (port.state !in 1..2) {
                            var x = -size.height
                            while (x < size.width) {
                                drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 2.dp.toPx())
                                x += 5.dp.toPx()
                            }
                        }
                    }
                }
                repeat(10 - ports.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CampusSkeletonGrid(count: Int, columns: Int, height: Int) {
    val c = LocalNativeColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "正在读取" },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        (0 until count).toList().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(height.dp)
                            .clip(RoundedRectangle(18.dp))
                            .background(c.border.copy(alpha = 0.42f)),
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CampusBusBoard(
    store: NativeStore,
    s: NativeState,
    now: AcademicMoment,
    active: Boolean,
    leafColor: Color,
) {
    val c = LocalNativeColors.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(store, s.server, s.token, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            try {
                data = store.request("/v1/bus")
                error = false
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                error = true
            }
            delay(30_000)
        }
    }
    val snapshot = data
    if (snapshot == null) {
        if (error) {
            NCard { NText("公交到站暂时取不到，稍后自动重试。", 14, muted = true) }
        } else {
            CampusSkeletonGrid(3, 1, 62)
        }
        return
    }
    val at = AcademicClock.parseTime(snapshot.optString("updated_at"))
    NCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isLive = snapshot.optBoolean("live")
            NTag(
                if (isLive) "实时 · " + snapshot.optInt("live_count") + " 条" else "按班次推算",
                if (isLive) leafColor else c.muted,
            )
            NText(
                snapshot.optString("stop") + (at?.let { " · " + AcademicClock.ago(it, now.ms) } ?: ""),
                12,
                muted = true,
                maxLines = 1,
                softWrap = false,
                tabularNums = true,
            )
        }
        academicObjects(snapshot.optJSONArray("lines")).forEachIndexed { index, line ->
            if (index > 0) NDivider()
            val eta = if (line.has("eta_min") && !line.isNull("eta_min")) line.optDouble("eta_min") else null
            val next = if (line.has("next_min") && !line.isNull("next_min")) line.optDouble("next_min") else null
            val badgeShape = RoundedRectangle(12.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .defaultMinSize(minWidth = 54.dp, minHeight = 38.dp)
                        .clip(badgeShape)
                        .background(c.brand.copy(alpha = 0.11f))
                        .border(0.75.dp, c.brand.copy(alpha = 0.22f), badgeShape)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NText(
                        line.optString("name"),
                        17,
                        bold = true,
                        color = c.brand,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                        align = TextAlign.Center,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    NText(
                        line.optString("from") + " → " + line.optString("to"),
                        14,
                        bold = true,
                        maxLines = 1,
                        softWrap = false,
                    )
                    NText(
                        "首 " + line.optString("first") + " · 末 " + line.optString("last") + " · 约 " + line.optInt("headway") + " 分钟一班",
                        11,
                        muted = true,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val etaColor = when {
                        eta != null && eta <= 1 -> leafColor
                        eta != null && eta <= 8 -> c.brand
                        else -> c.ink
                    }
                    NText(
                        text = when {
                            eta == null -> "—"
                            eta <= 0 -> "即将到站"
                            else -> campusNumber(eta) + " 分"
                        },
                        size = 17,
                        bold = true,
                        color = etaColor,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                        align = TextAlign.End,
                    )
                    NText(
                        text = if (next != null) "下一班 " + campusNumber(next) + " 分" else line.optString("note"),
                        size = 11,
                        muted = true,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                        align = TextAlign.End,
                    )
                }
            }
        }
        CampusFootnote(snapshot.optString("note"))
        NDivider()
        NText("数据来源 · 以官方为准", 13, bold = true)
        val context = LocalContext.current
        academicObjects(snapshot.optJSONArray("sources")).forEach { source ->
            NRow(
                title = source.optString("name"),
                subtitle = source.optString("note"),
                trailingIcon = "external",
                onClick = { nativeOpenLink(context, source.optString("url")) },
            )
        }
        if (snapshot.optString("official").isNotBlank()) {
            NOpenLink("打开学校官网", snapshot.optString("official"))
        }
    }
}

private fun campusNumber(value: Double): String = if (value % 1 == 0.0) value.toLong().toString() else value.toString()
private data class CampusEntrance(val name: String, val hint: String, val url: String, val group: String, val insecure: Boolean = false, val campusOnly: Boolean = false)

@Composable
private fun CampusEntrances() {
    val context = LocalContext.current
    val c = LocalNativeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("学习", "缴费", "办事", "生活").forEach { group ->
            NText(group, 13, bold = true, muted = true, maxLines = 1, softWrap = false)
            campusEntrances.filter { it.group == group }.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { entry ->
                        val shape = RoundedRectangle(18.dp)
                        Column(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 84.dp)
                                .nativeGlassCard(18)
                                .nativeCardAction(
                                    onClick = { nativeOpenLink(context, entry.url) },
                                    haptic = NativeButtonHaptic.Navigation,
                                    shape = shape,
                                )
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) {
                                    NText(entry.name, 15, bold = true, maxLines = 1, softWrap = false)
                                }
                                NativeIcon("external", c.muted, size = 14.dp)
                            }
                            NText(entry.hint, 12, muted = true, maxLines = 2)
                            if (entry.campusOnly || entry.insecure) {
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (entry.campusOnly) NTag("校内", c.muted)
                                    if (entry.insecure) NTag("HTTP", c.muted)
                                }
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CampusCalendar(now: AcademicMoment) {
    val c = LocalNativeColors.current
    val dark = LocalLiquidGlassDarkTheme.current == true
    val palette = LocalNativePalette.current
    val tones = remember(dark, palette) { nativeSemanticTones(dark, palette) }
    val position = AcademicCalendar.position(now.date)
    val term = position?.term ?: AcademicCalendar.currentOrNext(now.date)
    val rawProgress = AcademicCalendar.progress(term, now.ms)
    val progress = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)

    NCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                NText(term.label, 13, bold = true, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
            }
            if (position?.phase != null) {
                NTag(position.phase.label, campusPhaseColor(position.phase.kind, tones))
            } else if (position == null) {
                NTag("假期中", tones.leaf)
            }
        }
        if (position != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    NText("第", 18, bold = true, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                    BasicText(
                        text = position.week.toString(),
                        modifier = Modifier.alignByBaseline(),
                        maxLines = 1,
                        softWrap = false,
                        style = nativeTextStyle(
                            color = c.brand,
                            sizeSp = 38f,
                            lineHeightSp = 42f,
                            weight = FontWeight.Bold,
                            tabularNums = true,
                        ),
                    )
                    NText("周", 18, bold = true, maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline())
                    NText(
                        "/ 共 ${term.totalWeeks} 周",
                        13,
                        muted = true,
                        maxLines = 1,
                        softWrap = false,
                        tabularNums = true,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                NTag("已过 $pct%", c.brand)
            }
        } else {
            NText("假期中", 24, bold = true)
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .semantics { contentDescription = "学期已过 $pct%" },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.Center)
                    .clip(Capsule()),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                term.phases.forEach { phase ->
                    val span = (phase.to - phase.from + 1).coerceAtLeast(1)
                    val phaseTone = campusPhaseColor(phase.kind, tones)
                    val activePhase = position?.phase?.kind == phase.kind && position.week in phase.from..phase.to
                    Box(
                        Modifier
                            .weight(span.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedRectangle(5.dp))
                            .background(
                                phaseTone.copy(
                                    alpha = if (activePhase) {
                                        if (dark) 0.36f else 0.26f
                                    } else {
                                        if (dark) 0.20f else 0.14f
                                    },
                                ),
                            )
                            .border(
                                0.75.dp,
                                phaseTone.copy(alpha = if (activePhase) 0.48f else 0.24f),
                                RoundedRectangle(5.dp),
                            ),
                    )
                }
            }
            Canvas(Modifier.matchParentSize()) {
                if (size.width <= 1f || size.height <= 1f) return@Canvas
                val edgePad = 4.dp.toPx()
                val maxX = (size.width - edgePad).coerceAtLeast(edgePad)
                val x = (size.width * progress).coerceIn(edgePad, maxX)
                drawRoundRect(
                    color = c.surface,
                    topLeft = Offset(x - 3.5.dp.toPx(), 1.dp.toPx()),
                    size = Size(7.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(3.5.dp.toPx()),
                )
                drawRoundRect(
                    color = c.brand,
                    topLeft = Offset(x - 1.75.dp.toPx(), 2.5.dp.toPx()),
                    size = Size(3.5.dp.toPx(), size.height - 5.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            term.phases.forEach { phase ->
                val phaseTone = campusPhaseColor(phase.kind, tones)
                val rangeText = if (phase.from == phase.to) "${phase.from}周" else "${phase.from}–${phase.to}周"
                Row(
                    Modifier
                        .clip(Capsule())
                        .background(phaseTone.copy(alpha = if (dark) 0.14f else 0.09f))
                        .border(0.6.dp, phaseTone.copy(alpha = 0.20f), Capsule())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(Capsule())
                            .background(phaseTone),
                    )
                    BasicText(
                        text = "${phase.label} $rangeText",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        style = nativeTextStyle(
                            color = phaseTone,
                            sizeSp = 11f,
                            lineHeightSp = 14f,
                            weight = FontWeight.SemiBold,
                            tabularNums = true,
                        ),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NText(AcademicClock.monthDay(term.week1Monday) + " 开学", 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
            NText(AcademicClock.monthDay(term.after.from) + " " + term.after.label, 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
        }
    }

    CampusSection("接下来", icon = "calendar") {
        NCard {
            val milestones = AcademicCalendar.milestones(now.date, 6)
            milestones.forEachIndexed { index, milestone ->
                if (index > 0) NDivider()
                val tone = campusPhaseColor(milestone.kind, tones)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier
                            .size(11.dp)
                            .border(2.8.dp, tone, Capsule()),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NText(milestone.label, 15, bold = true, maxLines = 1, softWrap = false)
                        NText(
                            AcademicClock.monthDay(milestone.from) +
                                if (milestone.to != milestone.from) " – " + AcademicClock.monthDay(milestone.to) else "",
                            12,
                            muted = true,
                            maxLines = 1,
                            softWrap = false,
                            tabularNums = true,
                        )
                    }
                    NTag(
                        text = if (milestone.days <= 0) "进行中" else "${milestone.days} 天",
                        color = if (milestone.days <= 0) tone else c.muted,
                    )
                }
            }
        }
    }

    CampusSection("本学期每周", icon = "week") {
        Column(
            Modifier
                .fillMaxWidth()
                .nativeGlassCard(22)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..term.totalWeeks).toList().chunked(5).forEach { weeks ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        weeks.forEach { week ->
                            val phase = AcademicCalendar.phase(term, week)
                            val current = position?.week == week
                            val past = position != null && week < position.week
                            val tone = campusPhaseColor(phase?.kind ?: "teaching", tones)
                            val shape = RoundedRectangle(13.dp)
                            val cellFg = when {
                                current -> c.onBrand
                                past -> c.muted
                                else -> tone
                            }
                            Column(
                                Modifier
                                    .weight(1f)
                                    .then(
                                        if (current) {
                                            Modifier.shadow(
                                                4.dp,
                                                shape,
                                                ambientColor = c.brand.copy(alpha = 0.26f),
                                                spotColor = c.brand.copy(alpha = 0.26f),
                                            )
                                        } else Modifier,
                                    )
                                    .clip(shape)
                                    .background(
                                        when {
                                            current -> Brush.verticalGradient(
                                                listOf(lerp(c.brand, Color.White, 0.16f), c.brand),
                                            )
                                            past -> Brush.verticalGradient(
                                                listOf(
                                                    c.surface.copy(alpha = if (dark) 0.42f else 0.55f),
                                                    c.bg.copy(alpha = if (dark) 0.36f else 0.48f),
                                                ),
                                            )
                                            else -> Brush.verticalGradient(
                                                listOf(
                                                    tone.copy(alpha = if (dark) 0.20f else 0.13f),
                                                    tone.copy(alpha = if (dark) 0.15f else 0.09f),
                                                ),
                                            )
                                        },
                                    )
                                    .then(
                                        when {
                                            current -> Modifier.border(0.8.dp, Color.White.copy(alpha = 0.34f), shape)
                                            past -> Modifier.border(0.8.dp, c.border.copy(alpha = 0.65f), shape)
                                            else -> Modifier.border(0.8.dp, tone.copy(alpha = 0.24f), shape)
                                        },
                                    )
                                    .padding(horizontal = 2.dp, vertical = 8.dp)
                                    .semantics {
                                        contentDescription = "第 " + week + " 周，" +
                                            AcademicClock.monthDay(AcademicCalendar.monday(term, week)) + "，" +
                                            (phase?.label ?: "教学周") + if (current) "，本周" else ""
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                BasicText(
                                    text = week.toString(),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    style = nativeTextStyle(
                                        color = cellFg,
                                        sizeSp = 15f,
                                        lineHeightSp = 18f,
                                        weight = FontWeight.Bold,
                                        align = TextAlign.Center,
                                        tabularNums = true,
                                    ),
                                )
                                BasicText(
                                    text = AcademicClock.slash(AcademicCalendar.monday(term, week)),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                    style = nativeTextStyle(
                                        color = if (current) c.onBrand.copy(alpha = 0.90f) else cellFg.copy(alpha = 0.85f),
                                        sizeSp = 10.5f,
                                        lineHeightSp = 13f,
                                        align = TextAlign.Center,
                                        tabularNums = true,
                                    ),
                                )
                            }
                        }
                        repeat(5 - weeks.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            NDivider()
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                term.phases.distinctBy { it.kind }.forEach { phase ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(Capsule())
                                .background(campusPhaseColor(phase.kind, tones)),
                        )
                        NText(phase.label, 12, muted = true, maxLines = 1, softWrap = false)
                    }
                }
                if (position != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(Capsule())
                                .background(c.brand)
                                .border(1.dp, Color.White.copy(alpha = 0.6f), Capsule()),
                        )
                        NText("本周（第 ${position.week} 周）", 12, muted = true, maxLines = 1, softWrap = false, tabularNums = true)
                    }
                }
            }
        }
    }

    if (term.makeupDays.isNotEmpty()) {
        CampusSection("调休", icon = "clock") {
            NCard {
                term.makeupDays.forEachIndexed { index, makeup ->
                    if (index > 0) NDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NTag(AcademicClock.monthDay(makeup.date), tones.amber)
                        Box(Modifier.weight(1f)) {
                            NText(makeup.note, 13, tabularNums = true)
                        }
                    }
                }
            }
        }
    }

    NCard {
        NOpenLink("教务处《2026-2027 学年教学校历》", AcademicCalendar.sourceUrl)
        NText("发布于 2026-05-20 · 核对于 2026-10-28。法定假期按国务院办公厅安排补充。", 12, muted = true, tabularNums = true)
    }
}

@Composable
private fun campusPhaseColor(kind: String, tones: NativeSemanticTones): Color = when (kind) {
    "exam" -> LocalNativeColors.current.danger
    "practice" -> tones.leaf
    "break" -> tones.leaf
    "holiday", "suspended" -> tones.amber
    else -> LocalNativeColors.current.brand
}

private const val campusWebVpn = "https://webvpn.stu.edu.cn/"

internal val campusDevices = listOf(
    CampusDevice("4GR00008492", "思源书院左一", "思源书院", 20, true),
    CampusDevice("4GR00008485", "思源书院左二", "思源书院", 20, true),
    CampusDevice("4GR00008489", "思源书院左三", "思源书院", 20, true),
    CampusDevice("4GR00008490", "思源左四", "思源书院", 20, true),
    CampusDevice("4GR00008495", "图书馆一", "图书馆", 20, true),
    CampusDevice("4GR00008493", "图书馆二", "图书馆", 20, true),
    CampusDevice("4GR00008491", "图书馆三", "图书馆", 20, true),
    CampusDevice("4GR00008494", "敬一书院", "敬一书院", 20, true),
    CampusDevice("4GR00008498", "志诚书院", "志诚书院", 20, true),
    CampusDevice("4GR00008480", "修远书院", "修远书院", 20, true),
    CampusDevice("4GR00008484", "文科教学楼", "文科教学楼", 20, true),
    CampusDevice("4GR00008497", "滑板场一", "滑板场", 20, true),
    CampusDevice("4GR00008488", "滑板场二", "滑板场", 20, true),
    CampusDevice("4GR00006723", "教师宿舍", "教师宿舍", 20, true),
)

private val campusEntrances = listOf(
    CampusEntrance("教务系统", "成绩、考试、选课", "https://jw.stu.edu.cn/", "学习", false, false),
    CampusEntrance("MySTU", "课程、作业、资料", "https://my.stu.edu.cn/", "学习", false, false),
    CampusEntrance("图书馆", "馆藏检索、借阅", "https://www.lib.stu.edu.cn/", "学习", false, false),
    CampusEntrance("教务处校历", "2026–2027 学年", "https://jwc.stu.edu.cn/wdxz/jxxl.htm", "学习", false, false),
    CampusEntrance("宿舍水电", "电费读数、历史账单、自助缴费", "http://power.stu.edu.cn/", "缴费", true, true),
    CampusEntrance("水电缴费平台", "hpao 缴费入口", "http://192.168.48.49:8080/hpao/main", "缴费", true, true),
    CampusEntrance("一卡通", "余额与流水", "http://smartcard.stu.edu.cn/homeLogin.action", "缴费", true, false),
    CampusEntrance("WebVPN", "校外访问校内系统", "https://webvpn.stu.edu.cn/", "缴费", false, false),
    CampusEntrance("一网通办", "事项目录与办理", "https://service.stu.edu.cn/fe/", "办事", false, false),
    CampusEntrance("综合报修", "两校区设施、网络故障", "https://r.stu.edu.cn/", "办事", false, false),
    CampusEntrance("宿舍报修", "桑浦山学生社区", "https://repair.stu.edu.cn/", "办事", false, false),
    CampusEntrance("上网账号", "套餐与流量", "https://netms.stu.edu.cn/", "生活", false, false),
    CampusEntrance("网络中心", "桑浦山 0754-86503400", "https://info.stu.edu.cn/its/lxwm.htm", "生活", false, false),
)
