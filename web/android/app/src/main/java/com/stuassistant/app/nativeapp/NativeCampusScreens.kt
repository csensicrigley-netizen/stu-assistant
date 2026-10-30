package com.stuassistant.app.nativeapp

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
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
    NPage("校园", "汕头大学", actions = { CampusModePill(store, s, onConnect) }) {
        NChips(listOf("notices" to "通知", "life" to "生活", "calendar" to "校历"), section, { section = it; onSectionChange(it) })
        when (section) {
            "life" -> CampusLife(store, s, now, active)
            "calendar" -> CampusCalendar(now)
            else -> CampusNotices(store, s, now, active)
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
    NButton(label, {
        if (s.sample) onConnect()
        else scope.launch {
            try { store.refresh("sync") } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* The store publishes the refresh error. */ }
        }
    }, enabled = !busy)
}

@Composable
private fun CampusSection(title: String, note: String = "", content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { NText(title, 20, true) }
            if (note.isNotEmpty()) NText(note, 13, muted = true)
        }
        content()
    }
}

@Composable
private fun CampusTag(text: String, color: Color = LocalNativeColors.current.brand) {
    BasicText(text, Modifier.clip(Capsule()).background(color.copy(alpha = .10f)).padding(horizontal = 8.dp, vertical = 4.dp),
        style = TextStyle(color = color, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium))
}

@Composable
private fun CampusFootnote(text: String) {
    NText("ⓘ " + text, 12, muted = true)
}

@Composable
private fun CampusNotices(store: NativeStore, s: NativeState, now: AcademicMoment, active: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("all") }
    var facts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(store, s.server, s.token, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            try { facts = academicObjects(store.request("/v1/facts", auth = false).optJSONArray("items")) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Same as JS: facts failure never hides school notices. */ }
            delay(30 * 60_000L)
        }
    }
    val notices = remember(s.notices) { s.notices.map(AcademicRecords::notice) }
    val sources = notices.map { it.source }.distinct()
    val shown = notices.filter { (source == "all" || it.source == source) &&
        (query.trim().isEmpty() || it.title.contains(query.trim()) || it.section.contains(query.trim())) }
    val groups = mutableListOf<Pair<String, MutableList<AcademicNotice>>>()
    for (notice in shown) {
        val date = notice.publishedMs?.let { AcademicClock.moment(it + 3_600_000).date }
        val label = when { date == null -> "日期未标注"; date == now.date -> "今天"; else -> AcademicClock.monthDay(date) }
        if (groups.lastOrNull()?.first == label) groups.last().second.add(notice)
        else groups.add(label to mutableListOf(notice))
    }
    CampusSection("今日冷知识", if (facts.isEmpty()) "每天 5 条" else facts.size.toString() + " 条") {
        if (facts.isEmpty()) NText("今天这 5 条还没生成，过一会儿再来看看。", 13, muted = true)
        else NCard {
            facts.forEachIndexed { index, fact ->
                if (index > 0) NDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    CampusTag("冷知识")
                    Box(Modifier.weight(1f)) { NText(academicString(fact.opt("text")), 15) }
                }
            }
        }
    }
    CampusNoticeSearch(query, { query = it })
    if (sources.size > 1) {
        val tick = nSelectionFeedback()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("all") + sources).forEach { key ->
                val c = LocalNativeColors.current
                val selected = source == key
                val shape = Capsule()
                Box(Modifier.heightIn(min = 48.dp).clip(shape)
                    .background(if (selected) c.brand.copy(alpha = .14f) else c.surface)
                    .border(1.dp, if (selected) c.brand else c.border, shape)
                    .selectable(selected = selected, role = Role.RadioButton) { if (source != key) { tick(); source = key } }
                    .padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    NText(if (key == "all") "全部" else AcademicRecords.source(key), 14, selected)
                }
            }
        }
    }
    if (shown.isEmpty()) {
        NCard {
            NText(if (query.isNotEmpty()) "没有匹配的通知" else "缓存里还没有通知", 18, true)
            NText(if (query.isNotEmpty()) "换个关键词试试。只搜索已同步到这里的通知。" else "同步后会出现在这里。没有记录不代表学校没有发布。", 14, muted = true)
        }
    } else {
        val context = LocalContext.current
        groups.forEach { (label, items) ->
            CampusSection(label, items.size.toString() + " 条") {
                NCard {
                    items.forEachIndexed { index, notice ->
                        if (index > 0) NDivider()
                        Column(Modifier.fillMaxWidth()
                            .then(notice.url?.let { url -> Modifier.clickable(role = Role.Button) { nativeOpenLink(context, url) } } ?: Modifier)
                            .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CampusTag(AcademicRecords.source(notice.source))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { NText(notice.title, 15, true) }
                                if (notice.url != null) NText("↗", 16, muted = true)
                            }
                            NText((notice.section.ifEmpty { "未标注栏目" }) +
                                (if (notice.publishedRaw.isNotEmpty()) " · " + notice.publishedRaw else ""), 12, muted = true)
                        }
                    }
                }
            }
        }
    }
    CampusFootnote("标题和日期按学校原文显示，正式要求以原文为准。冷知识每日更新，图个乐子，别拿去写论文。")
}

@Composable
private fun CampusNoticeSearch(query: String, onChange: (String) -> Unit) {
    val c = LocalNativeColors.current
    val shape = RoundedRectangle(16.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.surface)
        .border(1.dp, c.border, shape).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.size(18.dp)) {
            drawCircle(c.muted, size.width * .3f, Offset(size.width * .4f, size.height * .4f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
            drawLine(c.muted, Offset(size.width * .63f, size.height * .63f), Offset(size.width * .94f, size.height * .94f), 1.8.dp.toPx())
        }
        BasicTextField(query, onChange, Modifier.weight(1f).padding(vertical = 10.dp).semantics { contentDescription = "搜索通知" },
            textStyle = TextStyle(color = c.ink, fontSize = 15.sp, lineHeight = 22.sp),
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), cursorBrush = SolidColor(c.brand),
            decorationBox = { field ->
                Box {
                    if (query.isEmpty()) NText("搜索通知标题，例如「竞赛」「奖学金」", 14, muted = true)
                    field()
                }
            })
        if (query.isNotEmpty()) Box(Modifier.size(44.dp).clip(Capsule()).clickable(role = Role.Button) { onChange("") }
            .semantics { contentDescription = "清除搜索" }, contentAlignment = Alignment.Center) { NText("×", 22, muted = true) }
    }
}

private data class CampusDevice(val code: String, val name: String, val location: String, val capacity: Int, val verified: Boolean)
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
            CampusCharge(device, counts?.optInt("total", device.capacity) ?: device.capacity,
                counts?.optInt("free", ports.count { it.state == 1 }) ?: ports.count { it.state == 1 },
                counts?.optInt("occupied", ports.count { it.state == 2 }) ?: ports.count { it.state == 2 },
                counts?.optInt("fault", ports.count { it.state !in 1..2 }) ?: ports.count { it.state !in 1..2 },
                ports, true, at)
        } else if (answered) {
            CampusCharge(device, device.capacity, 0, 0, 0, emptyList(), false, null,
                hit?.optString("error")?.takeIf { it.isNotBlank() } ?: "no_data")
        } else campusSampleCharging(device)
    }
}

private fun campusSampleCharging(device: CampusDevice): CampusCharge {
    val bucket = AcademicClock.nowMillis() / 600_000
    fun hash(text: String): Int { var value = 0; text.forEach { value = (value * 31 + it.code) % 1_000_003 }; return value }
    val ports = (0 until device.capacity).map { index ->
        val r = hash(device.code + "|" + index + "|" + bucket) % 100
        val state = if (r < 24) 1 else if (r < 97) 2 else 5
        CampusPort(index + 1, state, when (state) { 1 -> "空闲"; 2 -> "占用"; else -> "过温保护" })
    }
    return CampusCharge(device, device.capacity, ports.count { it.state == 1 }, ports.count { it.state == 2 },
        ports.count { it.state !in 1..2 }, ports, false, null)
}

@Composable
private fun CampusLife(store: NativeStore, s: NativeState, now: AcademicMoment, active: Boolean) {
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
        if (charge.unavailable != null) { group.missing++; group.live = false }
        else {
            group.free += charge.free; group.total += charge.total; group.live = group.live && charge.live
            charge.at?.let { if (group.at == null || it < group.at!!) group.at = it }
        }
    }
    val sortedPlaces = places.values.sortedByDescending { it.free }
    val liveCount = charging.count { it.live }
    val allLive = charging.isNotEmpty() && liveCount == charging.size
    val hasSamples = charging.any { !it.live && it.unavailable == null }
    val newest = charging.filter { it.live }.mapNotNull { it.at }.maxOrNull()
    val context = LocalContext.current
    CampusSection("宿舍水电", "需校园网") {
        NCard {
            NText("查电费、看读数、自助缴费", 17, true)
            NText("学校的水电系统只在校园网内开放。在宿舍或教学区连上校园 Wi-Fi 可直接打开；在校外，先登录 WebVPN，再从 WebVPN 门户进入。", 14, muted = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NButton("水电查询", { nativeOpenLink(context, "http://power.stu.edu.cn/") }, primary = true)
                NButton("缴费平台", { nativeOpenLink(context, "http://192.168.48.49:8080/hpao/main") })
                NButton("WebVPN", { nativeOpenLink(context, campusWebVpn) })
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
    CampusSection("充电桩", chargeNote) {
        if (charging.isEmpty()) CampusSkeletonGrid(6, 2, 84)
        else {
            val tick = nSelectionFeedback()
            sortedPlaces.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { p ->
                        val c = LocalNativeColors.current
                        val selected = place == p.name
                        val shape = RoundedRectangle(16.dp)
                        Column(Modifier.weight(1f).heightIn(min = 84.dp).clip(shape)
                            .background(if (selected) c.brand.copy(alpha = .10f) else c.surface)
                            .border(1.dp, if (selected) c.brand.copy(alpha = .4f) else c.border, shape)
                            .clickable(role = Role.Button) { tick(); place = if (selected) null else p.name }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .semantics {
                                this.selected = selected
                                contentDescription = p.name + if (p.total == 0) "，暂无数据" else "，" + p.total + " 个口中 " + p.free + " 个空闲"
                            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NText(p.name, 14, true)
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (p.total == 0) NText("暂无", 24, muted = true)
                                else { CampusColoredText(p.free.toString(), if (p.free == 0) c.muted else campusLeaf, 24, true); NText("/" + p.total, 13, muted = true) }
                            }
                            Box(Modifier.fillMaxWidth().height(4.dp).clip(Capsule()).background(c.border)) {
                                if (p.total > 0 && p.free > 0) Box(Modifier.fillMaxWidth((p.free.toFloat() / p.total).coerceIn(0f, 1f)).height(4.dp).background(campusLeaf))
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (place != null) {
                charging.filter { it.device.location == place }.forEach { device ->
                    NCard {
                        NText(device.device.name + if (!device.device.verified && !device.device.name.contains("名称待核")) "（名称待核）" else "", 14, true)
                        NText(if (device.unavailable != null) "暂无数据" else
                            "空闲 " + device.free + " · 占用 " + device.occupied + if (device.fault > 0) " · 故障 " + device.fault else "", 13, muted = true)
                        if (device.unavailable != null) {
                            NText(if (device.unavailable == "device_offline") "平台说这台设备当前不在线，等它上线再刷新。" else "这一轮平台没有回数据，稍后自动重试。", 13, muted = true)
                        } else CampusPorts(device)
                    }
                }
            }
        }
        CampusFootnote("第三方充电平台，已核验 15 台设备，不代表全校。按机身二维码逐台核对（2026-10-29）。" +
            (newest?.let { "采样于 " + AcademicClock.ago(it, now.ms) + "，每两分钟更新。" }
                ?: if (hasSamples) "充电平台暂时连不上，显示的是示意数据。" else if (charging.isEmpty()) "" else "平台本轮暂无设备数据，每两分钟自动重试。"))
        if (charging.any { !it.device.verified }) CampusFootnote("标「名称待核」的设备：编号在平台上有效，但地点名还没有二维码可对，先按原清单名称显示。")
        NCard {
            listOf("桑浦山校区图书馆", "东海岸校区图书馆").forEachIndexed { index, name ->
                if (index > 0) NDivider()
                val open = now.minutes in 480 until 1320
                val stateText = when { now.minutes < 480 -> "08:00 开馆"; now.minutes >= 1320 -> "明天 08:00 开馆"; 1320 - now.minutes <= 60 -> (1320 - now.minutes).toString() + " 分钟后闭馆"; else -> "开到 22:00" }
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(8.dp).clip(Capsule()).background(if (open) campusLeaf else LocalNativeColors.current.border))
                    Box(Modifier.weight(1f)) { NText(name, 15, true) }
                    Column(horizontalAlignment = Alignment.End) { NText(if (open) "开放中" else "已闭馆", 13, true); NText(stateText, 12, muted = true) }
                }
            }
        }
        listOf("特藏室：周一至周五 8:00–12:00、14:30–17:30", "考试月部分自习室 24 小时开放", "假期时间以图书馆公告为准").forEach { NText("• " + it, 13, muted = true) }
    }
    CampusSection("实时公交") { CampusBusBoard(store, s, now, active) }
    CampusSection("到桑浦山校区") {
        NCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("6", "17", "21", "27", "39", "K3").forEach { line ->
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedRectangle(10.dp)).background(LocalNativeColors.current.brand.copy(alpha = .10f)), contentAlignment = Alignment.Center) { NText(line, 16, true) }
                }
            }
            listOf("39 路终点在汕头大学正门", "潮汕站可乘 181b 路", "线路会调整，出发前以公交官方信息为准").forEach { NText("• " + it, 13, muted = true) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NButton("车来了查实时 ↗", { nativeOpenLink(context, "https://www.chelaile.net.cn/") })
                NButton("高德地图查实时 ↗", { nativeOpenLink(context, "https://www.amap.com/") })
            }
        }
        CampusFootnote("线路来自学校 2023 年来访交通指引（仅供参考）。助手不提供实时到站：唯一找到的实时数据源不允许第三方转用。")
    }
    CampusSection("常用入口") { CampusEntrances() }
}

@Composable
private fun CampusColoredText(text: String, color: Color, size: Int = 16, bold: Boolean = false) {
    BasicText(text, style = TextStyle(color = color, fontSize = size.sp, lineHeight = (size * 1.4f).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

@Composable
private fun CampusPorts(device: CampusCharge) {
    val c = LocalNativeColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().semantics { contentDescription = device.device.name + "：" + device.free + " 个空闲，" + device.occupied + " 个占用" },
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        device.ports.chunked(10).forEach { ports ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ports.forEach { port ->
                    val color = when (port.state) { 1 -> campusLeaf; 2 -> c.border; else -> c.danger }
                    Canvas(Modifier.weight(1f).aspectRatio(1f).clip(RoundedRectangle(5.dp))
                        .background(if (port.state in 1..2) color else color.copy(alpha = .08f))
                        .clickable(role = Role.Button) { android.widget.Toast.makeText(context, port.num.toString() + " 号 · " + port.label, android.widget.Toast.LENGTH_SHORT).show() }
                        .semantics { contentDescription = port.num.toString() + " 号 · " + port.label }) {
                        if (port.state !in 1..2) {
                            var x = -size.height
                            while (x < size.width) { drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 2.dp.toPx()); x += 5.dp.toPx() }
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
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "正在读取" }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until count).toList().chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { Box(Modifier.weight(1f).height(height.dp).clip(RoundedRectangle(16.dp)).background(c.border.copy(alpha = .45f))) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CampusBusBoard(store: NativeStore, s: NativeState, now: AcademicMoment, active: Boolean) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(store, s.server, s.token, active) {
        if (!active) return@LaunchedEffect
        while (true) {
            try { data = store.request("/v1/bus", auth = false); error = false }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = true }
            delay(30_000)
        }
    }
    val snapshot = data
    if (snapshot == null) {
        if (error) NText("ⓘ 公交到站暂时取不到，稍后自动重试。", 14, muted = true)
        else CampusSkeletonGrid(3, 1, 56)
        return
    }
    val at = AcademicClock.parseTime(snapshot.optString("updated_at"))
    NCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CampusTag(if (snapshot.optBoolean("live")) "实时 · " + snapshot.optInt("live_count") + " 条" else "按班次推算",
                if (snapshot.optBoolean("live")) campusLeaf else LocalNativeColors.current.muted)
            NText(snapshot.optString("stop") + (at?.let { " · " + AcademicClock.ago(it, now.ms) } ?: ""), 12, muted = true)
        }
        academicObjects(snapshot.optJSONArray("lines")).forEachIndexed { index, line ->
            if (index > 0) NDivider()
            val eta = if (line.has("eta_min") && !line.isNull("eta_min")) line.optDouble("eta_min") else null
            val next = if (line.has("next_min") && !line.isNull("next_min")) line.optDouble("next_min") else null
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CampusColoredText(line.optString("name"), LocalNativeColors.current.brand, 22, true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NText(line.optString("from") + " → " + line.optString("to"), 14, true)
                    NText("首 " + line.optString("first") + " · 末 " + line.optString("last") + " · 约 " + line.optInt("headway") + " 分钟一班", 11, muted = true)
                }
                Column(horizontalAlignment = Alignment.End) {
                    CampusColoredText(when { eta == null -> "—"; eta <= 0 -> "即将到站"; else -> campusNumber(eta) + " 分" },
                        when { eta != null && eta <= 1 -> campusLeaf; eta != null && eta <= 8 -> LocalNativeColors.current.brand; else -> LocalNativeColors.current.ink }, 18, true)
                    NText(if (next != null) "下一班 " + campusNumber(next) + " 分" else line.optString("note"), 11, muted = true)
                }
            }
        }
        CampusFootnote(snapshot.optString("note"))
        NText("数据来源 · 以官方为准", 13, true)
        val context = LocalContext.current
        academicObjects(snapshot.optJSONArray("sources")).forEach { source ->
            NRow(source.optString("name"), source.optString("note"), "↗") { nativeOpenLink(context, source.optString("url")) }
        }
        if (snapshot.optString("official").isNotBlank()) NOpenLink("打开学校官网", snapshot.optString("official"))
    }
}

private fun campusNumber(value: Double): String = if (value % 1 == 0.0) value.toLong().toString() else value.toString()
private data class CampusEntrance(val name: String, val hint: String, val url: String, val group: String, val insecure: Boolean = false, val campusOnly: Boolean = false)

@Composable
private fun CampusEntrances() {
    val context = LocalContext.current
    listOf("学习", "缴费", "办事", "生活").forEach { group ->
        NText(group, 13, true, muted = true)
        campusEntrances.filter { it.group == group }.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { entry ->
                    val c = LocalNativeColors.current
                    val shape = RoundedRectangle(16.dp)
                    Column(Modifier.weight(1f).heightIn(min = 76.dp).clip(shape).background(c.surface)
                        .border(1.dp, c.border, shape).clickable(role = Role.Button) { nativeOpenLink(context, entry.url) }
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        NText(entry.name, 15, true)
                        NText(entry.hint, 12, muted = true)
                        if (entry.campusOnly || entry.insecure) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (entry.campusOnly) CampusTag("校内 / WebVPN", c.muted)
                                if (entry.insecure) CampusTag("HTTP", c.muted)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CampusCalendar(now: AcademicMoment) {
    val c = LocalNativeColors.current
    val position = AcademicCalendar.position(now.date)
    val term = position?.term ?: AcademicCalendar.currentOrNext(now.date)
    val progress = AcademicCalendar.progress(term, now.ms)
    NCard {
        NText(term.label, 13, true, muted = true)
        if (position != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                NText("第", 20, true); NText(position.week.toString(), 44, true); NText("周", 20, true)
                NText("/ " + term.totalWeeks, 16, muted = true)
                CampusColoredText(position.phase?.label.orEmpty(), c.brand, 13, true)
            }
        } else NText("假期中", 20, true)
        Box(Modifier.fillMaxWidth().height(42.dp).semantics { contentDescription = "学期已过 " + (progress * 100).roundToInt() + "%" }) {
            Row(Modifier.fillMaxWidth().height(30.dp).align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                term.phases.forEach { phase ->
                    Box(Modifier.weight((phase.to - phase.from + 1).toFloat()).fillMaxHeight().clip(RoundedRectangle(7.dp))
                        .background(campusPhaseColor(phase.kind).copy(alpha = .13f)).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        CampusColoredText(phase.label, campusPhaseColor(phase.kind), 11, true)
                    }
                }
            }
            Canvas(Modifier.matchParentSize()) {
                val x = size.width * progress
                drawLine(c.surface, Offset(x, 0f), Offset(x, size.height), 7.dp.toPx())
                drawLine(c.ink, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NText(AcademicClock.monthDay(term.week1Monday), 12, muted = true)
            NText(AcademicClock.monthDay(term.after.from) + " " + term.after.label, 12, muted = true)
        }
    }
    CampusSection("接下来") {
        AcademicCalendar.milestones(now.date, 6).forEach { milestone ->
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(12.dp).border(3.dp, campusPhaseColor(milestone.kind), Capsule()))
                Column(Modifier.weight(1f)) {
                    NText(milestone.label, 16, true)
                    NText(AcademicClock.monthDay(milestone.from) + if (milestone.to != milestone.from) " – " + AcademicClock.monthDay(milestone.to) else "", 12, muted = true)
                }
                NText(if (milestone.days <= 0) "进行中" else milestone.days.toString() + " 天", 14, muted = true)
            }
        }
    }
    CampusSection("本学期每周") {
        (1..term.totalWeeks).toList().chunked(5).forEach { weeks ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                weeks.forEach { week ->
                    val phase = AcademicCalendar.phase(term, week)
                    val current = position?.week == week
                    val past = position != null && week < position.week
                    val tone = campusPhaseColor(phase?.kind ?: "teaching")
                    val shape = RoundedRectangle(10.dp)
                    Column(Modifier.weight(1f).clip(shape)
                        .background(if (current) c.ink else if (past) Color.Transparent else tone.copy(alpha = .13f))
                        .then(if (past) Modifier.border(1.dp, c.border, shape) else Modifier)
                        .padding(top = 8.dp, bottom = 6.dp)
                        .semantics { contentDescription = "第 " + week + " 周，" + AcademicClock.monthDay(AcademicCalendar.monday(term, week)) + "，" + (phase?.label ?: "教学周") + if (current) "，本周" else "" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        CampusColoredText(week.toString(), if (current) c.bg else if (past) c.muted else tone, 16, true)
                        CampusColoredText(AcademicClock.slash(AcademicCalendar.monday(term, week)), if (current) c.bg else if (past) c.muted else tone, 11)
                    }
                }
                repeat(5 - weeks.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("practice" to "实践周", "teaching" to "教学周", "exam" to "考试周").forEach { (kind, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(8.dp).clip(Capsule()).background(campusPhaseColor(kind)))
                    NText(label, 12, muted = true)
                }
            }
        }
    }
    if (term.makeupDays.isNotEmpty()) CampusSection("调休") {
        term.makeupDays.forEach { makeup -> NText("• " + AcademicClock.monthDay(makeup.date) + " " + makeup.note, 14) }
    }
    NCard {
        NOpenLink("教务处《2026-2027 学年教学校历》", AcademicCalendar.sourceUrl)
        NText("发布于 2026-05-20 · 核对于 2026-10-28。法定假期按国务院办公厅安排补充。", 12, muted = true)
    }
}

@Composable
private fun campusPhaseColor(kind: String): Color = when (kind) {
    "exam" -> LocalNativeColors.current.danger
    "practice" -> LocalNativeColors.current.muted
    "break" -> campusLeaf
    "holiday", "suspended" -> Color(0xFFA27722)
    else -> LocalNativeColors.current.brand
}

private val campusLeaf = Color(0xFF397B5A)
private const val campusWebVpn = "https://webvpn.stu.edu.cn/"

private val campusDevices = listOf(
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
    CampusDevice("4GR00008477", "水电中心（名称待核）", "水电中心", 20, false),
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
