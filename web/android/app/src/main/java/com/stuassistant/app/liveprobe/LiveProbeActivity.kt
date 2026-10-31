package com.stuassistant.app.liveprobe

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** A separate local-only launcher. No account, NativeStore, WebView, or backend is used. */
class LiveProbeActivity : ComponentActivity() {
    private lateinit var probe: LiveUpdateProbe
    private var foreground by mutableStateOf(false)
    private var refreshToken by mutableIntStateOf(0)
    private var notice by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notice = if (granted) "通知权限已授予" else "通知权限未授予，可在系统通知设置中开启"
        refreshToken++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        probe = LiveUpdateProbe(this)
        setContent { ProbePage() }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        refreshToken++
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSettings(probe.notificationSettingsIntent())
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: Exception) {
            notice = "无法打开系统设置：${error.message ?: error.javaClass.simpleName}"
        }
    }

    @Composable
    private fun ProbePage() {
        val dark = isSystemInDarkTheme()
        val colors = remember(dark) { ProbeColors(dark) }
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        var snapshot by remember { mutableStateOf<ProbeSnapshot?>(null) }
        var readError by remember { mutableStateOf<String?>(null) }
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        val readSnapshot: () -> Unit = {
            try {
                snapshot = probe.snapshot()
                readError = null
            } catch (error: Exception) {
                readError = "状态读取失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
        // Promotion is asynchronous on some OEM builds. Observe briefly after a post
        // or a settings round-trip, then track dismissal/expiry while still foreground.
        // No polling or notification updates run in background.
        LaunchedEffect(foreground, refreshToken) {
            if (foreground) {
                repeat(9) {
                    readSnapshot()
                    if (it < 8) delay(400)
                }
                while (snapshot?.active == true) {
                    delay(2_000)
                    readSnapshot()
                }
            }
        }
        LaunchedEffect(foreground, snapshot?.endsAt, snapshot?.active) {
            if (foreground && snapshot?.active == true && snapshot?.endsAt != null) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
        }
        val runtimePermission = remember(foreground, refreshToken) {
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        val perform: (() -> ProbeSnapshot) -> Unit = { action ->
            try {
                snapshot = action()
                readError = null
                notice = null
                now = System.currentTimeMillis()
            } catch (error: Exception) {
                notice = "操作失败：${error.message ?: error.javaClass.simpleName}"
            }
            refreshToken++
        }

        CompositionLocalProvider(LocalProbeColors provides colors) {
            Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .systemBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProbeText("STU 实时通知验证", size = 29, bold = true, modifier = Modifier.semantics { heading() })
                        ProbeText("本机验证 · 无需登录", muted = true)
                        ProbeText(snapshot?.device ?: "正在读取设备信息…", muted = true)
                        ProbeText("Android ${Build.VERSION.RELEASE} · API ${snapshot?.sdk ?: Build.VERSION.SDK_INT}", muted = true)
                    }

                    ProbeCard {
                        ProbeText("1  允许通知", size = 20, bold = true)
                        ProbeStatus("发送权限", if (runtimePermission) "已授予" else "未授予", runtimePermission)
                        ProbeStatus("应用通知", enabledLabel(snapshot?.notificationsEnabled), snapshot?.notificationsEnabled)
                        ProbeStatus("测试通知渠道", enabledLabel(snapshot?.channelEnabled), snapshot?.channelEnabled)
                        ProbeButton(
                            if (!runtimePermission) "允许发送通知" else "应用通知设置",
                            "probe_notification_permission",
                            primary = !runtimePermission,
                            onClick = ::requestNotifications,
                        )
                        ProbeButton("实时通知 / 提升设置", "probe_promotion_settings") {
                            openSettings(probe.promotionSettingsIntent())
                        }
                    }

                    ProbeCard {
                        ProbeText("2  发送测试", size = 20, bold = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProbeButton("进度测试", "probe_start_progress", Modifier.weight(1f), primary = true) {
                                perform { probe.start(ProbeKind.PROGRESS) }
                            }
                            ProbeButton("3 分钟倒计时", "probe_start_countdown", Modifier.weight(1f), primary = true) {
                                perform { probe.start(ProbeKind.COUNTDOWN) }
                            }
                        }
                        val current = snapshot
                        when (current?.kind) {
                            ProbeKind.PROGRESS -> {
                                ProbeText("测试进度  ${current.progress}%", size = 22, bold = true)
                                ProbeProgress(current.progress)
                            }
                            ProbeKind.COUNTDOWN -> {
                                val seconds = current.endsAt?.let { ceil((it - now).coerceAtLeast(0) / 1_000.0).toLong() }
                                val timer = seconds?.let { "%d:%02d".format(it / 60, it % 60) } ?: "—"
                                ProbeText(if (current.active) "距离结束  $timer" else "倒计时已结束", size = 26, bold = true)
                            }
                            null -> ProbeText("选择一种测试后，返回桌面观察系统胶囊。", muted = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProbeButton(
                                "推进进度", "probe_advance", Modifier.weight(1f),
                                enabled = current?.active == true && current.kind == ProbeKind.PROGRESS,
                            ) { perform { probe.advance() } }
                            ProbeButton("结束测试", "probe_finish", Modifier.weight(1f), enabled = current?.active == true) {
                                perform { probe.finish() }
                            }
                        }
                        ProbeText(if (Build.VERSION.SDK_INT >= 26) "测试通知最长保留 10 分钟，可随时结束。" else "此系统需手动结束测试通知。", muted = true)
                    }

                    ProbeCard {
                        ProbeText("3  查看系统状态", size = 20, bold = true)
                        ProbeStatus("提升权限", when (snapshot?.promotionAllowed) {
                            true -> "系统允许"
                            false -> "系统未允许"
                            null -> if ((snapshot?.sdk ?: Build.VERSION.SDK_INT) < 36) "系统版本不支持" else "未提供结果"
                        }, snapshot?.promotionAllowed)
                        ProbeStatus("可提升特征", when (snapshot?.eligible) {
                            true -> "符合"
                            false -> "不符合"
                            null -> "尚无结果"
                        }, snapshot?.eligible)
                        ProbeStatus("系统活动通知", when (snapshot?.active) {
                            true -> "存在"
                            false -> "未发现"
                            null -> "正在读取"
                        }, snapshot?.active)
                        ProbeStatus("实际提升标志", when (snapshot?.promoted) {
                            true -> "已设置"
                            false -> "未设置"
                            null -> "尚无结果"
                        }, snapshot?.promoted)
                        ProbeText("标志不等于肉眼可见的胶囊。请回到桌面或其他应用，观察通知栏与顶部胶囊。", muted = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProbeButton("刷新状态", "probe_refresh", Modifier.weight(1f)) { refreshToken++ }
                            ProbeButton("复制诊断", "probe_copy_diagnostics", Modifier.weight(1f)) {
                                try {
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                        ClipData.newPlainText("STU 实时通知验证", probe.diagnosticText()),
                                    )
                                    notice = "诊断信息已复制"
                                } catch (error: Exception) {
                                    notice = "复制失败：${error.message ?: error.javaClass.simpleName}"
                                }
                            }
                        }
                        snapshot?.lastAction?.takeIf { it.isNotBlank() }?.let {
                            ProbeText("最近操作：$it", muted = true)
                        }
                    }

                    val error = readError ?: snapshot?.lastError
                    if (!error.isNullOrBlank()) ProbeCard {
                        ProbeText("操作提示", bold = true)
                        SelectionContainer { ProbeText(error, color = colors.error) }
                    }
                    notice?.let { ProbeText(it, modifier = Modifier.testTag("probe_notice")) }
                }
            }
        }
    }
}

private class ProbeColors(dark: Boolean) {
    val background = if (dark) Color(0xFF18161D) else Color(0xFFFFF0F1)
    val surface = if (dark) Color(0xFF24212B) else Color(0xFFFFFCFC)
    val ink = if (dark) Color(0xFFF4EDF7) else Color(0xFF35212D)
    val muted = if (dark) Color(0xFFC4B7C8) else Color(0xFF76636D)
    val border = if (dark) Color(0xFF49414F) else Color(0xFFE8DCE0)
    val brand = if (dark) Color(0xFFC2B2FF) else Color(0xFF6756B9)
    val onBrand = if (dark) Color(0xFF291C50) else Color.White
    val error = if (dark) Color(0xFFFFADB6) else Color(0xFFAB3A47)
}

private val LocalProbeColors = staticCompositionLocalOf { ProbeColors(false) }
private fun enabledLabel(value: Boolean?): String = when (value) {
    true -> "已开启"
    false -> "已关闭"
    null -> "正在读取"
}

@Composable
private fun ProbeText(
    text: String, modifier: Modifier = Modifier, size: Int = 16, bold: Boolean = false,
    muted: Boolean = false, color: Color? = null,
) {
    val colors = LocalProbeColors.current
    BasicText(text, modifier, style = TextStyle(
        color = color ?: if (muted) colors.muted else colors.ink,
        fontSize = size.sp, lineHeight = (size * 1.45f).sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    ))
}

@Composable
private fun ProbeCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalProbeColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.border, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
private fun ProbeStatus(label: String, value: String, good: Boolean?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ProbeText(label, Modifier.weight(1f), muted = true)
        ProbeText(value, color = if (good == true) LocalProbeColors.current.brand else null, bold = good == true)
    }
}

@Composable
private fun ProbeButton(
    label: String, tag: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    primary: Boolean = false, onClick: () -> Unit,
) {
    val colors = LocalProbeColors.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.heightIn(min = 52.dp).fillMaxWidth().clip(shape)
            .background(if (primary) colors.brand.copy(alpha = if (enabled) 1f else .35f) else colors.background)
            .border(1.dp, if (primary) Color.Transparent else colors.border, shape)
            .testTag(tag).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ProbeText(label, bold = true, color = (if (primary) colors.onBrand else colors.ink).copy(alpha = if (enabled) 1f else .45f))
    }
}

@Composable
private fun ProbeProgress(value: Int) {
    val colors = LocalProbeColors.current
    Box(
        Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.border)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0, 100).toFloat(), 0f..100f) },
    ) {
        Box(Modifier.fillMaxWidth(value.coerceIn(0, 100) / 100f).fillMaxHeight().background(colors.brand))
    }
}
