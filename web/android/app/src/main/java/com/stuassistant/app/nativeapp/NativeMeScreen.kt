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
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Native counterpart of Me.tsx and FeedbackSettings.tsx with grouped overview and sub-panels. */
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
    var confirmDelete by remember { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf("main") }
    var exportingToday by remember { mutableStateOf(false) }
    var exportTodayError by remember { mutableStateOf<String?>(null) }
    var todayFallbackTicket by remember { mutableStateOf<NativeTodayExportTicket?>(null) }
    val signedIn = s.token != null && !s.preview
    val bound = academicStrings(s.user?.optJSONArray("bound"))
    val sourceNames = mapOf("public" to "学校官网通知", "jw" to "教务系统", "mystu" to "MySTU")
    val sourceStates = mapOf(
        "ok" to "正常",
        "maintenance" to "学校系统暂不可达",
        "need_login" to "登录已过期",
        "failed" to "读取失败",
        "keystore" to "服务器密钥异常",
        "running" to "正在同步",
        "never" to "还没同步过",
        "unknown" to "尚未同步",
    )
    val hasTimetable = !s.sample && (s.timetable?.optJSONArray("courses")?.length() ?: 0) > 0
    val connected: Boolean? = if (s.user?.has("bound") == true) {
        bound.any { it == "jw" || it == "mystu" } || hasTimetable
    } else {
        null
    }
    val expired = s.sources.filter {
        it.optString("state") == "need_login" && it.optString("source") in bound
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = if (error is NativeApiError) error.detail else error.message ?: "没有完成，请重试"
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
                signedIn = signedIn,
                connected = connected,
                hasTimetable = hasTimetable,
                exportingToday = exportingToday,
                exportTodayError = exportTodayError,
                expiredCount = expired.size,
                sourceNames = sourceNames,
                bound = bound,
                busy = busy,
                confirmDelete = confirmDelete,
                onConfirmDelete = { confirmDelete = it },
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
                            exportTodayError = when ((e as? NativeApiError)?.code) {
                                "network", "timeout" -> "服务器没有及时响应，检查网络后再试。"
                                "invalid_token", "token_revoked" -> "登录状态已失效，请重新登录。"
                                "not_signed_in" -> "请先登录助手账号后再操作。"
                                "no_timetable" -> "当前还没有可导出的课表，请先同步或手动导入课表。"
                                "too_many_attempts" -> "操作太频繁了，请稍后再试。"
                                else -> (e as? NativeApiError)?.detail?.ifEmpty { null } ?: e.message ?: "生成导入码失败，请重试。"
                            }
                        } finally {
                            exportingToday = false
                        }
                    }
                },
                onLogout = { action { store.logout(); onSignOut() } },
                onDeleteAccount = {
                    action {
                        store.deleteAccount()
                        confirmDelete = false
                        onSignOut()
                    }
                },
            )

            "sources" -> {
                if (!signedIn) {
                    NCard {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NIconBadge("person", sizeDp = 44, iconDp = 22, filled = true)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                NText("登录后换成你自己的课表", 19, bold = true)
                                NText("连接教务与 MySTU，实时跟进课程与死线", 13, muted = true, maxLines = 1, softWrap = false)
                            }
                        }
                        NText("现在看到的是一份虚构示例课表和示例作业、成绩。创建账号并连接校园账号后，课表、作业、考试和成绩都会换成你的。", 14, muted = true)
                        NButton("登录 / 注册", onConnect, primary = true, modifier = Modifier.fillMaxWidth(), haptic = NativeButtonHaptic.Navigation)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MeSectionHeader("数据来源", "refresh")
                        NativeMeTimetableSourceActions(onNavigate)
                    }
                } else {
                    NCard {
                        val email = s.user?.optString("email")?.takeIf { it.isNotBlank() }
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
                                NText(
                                    (email ?: "S").take(1).uppercase(),
                                    22,
                                    bold = true,
                                    color = c.onBrand,
                                    maxLines = 1,
                                    softWrap = false,
                                    align = TextAlign.Center,
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        NText(email ?: "已登录", 17, bold = true, maxLines = 1, softWrap = false)
                                    }
                                    when (connected) {
                                        true -> NTag("已连接", tones.leaf)
                                        false -> NTag("待绑定", c.brand)
                                        null -> Unit
                                    }
                                }
                                val accountStatus = when (connected) {
                                    true -> "已绑定 ${bound.filter { it == "jw" || it == "mystu" }.joinToString("、") { sourceNames[it] ?: it }}"
                                    false -> "还没有连接校园账号"
                                    null -> "正在读取账号信息"
                                }
                                val host = runCatching { URI(s.server).host }.getOrNull() ?: s.server
                                NText(
                                    accountStatus + if (s.server.isNotEmpty()) " · $host" else "",
                                    13,
                                    muted = true,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                    if (connected == false) {
                        NativeMeConnectCard("连接校园账号", "读取你的课表、作业、考试和成绩", onConnect)
                    }
                    if (expired.isNotEmpty()) {
                        NCard {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                NIconBadge("info", tint = c.danger)
                                Box(Modifier.weight(1f)) {
                                    NText(
                                        "${expired.joinToString("、") { sourceNames[it.optString("source")] ?: it.optString("source") }}的学校登录已过期，请重新连接。",
                                        14,
                                        bold = true,
                                    )
                                }
                            }
                            NButton("重新连接", onConnect, primary = true, haptic = NativeButtonHaptic.Navigation)
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
                                        message = "同步结束，下面是每个来源这次的结果。为了不打扰学校系统，同一来源一分钟内只同步一次。"
                                    }
                                },
                                haptic = NativeButtonHaptic.Submit,
                            )
                        }
                        s.syncedAt?.let { NText("${AcademicClock.ago(it)}读取", 13, muted = true, tabularNums = true) }
                        message?.let { NText(it, 13, muted = true) }
                        NError(s.error)
                        NCard {
                            val rows = s.sources
                                .ifEmpty { listOf("public", "jw", "mystu").map { JSONObject().put("source", it).put("state", "never") } }
                                .filter { it.optString("source") == "public" || it.optString("source") in bound }
                            rows.forEachIndexed { index, source ->
                                if (index > 0) NDivider()
                                val stateCode = source.optString("state")
                                val status = if (source.optBoolean("queued")) "排队中" else sourceStates[stateCode] ?: stateCode
                                val at = source.optDouble("at", Double.NaN)
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    val dotColor = when (stateCode) {
                                        "ok" -> tones.leaf
                                        "need_login", "failed", "keystore" -> c.danger
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
                                        color = if (stateCode in setOf("need_login", "failed", "keystore")) c.danger else null,
                                        maxLines = 1,
                                        softWrap = false,
                                        tabularNums = true,
                                    )
                                }
                            }
                        }
                        if (connected == true) {
                            NativeMeConnectCard("重新连接校园账号", "教务或 MySTU 掉线时，重新登录一次就能接着读。", onConnect)
                        }
                        if (s.rememberSchool) {
                            NCard {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        NText("已在这台设备记住学校密码，教务过期会自动重连。", 13, muted = true)
                                    }
                                    NButton(
                                        label = "清除",
                                        onClick = {
                                            action {
                                                withContext(Dispatchers.IO) { store.clearSchool() }
                                                if (store.state.value.rememberSchool) {
                                                    throw NativeApiError("secure_storage", 0, store.state.value.error ?: "本机学校密码未能清除")
                                                }
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
                        title = "学校凭据与会话",
                        subtitle = "连接资料交给你选择的服务器，服务器会保存学校凭据及会话用于续连。本机“记住”仅控制本机加密副本。",
                        leadingIcon = "shield",
                    )
                    NDivider()
                    NRow(
                        title = "只读，不代办",
                        subtitle = "不提交作业、不签到、不缴费、不报修。学校作业勾选完成只记在这台设备上。",
                        leadingIcon = "check",
                    )
                    NDivider()
                    NRow(
                        title = "随时删除",
                        subtitle = "删除账号会清空服务器上你的会话和缓存数据。",
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
                    NText("开源 stu-mcp v0.7.3，MIT 许可", 13, muted = true)
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
    signedIn: Boolean,
    connected: Boolean?,
    hasTimetable: Boolean,
    exportingToday: Boolean,
    exportTodayError: String?,
    expiredCount: Int,
    sourceNames: Map<String, String>,
    bound: List<String>,
    busy: Boolean,
    confirmDelete: Boolean,
    onConfirmDelete: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenPanel: (String) -> Unit,
    onExportToday: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    val c = LocalNativeColors.current
    val tones = currentNativeSemanticTones()
    val email = s.user?.optString("email")?.takeIf { it.isNotBlank() }
    val statusText = when {
        !signedIn -> "未登录 · 示例数据"
        expiredCount > 0 -> "登录已过期，点击重连"
        connected == true -> "已绑定 ${bound.filter { it == "jw" || it == "mystu" }.joinToString("、") { sourceNames[it] ?: it }}"
        connected == false -> "未绑定校园账号"
        else -> "正在读取状态"
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
                if (!signedIn || connected == false || expiredCount > 0) {
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
            if (signedIn && email != null) {
                NText(
                    email.take(1).uppercase(),
                    28,
                    bold = true,
                    color = c.onBrand,
                    align = TextAlign.Center,
                )
            } else {
                NativeIcon("person", c.onBrand, size = 30.dp, strokeWidth = 2.0f)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NText(
                text = if (signedIn) (email ?: "我的账号") else "连接我的校园",
                size = 20,
                bold = true,
                maxLines = 1,
                softWrap = false,
            )
            NText(statusText, 13, muted = true, maxLines = 1, softWrap = false)
        }
        if (!signedIn) {
            NTag("去登录", c.brand, filled = true)
        } else if (expiredCount > 0) {
            NTag("需重连", c.danger)
        } else if (connected == true) {
            NTag("已连接", tones.leaf)
        } else {
            NativeIcon("chevron-right", c.muted, size = 18.dp)
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
                subtitle = if (signedIn) {
                    s.syncedAt?.let { "${AcademicClock.ago(it)}同步" } ?: "同步状态与绑定设置"
                } else {
                    "连接教务与 MySTU 自动同步"
                },
                leadingIcon = "refresh",
                onClick = { onOpenPanel("sources") },
            )
            NDivider()
            NRow(
                title = "手动导入课表",
                subtitle = "粘贴文字、导入 CSV 或逐条添加",
                leadingIcon = "spark",
                onClick = { onNavigate("import") },
            )
            NDivider()
            NRow(
                title = "医学院教务",
                subtitle = "用医学院账号同步课表与考试",
                leadingIcon = "shield",
                onClick = { onNavigate("medical") },
            )
            if (signedIn && hasTimetable) {
                NDivider()
                NRow(
                    title = if (exportingToday) "正在生成 today 导入码…" else "导入到 today",
                    subtitle = "一键同步当前课表到 today 安排表",
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
                subtitle = "版本更新 · 反馈群 · 隐私与数据依据",
                leadingIcon = "info",
                onClick = { onOpenPanel("about") },
            )
        }
    }

    if (signedIn) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton(
                label = "退出登录",
                onClick = onLogout,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                haptic = NativeButtonHaptic.Destructive,
            )
            if (confirmDelete) {
                NCard {
                    NText("删除后服务器上的会话、课表和缓存会被清空，无法恢复。", 14, bold = true)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompositionLocalProvider(LocalNativeColors provides c.copy(brand = c.danger)) {
                            NButton(
                                label = if (busy) "正在删除…" else "确认删除",
                                onClick = onDeleteAccount,
                                enabled = !busy,
                                primary = true,
                                haptic = NativeButtonHaptic.Destructive,
                            )
                        }
                        NButton(
                            label = "取消",
                            onClick = { onConfirmDelete(false) },
                            enabled = !busy,
                            haptic = NativeButtonHaptic.Utility,
                        )
                    }
                }
            } else {
                CompositionLocalProvider(LocalNativeColors provides c.copy(ink = c.danger)) {
                    NButton(
                        label = "删除账号和数据",
                        onClick = { onConfirmDelete(true) },
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
        title = "用医学院教务账号自动同步",
        subtitle = "连一次，课表成绩考试都自己来；密码只用一次，不保存",
        icon = "shield",
        onClick = { onNavigate("medical") },
    )
    NativeMeConnectCard(
        title = "手动导入课表",
        subtitle = "不想连账号，或者那边密码改过：粘文字、导 CSV 或一条条加",
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
