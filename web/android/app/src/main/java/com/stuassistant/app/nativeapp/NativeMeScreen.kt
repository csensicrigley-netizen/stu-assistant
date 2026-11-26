package com.stuassistant.app.nativeapp

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import com.stuassistant.app.glass.liquidGlassThemeProgress
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Pure-local Me screen: local source management, appearance, privacy disclosure and local reset. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NativeMeScreen(
    store: NativeStore,
    s: NativeState,
    onConnect: () -> Unit,
    onSignOut: () -> Unit,
    onNavigate: (String) -> Unit = {},
) {
    val c = LocalNativeColors.current
    val tones = currentNativeSemanticTones()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toggleFeedback = nHapticFeedback("toggle")
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("main") }
    var exportingToday by remember { mutableStateOf(false) }
    var exportTodayError by remember { mutableStateOf<String?>(null) }
    var todayFallbackTicket by remember { mutableStateOf<NativeTodayExportTicket?>(null) }

    val bound = remember(s.boundSources, s.user) {
        s.boundSources.ifEmpty { academicStrings(s.user?.optJSONArray("bound")).toSet() }
    }
    val sourceNames = mapOf(
        "public" to "学校官网通知（公开）",
        "jw" to "本部教务（本机直连）",
        "mystu" to "MySTU（本机直连）",
        "med" to "医学院教务（本机直连）",
        "manual" to "手动导入课表（本机）",
    )
    val shortSourceNames = mapOf(
        "jw" to "教务",
        "mystu" to "MySTU",
        "med" to "医学院",
        "manual" to "手动课表",
    )
    val sourceStates = mapOf(
        "ok" to "正常",
        "maintenance" to "学校系统暂不可达",
        "need_login" to "登录已过期",
        "failed" to "读取失败",
        "running" to "正在同步",
        "never" to "还没同步过",
        "unknown" to "尚未同步",
    )
    val hasTimetable = !s.preview && !s.sample && (s.timetable?.optJSONArray("courses")?.length() ?: 0) > 0
    val connected = !s.preview && (bound.any { it in setOf("jw", "mystu", "med", "manual") } || hasTimetable)
    val expired = s.sources.filter {
        it.optString("state") == "need_login" && it.optString("source") in bound
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        actionError = null
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = formatLocalError(error)
            } finally {
                busy = false
            }
        }
    }

    BackHandler(enabled = panel != "main") {
        panel = "main"
    }

    val pageTitle = when (panel) {
        "sources" -> "课表与账号"
        "appearance" -> "外观与触感"
        "about" -> "关于 STU 助手"
        else -> "我的"
    }

    NPage(
        title = pageTitle,
        eyebrow = "",
        actions = if (panel != "main") {
            {
                NButton(
                    label = "返回",
                    onClick = { panel = "main" },
                    haptic = NativeButtonHaptic.Navigation,
                )
            }
        } else {
            null
        },
        contentKey = panel,
        contentForward = panel != "main",
    ) {
        when (panel) {
            "main" -> NativeMeOverview(
                s = s,
                connected = connected,
                hasTimetable = hasTimetable,
                exportingToday = exportingToday,
                exportTodayError = exportTodayError,
                expiredCount = expired.size,
                shortSourceNames = shortSourceNames,
                bound = bound,
                busy = busy,
                statusMessage = message,
                statusError = actionError,
                confirmReset = confirmReset,
                onConfirmReset = { confirmReset = it },
                onConnect = onConnect,
                onNavigate = onNavigate,
                onOpenPanel = { panel = it },
                onExportToday = {
                    if (exportingToday) return@NativeMeOverview
                    scope.launch {
                        exportingToday = true
                        exportTodayError = null
                        try {
                            val semester = s.timetable?.optString("semester")?.trim()?.takeIf { it.isNotBlank() }
                            val ticket = store.exportTodayTimetable(semester)
                            if (!openTodayImportDeepLink(context, ticket.deepLink)) {
                                todayFallbackTicket = ticket
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            exportTodayError = formatLocalError(e)
                        } finally {
                            exportingToday = false
                        }
                    }
                },
                onExitPreview = {
                    action {
                        store.exitPreview()
                        onSignOut()
                    }
                },
                onClearCredentials = {
                    action {
                        store.clearCampusCredentials()
                        message = "已清除本机保存的校园密码与登录会话，已缓存的课表和个人事项已保留。"
                    }
                },
                onResetAll = {
                    action {
                        store.resetAllLocalData()
                        confirmReset = false
                        onSignOut()
                    }
                },
            )

            "sources" -> {
                if (s.preview) {
                    NCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NIconBadge("shield", sizeDp = 44, iconDp = 22, filled = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                NText("当前为示例模式", 19, bold = true)
                                NText("无需注册账号，直接连接你自己的课表", 13, muted = true, maxLines = 1, softWrap = false)
                            }
                        }
                        NText("现在看到的是一份虚构示例课表和作业。连接本部校园账号、医学院教务或手动导入后，全部数据将仅保存在你的这台手机里。", 14, muted = true)
                        NButton("本地连接本部校园账号", onConnect, primary = true, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Navigation)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MeSectionHeader("其他课表来源", "refresh")
                        NativeMeTimetableSourceActions(onNavigate)
                    }
                } else {
                    NCard {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .shadow(4.dp, CircleShape, ambientColor = c.brand.copy(alpha = 0.25f), spotColor = c.brand.copy(alpha = 0.25f))
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(lerp(c.brand, Color.White, 0.16f), c.brand),
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                NativeIcon("shield", c.onBrand, size = 24.dp, strokeWidth = 2.0f)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        val identity = when {
                                            s.schoolId.isNotBlank() -> "学号 ${s.schoolId}"
                                            s.medName.isNotBlank() -> s.medName
                                            s.medUsername.isNotBlank() -> "医学院 ${s.medUsername}"
                                            else -> "纯本地模式"
                                        }
                                        NText(identity, 17, bold = true, maxLines = 1, softWrap = false)
                                    }
                                    NTag(if (connected) "本机直连" else "待连接", if (connected) tones.leaf else c.brand)
                                }
                                val boundLabels = listOf("jw", "mystu", "med", "manual")
                                    .filter { it in bound }
                                    .mapNotNull { shortSourceNames[it] }
                                NText(
                                    if (boundLabels.isNotEmpty()) "本机已连：${boundLabels.joinToString("、")}" else "尚未连接课表源 · 数据仅留在本机",
                                    13,
                                    muted = true,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }

                    if (s.availableTimetableSources.size > 1) {
                        NCard {
                            NText("当前显示的课表来源", 15, bold = true)
                            NText("你同时保存了多份本地课表，可随时切换当前在「今天」和「课表」页展示的一份。", 13, muted = true)
                            val options = s.availableTimetableSources.map { key ->
                                key to (shortSourceNames[key] ?: key)
                            }
                            NChips(
                                options = options,
                                value = s.activeTimetableSource ?: s.availableTimetableSources.first(),
                                onSelect = {
                                    store.selectTimetableSource(it)
                                    toggleFeedback()
                                },
                                haptic = "toggle",
                            )
                        }
                    }

                    if (!connected) {
                        NativeMeConnectCard("本地连接本部校园账号", "直连学校官网读取课表、作业、考试和成绩", onConnect)
                    }
                    if (expired.isNotEmpty()) {
                        val expiredSources = expired.map { it.optString("source") }
                        val needCampus = expiredSources.any { it == "jw" || it == "mystu" }
                        val needMed = "med" in expiredSources
                        NCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                NIconBadge("info", tint = c.danger)
                                Box(Modifier.weight(1f)) {
                                    NText(
                                        "${expiredSources.joinToString("、") { shortSourceNames[it] ?: it }}的本机登录会话已过期，当前显示的是上次缓存，请重新连接。",
                                        14,
                                        bold = true,
                                    )
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (needCampus) {
                                    NButton("重新连接本部账号", onConnect, primary = true, haptic = NativeButtonHaptic.Navigation)
                                }
                                if (needMed) {
                                    NButton(
                                        "重新连接医学院教务",
                                        { onNavigate("medical") },
                                        primary = !needCampus,
                                        haptic = NativeButtonHaptic.Navigation,
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                NativeIcon("refresh", c.brand, size = 18.dp, strokeWidth = 1.95f)
                                NText("数据来源", 19, bold = true, maxLines = 1, softWrap = false)
                            }
                            NButton(
                                label = if (s.syncing) "同步中…" else "立即同步",
                                enabled = !s.syncing && !busy,
                                onClick = {
                                    action {
                                        store.refresh("force")
                                        message = "本机直连同步已完成，下面是各个来源本次的状态。"
                                    }
                                },
                                haptic = NativeButtonHaptic.Submit,
                            )
                        }
                        s.syncedAt?.let { NText("${AcademicClock.ago(it)}更新", 13, muted = true, tabularNums = true) }
                        message?.let { NText(it, 13, muted = true) }
                        NError(actionError ?: s.error)
                        NCard {
                            val watched = buildList {
                                add("public")
                                listOf("jw", "mystu", "med", "manual").forEach { if (it in bound) add(it) }
                            }
                            val bySource = s.sources.associateBy { it.optString("source") }
                            val rows = watched.map { key ->
                                bySource[key] ?: JSONObject().put("source", key).put("state", if (key == "manual") "ok" else "never")
                            }
                            rows.forEachIndexed { index, source ->
                                if (index > 0) NDivider()
                                val stateCode = source.optString("state")
                                val status = sourceStates[stateCode] ?: stateCode
                                val at = source.optDouble("at", Double.NaN)
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val dotColor = when (stateCode) {
                                        "ok" -> tones.leaf
                                        "need_login", "failed" -> c.danger
                                        else -> c.muted
                                    }
                                    Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                                    Box(Modifier.weight(1f)) {
                                        NText(
                                            sourceNames[source.optString("source")] ?: source.optString("source"),
                                            15,
                                            bold = true,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    NText(
                                        text = status + if (at.isFinite() && at > 0) " · ${AcademicClock.ago((at * 1000).toLong())}" else "",
                                        size = 13,
                                        muted = stateCode == "ok" || stateCode == "never" || stateCode == "unknown",
                                        color = if (stateCode in setOf("need_login", "failed")) c.danger else null,
                                        maxLines = 1,
                                        softWrap = false,
                                        tabularNums = true,
                                    )
                                }
                            }
                        }
                        if (connected) {
                            NativeMeConnectCard("重新连接本部校园账号", "教务或 MySTU 掉线时，在本机直连重新登录即可。", onConnect)
                        }
                        if (s.rememberSchool) {
                            NCard {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        NText("已在本机 Android Keystore 加密记住校园密码，会话过期时由本机直连重连，可随时清除。", 13, muted = true)
                                    }
                                    NButton(
                                        label = "清除",
                                        onClick = {
                                            action {
                                                withContext(Dispatchers.IO) { store.clearSchool() }
                                                if (store.state.value.rememberSchool) {
                                                    throw NativeApiError("secure_storage", 0, store.state.value.error ?: "本机学校密码未能清除")
                                                }
                                                message = "已清除本机保存的校园密码。"
                                            }
                                        },
                                        enabled = !busy,
                                        haptic = NativeButtonHaptic.Destructive,
                                    )
                                }
                            }
                        }
                        NativeMeTimetableSourceActions(onNavigate)
                    }
                }
            }

            "appearance" -> {
                MeSectionHeader("外观", "palette")
                NCard {
                    NText("深浅模式", 13, bold = true, muted = true)
                    NChips(
                        options = listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                        value = s.theme,
                        onSelect = {
                            store.setAppearance(theme = it)
                            toggleFeedback()
                        },
                        haptic = "toggle",
                    )
                    NDivider()
                    NText("界面材质", 13, bold = true, muted = true)
                    NChips(
                        options = listOf("glass" to "液态玻璃", "modern" to "简约"),
                        value = s.style,
                        onSelect = {
                            store.setAppearance(style = it)
                            toggleFeedback()
                        },
                        haptic = "toggle",
                    )
                    NText("可在液态玻璃折射与纯净简约卡片之间随时切换，功能与布局完全一致。", 12, muted = true)
                    NDivider()
                    NText("校园色系", 13, bold = true, muted = true)
                    NativePaletteGrid(
                        selectedKey = s.palette,
                        onSelect = {
                            if (it != s.palette) {
                                store.setAppearance(palette = it)
                                toggleFeedback()
                            }
                        },
                    )
                    NText("六款校园色系仅改变全局色彩氛围，所有动效、手势与功能体验完全一致。", 12, muted = true)
                }
                NativeFeedbackSettings(store, s)
            }

            "about" -> {
                NativeUpdateSection(store)
                MeSectionHeader("反馈群", "chat")
                NCard {
                    val bitmap = remember(context) {
                        runCatching { context.assets.open("feedback-group.png").use { BitmapFactory.decodeStream(it) } }
                            .recoverCatching { context.assets.open("public/feedback-group.png").use { BitmapFactory.decodeStream(it) } }
                            .getOrNull()
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val qrFrameShape = RoundedRectangle(16.dp)
                        if (bitmap != null) {
                            Box(
                                Modifier
                                    .size(124.dp)
                                    .shadow(3.dp, qrFrameShape, ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.10f))
                                    .clip(qrFrameShape)
                                    .background(Color.White)
                                    .border(1.dp, c.border, qrFrameShape)
                                    .padding(6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "反馈群二维码",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.High,
                                )
                            }
                        } else {
                            NError("反馈群二维码未能读取")
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            NTag("微信扫码")
                            NText("扫码进反馈群", 17, bold = true)
                            NText("遇到问题、有新点子，或者想聊聊校园日常使用体验，随时欢迎扫码进群交流。", 13, muted = true)
                        }
                    }
                }
                MeSectionHeader("隐私", "shield")
                NCard {
                    NRow(
                        title = "本机加密存储与备份边界",
                        subtitle = "学号、可选记住的校园密码、教务/MySTU/医学院业务会话、课表、成绩、作业与个人事项仅通过 Android Keystore AES-256-GCM 加密保存在本机私有目录；已禁用系统云备份（allowBackup=false）；中央统一认证（SSO）Cookie 仅在登录瞬间驻留内存，用后即清且不落盘。",
                        leadingIcon = "shield",
                    )
                    NDivider()
                    NRow(
                        title = "直连学校官方域名",
                        subtitle = "个人课表与学业数据均由本机直接请求学校域名：https://sso.stu.edu.cn、http://jw.stu.edu.cn、https://my.stu.edu.cn、https://sso.med.stu.edu.cn、https://jwgl.med.stu.edu.cn。因本部教务 CAS 回调固定使用 HTTP，网络配置仅对 jw.stu.edu.cn 单域放行明文（不含子域），Cookie 按域严格隔离，密码仅经 HTTPS 提交。",
                        leadingIcon = "campus",
                    )
                    NDivider()
                    NRow(
                        title = "发往 stu.oyishi.asia 的公开请求",
                        subtitle = "向 stu.oyishi.asia 仅发送不带身份凭据的公开请求：公开通知、校园公交、充电桩、应用公告与版本更新检查。仅在你主动点击「导入到 today」时，通过公开导出接口临时中转剥离个人身份信息的课表快照（5 分钟失效、单次消费）。",
                        leadingIcon = "info",
                    )
                    NDivider()
                    NRow(
                        title = "只读边界与本地清理",
                        subtitle = "应用保持只读，不提交作业、不签到、不选课、不缴费。可随时在「我的」中清除校园凭据或重置本机全部数据；学校认证服务器侧仍会按校方策略留存正常登录日志。",
                        leadingIcon = "trash",
                    )
                }
                MeSectionHeader("数据依据", "book")
                NCard {
                    NOpenLink("节次时刻", "https://jwc.stu.edu.cn/info/1080/1939.htm")
                    NText("教务处《课堂作息时间表》，与 2026–27 校历所附表一致", 13, muted = true)
                    NDivider()
                    NOpenLink("周次与假期", "https://jwc.stu.edu.cn/info/1080/3307.htm")
                    NText("教务处 2026–2027 学年教学校历（2026-05-20 发布）", 13, muted = true)
                    NDivider()
                    NOpenLink("学校数据读取", "https://github.com/0error0warning/stu-mcp")
                    NText("参考开源 stu-mcp v0.7.3 协议，MIT 许可", 13, muted = true)
                }
                NText("个人项目，与学校官方无关。学校原文和通知始终优先。", 13, muted = true)
            }
        }
    }
    todayFallbackTicket?.let { ticket ->
        NativeTodayExportFallbackDialog(ticket = ticket, onDismiss = { todayFallbackTicket = null })
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NativeMeOverview(
    s: NativeState,
    connected: Boolean,
    hasTimetable: Boolean,
    exportingToday: Boolean,
    exportTodayError: String?,
    expiredCount: Int,
    shortSourceNames: Map<String, String>,
    bound: Set<String>,
    busy: Boolean,
    statusMessage: String?,
    statusError: String?,
    confirmReset: Boolean,
    onConfirmReset: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    onExportToday: () -> Unit,
    onExitPreview: () -> Unit,
    onClearCredentials: () -> Unit,
    onResetAll: () -> Unit,
) {
    val c = LocalNativeColors.current
    val tones = currentNativeSemanticTones()
    val boundLabels = listOf("jw", "mystu", "med", "manual").filter { it in bound }.mapNotNull { shortSourceNames[it] }
    val headline = when {
        s.preview -> "体验模式 · 示例数据"
        s.schoolId.isNotBlank() -> "学号 ${s.schoolId}"
        s.medName.isNotBlank() -> s.medName
        s.medUsername.isNotBlank() -> "医学院 ${s.medUsername}"
        else -> "纯本地模式"
    }
    val statusText = when {
        s.preview -> "免注册登录 · 点击连接你自己的课表"
        expiredCount > 0 -> "本机校园会话已过期，点击重连"
        boundLabels.isNotEmpty() -> "纯本地模式 · 数据仅留在本机 · 已连 ${boundLabels.joinToString(" / ")}"
        else -> "纯本地模式 · 数据仅留在本机"
    }

    val profileInteraction = remember { MutableInteractionSource() }
    val profilePressed by profileInteraction.collectIsPressedAsState()
    val profileScale = rememberPressScale(profilePressed, NativeMotion.PressRow)
    val avatarShape = RoundedCornerShape(22.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .nativeScale(profileScale)
            .clip(RoundedCornerShape(22.dp))
            .clickable(
                interactionSource = profileInteraction,
                indication = null,
                role = Role.Button,
            ) {
                if (s.preview || (!connected && expiredCount == 0)) {
                    onConnect()
                } else {
                    onOpenPanel("sources")
                }
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(68.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = avatarShape,
                    ambientColor = c.brand.copy(alpha = 0.22f),
                    spotColor = c.brand.copy(alpha = 0.28f),
                )
                .clip(avatarShape)
                .background(
                    Brush.linearGradient(
                        listOf(lerp(c.brand, Color.White, 0.14f), c.brand),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            NativeIcon(if (s.preview) "person" else "shield", c.onBrand, size = 30.dp, strokeWidth = 2.0f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NText(
                text = headline,
                size = 20,
                bold = true,
                maxLines = 1,
                softWrap = false,
            )
            NText(statusText, 13, muted = true, maxLines = 2)
        }
        when {
            s.preview -> NTag("连接", c.brand, filled = true)
            expiredCount > 0 -> NTag("需重连", c.danger)
            connected -> NTag("纯本地", tones.leaf)
            else -> NativeIcon("chevron-right", c.muted, size = 18.dp)
        }
    }

    val themeName = when (s.theme) {
        "light" -> "浅色"
        "dark" -> "深色"
        else -> "跟随系统"
    }
    val paletteName = NATIVE_PALETTES.firstOrNull { it.key == s.palette }?.label ?: "海蓝"
    val styleName = if (s.style == "modern") "简约" else "液态玻璃"

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NText("我的课表", 14, bold = true, muted = true)
        NCard {
            NRow(
                title = "课表与账号",
                subtitle = if (connected) {
                    s.syncedAt?.let { "${AcademicClock.ago(it)}本机同步" } ?: "管理本地课表源与直连状态"
                } else {
                    "本机直连教务与 MySTU，数据仅留本机"
                },
                leadingIcon = "refresh",
                onClick = { onOpenPanel("sources") },
            )
            NDivider()
            NRow(
                title = "手动导入课表",
                subtitle = "离线粘贴文字、导入 CSV 或逐条添加",
                leadingIcon = "spark",
                onClick = { onNavigate("import") },
            )
            NDivider()
            NRow(
                title = "医学院教务",
                subtitle = "本机直连医学院教务同步课表",
                leadingIcon = "shield",
                onClick = { onNavigate("medical") },
            )
            if (hasTimetable) {
                NDivider()
                NRow(
                    title = if (exportingToday) "正在生成 today 导入码…" else "导入到 today",
                    subtitle = "发送脱敏课表换取 5 分钟一次性导入码（不含账号）",
                    leadingIcon = "external",
                    onClick = onExportToday,
                )
            }
        }
        NError(exportTodayError)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        NText("偏好与支持", 14, bold = true, muted = true)
        NCard {
            NRow(
                title = "外观与触感",
                subtitle = "$themeName · $paletteName · $styleName",
                leadingIcon = "palette",
                onClick = { onOpenPanel("appearance") },
            )
            NDivider()
            NRow(
                title = "关于 STU 助手",
                subtitle = "版本更新 · 反馈群 · 隐私与数据说明",
                leadingIcon = "info",
                onClick = { onOpenPanel("about") },
            )
        }
    }

    statusMessage?.let { NText(it, 13, muted = true) }
    NError(statusError)

    if (s.preview) {
        NButton(
            label = "退出示例，进入纯本地模式",
            onClick = onExitPreview,
            enabled = !busy,
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            haptic = NativeButtonHaptic.Navigation,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton(
                label = "清除校园登录凭据",
                onClick = onClearCredentials,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                haptic = NativeButtonHaptic.Destructive,
            )
            if (confirmReset) {
                NCard {
                    NText("将彻底清空本机保存的课表、作业、考试、成绩、个人事项、计划与校园登录凭据，且无法恢复。", 14, bold = true)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompositionLocalProvider(LocalNativeColors provides c.copy(brand = c.danger)) {
                            NButton(
                                label = if (busy) "正在重置…" else "确认重置",
                                onClick = onResetAll,
                                enabled = !busy,
                                primary = true,
                                haptic = NativeButtonHaptic.Destructive,
                            )
                        }
                        NButton(
                            label = "取消",
                            onClick = { onConfirmReset(false) },
                            enabled = !busy,
                            haptic = NativeButtonHaptic.Utility,
                        )
                    }
                }
            } else {
                CompositionLocalProvider(LocalNativeColors provides c.copy(ink = c.danger)) {
                    NButton(
                        label = "重置本机全部数据",
                        onClick = { onConfirmReset(true) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        haptic = NativeButtonHaptic.Utility,
                    )
                }
            }
        }
    }
}

@Composable
internal fun MeSectionHeader(title: String, icon: String? = null) {
    val c = LocalNativeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            NativeIcon(icon, c.brand, size = 18.dp, strokeWidth = 1.95f)
        }
        NText(title, 19, bold = true, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun NativeMeTimetableSourceActions(onNavigate: (String) -> Unit) {
    NativeMeConnectCard(
        title = "用医学院教务账号本机直连同步",
        subtitle = "本机直连医学院教务读取课表；密码仅直连校方，不上传服务器",
        icon = "shield",
        onClick = { onNavigate("medical") },
    )
    NativeMeConnectCard(
        title = "手动导入课表（完全离线）",
        subtitle = "不用连账号：粘文字、导 CSV 或一条条加，全部存在本机",
        icon = "spark",
        onClick = { onNavigate("import") },
    )
}

@Composable
private fun NativeMeConnectCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: String = "shield",
) {
    val c = LocalNativeColors.current
    val shape = RoundedRectangle(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .nativeGlassCard(20)
            .nativeCardAction(
                onClick = onClick,
                haptic = NativeButtonHaptic.Navigation,
                shape = shape,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NIconBadge(icon, tint = c.brand)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NText(title, 15, bold = true, maxLines = 1, softWrap = false)
            NText(subtitle, 13, muted = true)
        }
        NativeIcon("chevron-right", c.muted, size = 18.dp)
    }
}

@Composable
internal fun NativeFeedbackSettings(store: NativeStore, s: NativeState) {
    val engine = LocalNativeFeedback.current
    var draft by remember { mutableFloatStateOf(s.hapticStrength.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(s.hapticStrength) { if (!editing) draft = s.hapticStrength.toFloat() }
    val strength = draft.roundToInt().coerceIn(0, 100)
    MeSectionHeader("触感反馈", "wave")
    NCard {
        NRow("触感强度", trailing = if (strength == 0) "已关闭" else strength.toString())
        NativeStyledSlider(
            value = { draft },
            onValueChange = { value ->
                editing = true
                draft = value.coerceIn(0f, 100f)
                if (draft.roundToInt() == 0) engine?.cancel()
            },
            onValueChangeFinished = {
                val committed = draft.roundToInt().coerceIn(0, 100)
                draft = committed.toFloat()
                store.setAppearance(strength = committed)
                editing = false
                if (committed == 0) engine?.cancel() else engine?.play("preview", committed)
            },
            onValueChangeCancelled = {
                draft = store.state.value.hapticStrength.toFloat()
                editing = false
            },
            valueRange = 0f..100f,
            visibilityThreshold = .01f,
            backdrop = LocalNativeBackdrop.current,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .semantics {
                    contentDescription = "触感强度"
                    stateDescription = if (strength == 0) "已关闭" else "$strength%"
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            NText("0", 13, muted = true, tabularNums = true)
            NText("100", 13, muted = true, tabularNums = true)
        }
        NText("松手试听，0 为关闭。", 13, muted = true)
    }
}

@Composable
private fun NativePaletteGrid(selectedKey: String, onSelect: (String) -> Unit) {
    val c = LocalNativeColors.current
    val progress = liquidGlassThemeProgress()
    val reduced = LocalLiquidGlassReducedMotion.current
    Column(
        Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NATIVE_PALETTES.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    val active = option.key == selectedKey
                    val itemScale by animateFloatAsState(
                        targetValue = if (active) 1f else 0.985f,
                        animationSpec = if (reduced) snap() else NativeMotion.pop(0.001f),
                        label = "palette-item-scale",
                    )
                    val cardShape = RoundedRectangle(16.dp)
                    val bgColor = if (active) {
                        c.brand.copy(alpha = 0.11f + 0.07f * progress)
                    } else {
                        c.bg.copy(alpha = 0.65f - 0.10f * progress)
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 58.dp)
                            .graphicsLayer { scaleX = itemScale; scaleY = itemScale }
                            .clip(cardShape)
                            .background(bgColor)
                            .border(if (active) 1.5.dp else 1.dp, if (active) c.brand else c.border, cardShape)
                            .selectable(selected = active, role = Role.RadioButton) { onSelect(option.key) }
                            .semantics {
                                selected = active
                                contentDescription = "${option.label}（${option.subtitle}）"
                            }
                            .padding(horizontal = 11.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(Capsule())
                                .background(option.swatchPaper)
                                .border(1.dp, Color.Black.copy(alpha = 0.08f), Capsule()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy((-4).dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(13.dp).clip(CircleShape).background(option.swatchBrand))
                                Box(Modifier.size(11.dp).clip(CircleShape).background(option.swatchAccent))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            NText(option.label, 14, bold = true, maxLines = 1, softWrap = false)
                            NText(option.subtitle, 11, muted = true, maxLines = 1, softWrap = false)
                        }
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            if (active) {
                                NativeIcon("check", c.brand, size = 15.dp, strokeWidth = 2.2f)
                            }
                        }
                    }
                }
            }
        }
    }
}
