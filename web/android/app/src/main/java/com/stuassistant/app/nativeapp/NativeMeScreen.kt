package com.stuassistant.app.nativeapp

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stuassistant.app.glass.LiquidSlider
import com.stuassistant.app.glass.LiquidButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

/** Native counterpart of Me.tsx and FeedbackSettings.tsx; preserves source section order. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NativeMeScreen(store: NativeStore, s: NativeState, onConnect: () -> Unit, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val signedIn = s.token != null && !s.preview
    val bound = academicStrings(s.user?.optJSONArray("bound"))
    val sourceNames = mapOf("public" to "学校官网通知", "jw" to "教务系统", "mystu" to "MySTU")
    val sourceStates = mapOf("ok" to "正常", "maintenance" to "学校系统暂不可达", "need_login" to "登录已过期",
        "failed" to "读取失败", "keystore" to "服务器密钥异常", "running" to "正在同步", "never" to "还没同步过", "unknown" to "尚未同步")
    val hasTimetable = !s.sample && (s.timetable?.optJSONArray("courses")?.length() ?: 0) > 0
    val connected: Boolean? = if (s.user?.has("bound") == true) bound.any { it == "jw" || it == "mystu" } || hasTimetable else null
    val expired = s.sources.filter { it.optString("state") == "need_login" && it.optString("source") in bound }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = if (error is NativeApiError) error.detail else error.message ?: "没有完成，请重试" }
            finally { busy = false }
        }
    }

    NPage("我的", "账号 · 外观 · 关于") {
        if (!signedIn) {
            NCard {
                NText("登录后换成你自己的课表", 20, true)
                NText("现在看到的是一份真实课表快照和示例作业、成绩。创建账号并连接校园账号后，课表、作业、考试和成绩都会换成你的。", 16)
                NButton("登录 / 注册", onConnect, primary = true, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val email = s.user?.optString("email")?.takeIf { it.isNotBlank() }
                Box(Modifier.size(54.dp).background(LocalNativeColors.current.brand.copy(alpha = .12f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center) {
                    NText((email ?: "S").take(1).uppercase(), 24, true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NText(email ?: "已登录", 20, true)
                    val accountStatus = when (connected) {
                        true -> "已连接 ${bound.filter { it == "jw" || it == "mystu" }.joinToString("、") { sourceNames[it] ?: it }}"
                        false -> "还没有连接校园账号"
                        null -> "正在读取账号信息"
                    }
                    val host = runCatching { URI(s.server).host }.getOrNull() ?: s.server
                    NText(accountStatus + if (s.server.isNotEmpty()) " · $host" else "", 14, muted = true)
                }
            }
            if (connected == false) NativeMeConnectCard("连接校园账号", "读取你的课表、作业、考试和成绩", onConnect)
            if (expired.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NText("${expired.joinToString("、") { sourceNames[it.optString("source")] ?: it.optString("source") }}的学校登录已过期。请重新连接。", 16)
                NButton("重新连接", onConnect)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    NText("数据来源", 20, true)
                    NButton(if (s.syncing) "同步中…" else "立即同步", enabled = !s.syncing && !busy, onClick = {
                        action {
                            store.refresh("force")
                            message = "同步结束，下面是每个来源这次的结果。为了不打扰学校系统，同一来源一分钟内只同步一次。"
                        }
                    })
                }
                s.syncedAt?.let { NText("${AcademicClock.ago(it)}读取", 14, muted = true) }
                message?.let { NText(it, 14) }
                NError(s.error)
                NCard {
                    val rows = s.sources.ifEmpty { listOf("public", "jw", "mystu").map { JSONObject().put("source", it).put("state", "never") } }
                        .filter { it.optString("source") == "public" || it.optString("source") in bound }
                    rows.forEachIndexed { index, source ->
                        if (index > 0) NDivider()
                        val status = if (source.optBoolean("queued")) "排队中" else sourceStates[source.optString("state")] ?: source.optString("state")
                        val at = source.optDouble("at", Double.NaN)
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val color = when (source.optString("state")) {
                                "ok" -> androidx.compose.ui.graphics.Color(0xFF4D8B72)
                                "need_login", "failed", "keystore" -> LocalNativeColors.current.danger
                                else -> LocalNativeColors.current.muted
                            }
                            Box(Modifier.size(6.dp).background(color, androidx.compose.foundation.shape.CircleShape))
                            Box(Modifier.weight(1f)) { NText(sourceNames[source.optString("source")] ?: source.optString("source"), 16) }
                            NText(status + if (at.isFinite() && at > 0) " · ${AcademicClock.ago((at * 1000).toLong())}" else "", 14, muted = true)
                        }
                    }
                }
                if (connected == true) NativeMeConnectCard("重新连接校园账号", "教务或 MySTU 掉线时，重新登录一次就能接着读。", onConnect)
                if (s.rememberSchool) Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NText("已在这台设备记住学校密码，教务过期会自动重连。", 14, muted = true) }
                    NButton("清除", {
                        action {
                            withContext(Dispatchers.IO) { store.clearSchool() }
                            if (store.state.value.rememberSchool) throw NativeApiError("secure_storage", 0, store.state.value.error ?: "本机学校密码未能清除")
                        }
                    }, enabled = !busy)
                }
            }
        }

        NText("外观", 20, true)
        NCard {
            NText("主题", 14, true)
            NChips(listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色"), s.theme, { store.setAppearance(theme = it) })
            NText("色系", 14, true)
            NChips(listOf("sweet" to "樱粉", "classic" to "海蓝"), s.palette, { store.setAppearance(palette = it) })
            NText("跟手机系统的深浅色走，装到桌面之后也不会跳。", 14, muted = true)
        }
        NativeFeedbackSettings(store, s)
        NativeUpdateSection(store)

        NText("反馈群", 20, true)
        NCard {
            val bitmap = remember(context) {
                runCatching { context.assets.open("feedback-group.png").use { BitmapFactory.decodeStream(it) } }
                    .recoverCatching { context.assets.open("public/feedback-group.png").use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "反馈群二维码", Modifier.size(112.dp))
                else NError("反馈群二维码未能读取")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    NText("扫码进反馈群", 16, true)
                    NText("有 bug、想加功能、觉得哪条冷知识不对，或者就想骂我两句，都可以进群说。", 14, muted = true)
                }
            }
        }
        NText("隐私", 20, true)
        NCard {
            NRow("学校凭据与会话", "连接资料交给你选择的服务器，服务器会保存学校凭据及会话用于续连。本机“记住”仅控制本机加密副本。")
            NDivider()
            NRow("只读，不代办", "不提交作业、不签到、不缴费、不报修。学校作业勾选完成只记在这台设备上。")
            NDivider()
            NRow("随时删除", "删除账号会清空服务器上你的会话和缓存数据。")
        }
        NText("数据依据", 20, true)
        NCard {
            NOpenLink("节次时刻", "https://jwc.stu.edu.cn/info/1080/1939.htm")
            NText("教务处《课堂作息时间表》，与 2026–27 校历所附表一致", 14, muted = true)
            NDivider()
            NOpenLink("周次与假期", "https://jwc.stu.edu.cn/info/1080/3307.htm")
            NText("教务处 2026–2027 学年教学校历（2026-05-20 发布）", 14, muted = true)
            NDivider()
            NOpenLink("学校数据读取", "https://github.com/0error0warning/stu-mcp")
            NText("开源 stu-mcp v0.7.3，MIT 许可", 14, muted = true)
        }
        NText("个人项目，与学校官方无关。学校原文和通知始终优先。", 14, muted = true)
        if (signedIn) {
            NButton("退出登录", { action { store.logout(); onSignOut() } }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            if (confirmDelete) NCard {
                NText("删除后服务器上的会话、课表和缓存会被清空，无法恢复。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompositionLocalProvider(LocalNativeColors provides LocalNativeColors.current.let { it.copy(brand = it.danger) }) {
                        NButton(if (busy) "正在删除…" else "确认删除", {
                            action { store.deleteAccount(); confirmDelete = false; onSignOut() }
                        }, enabled = !busy, primary = true)
                    }
                    NButton("取消", { confirmDelete = false }, enabled = !busy)
                }
            } else CompositionLocalProvider(LocalNativeColors provides LocalNativeColors.current.let { it.copy(ink = it.danger) }) {
                NButton("删除账号和数据", { confirmDelete = true }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NativeMeConnectCard(title: String, subtitle: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val engine = LocalNativeFeedback.current
    val strength by rememberUpdatedState(LocalNativeStrength.current)
    LaunchedEffect(source, engine) {
        source.interactions.collect { interaction -> when (interaction) {
            is PressInteraction.Press -> engine?.play("press", strength)
            is PressInteraction.Release -> engine?.play("release", strength)
            is PressInteraction.Cancel -> engine?.cancel()
        } }
    }
    LiquidButton(onClick, LocalNativeBackdrop.current, interactionSource = source,
        modifier = Modifier.fillMaxWidth(), surfaceColor = LocalNativeColors.current.surface.copy(alpha = .5f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                NText(title, 16, true)
                NText(subtitle, 14, muted = true)
            }
            NText("›", 24, muted = true)
        }
    }
}

@Composable
private fun NativeFeedbackSettings(store: NativeStore, s: NativeState) {
    val engine = LocalNativeFeedback.current
    var strength by remember { mutableIntStateOf(s.hapticStrength) }
    var lastPreview by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(s.hapticStrength) { strength = s.hapticStrength }
    NText("触感反馈", 20, true)
    NCard {
        NRow("按键强度", trailing = if (strength == 0) "已关闭" else strength.toString())
        LiquidSlider(value = { strength.toFloat() }, onValueChange = { value ->
            val next = value.roundToInt().coerceIn(0, 100)
            if (next != strength) {
                strength = next
                store.setAppearance(strength = next)
                val now = SystemClock.elapsedRealtime()
                if (next > 0 && (lastPreview == Long.MIN_VALUE || now - lastPreview >= 60)) {
                    lastPreview = now
                    engine?.play("selection", next)
                }
            }
        }, valueRange = 0f..100f, visibilityThreshold = 1f, backdrop = LocalNativeBackdrop.current,
            modifier = Modifier.fillMaxWidth().height(56.dp).semantics {
                contentDescription = "按键强度"
                stateDescription = if (strength == 0) "已关闭" else "$strength%"
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { NText("0", 14, muted = true); NText("100", 14, muted = true) }
        NText("滑动感受力度，调到0即可关闭。", 14, muted = true)
    }
}
