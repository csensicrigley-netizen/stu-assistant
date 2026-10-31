package com.stuassistant.app.liveprobe

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.stuassistant.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Identical single-scene UI for the two app-id builds. No background observer or poster. */
class VivoIdentityProbeActivity : ComponentActivity() {
    private lateinit var probe: VivoIslandProbe
    private var foreground by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var notice by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notice = if (granted) "已允许通知，请开始测试" else "需要通知权限才能发送测试"
        revision++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        probe = VivoIslandProbe(this)
        setContent { Page() }
    }

    override fun onResume() { super.onResume(); foreground = true; revision++ }
    override fun onPause() { foreground = false; super.onPause() }

    private fun openSettings(promoted: Boolean) {
        try {
            val settings = LiveUpdateProbe(this)
            val intent: Intent = if (promoted) settings.promotionSettingsIntent() else settings.notificationSettingsIntent()
            startActivity(intent)
        } catch (error: Exception) {
            notice = "无法打开设置：${error.javaClass.simpleName}"
        }
    }

    private fun diagnostics(): String {
        val state = probe.snapshot()
        return buildString {
            appendLine(getString(R.string.live_probe_title))
            appendLine("identityPackage=$packageName; scene=NAVIGATION; mode=SUPERX_NAVIGATION")
            appendLine("capturedAtEpochMs=${System.currentTimeMillis()}; elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            appendLine("startedAt=${state.startedAt}; expiresAt=${state.expiresAt}")
            appendLine("rawActiveCount=${state.rawActive.size}; strictSessionMatch=${state.current != null}")
            appendLine("未匹配本会话不等于系统无通知；字段保留和API返回均不代替肉眼确认原子岛。")
            appendLine("--- 原始活动通知列表（本包）---")
            if (state.rawActive.isEmpty()) {
                appendLine(VivoProbeDiagnostics.capture(this@VivoIdentityProbeActivity, null))
            } else {
                state.rawActive.forEachIndexed { index, notification ->
                    appendLine("--- raw[$index] ---")
                    appendLine(VivoProbeDiagnostics.capture(this@VivoIdentityProbeActivity, notification))
                }
            }
            appendLine("--- 请求、渠道与会话历史 ---")
            appendLine(probe.historyText())
            appendLine("--- 当前包 NAVIGATION 只读查询 ---")
            val manager = getSystemService(NotificationManager::class.java)
            appendLine("selfPackageNavigationStatus=" + if (manager == null) "unknown(no manager)"
                else VivoSuperXPayload.readStatus(manager, packageName, "NAVIGATION"))
            append("本包只手动投递；无前台服务、网络请求、通知监听、场景名单写入或后台重发。")
        }
    }

    @Composable
    private fun Page() {
        val dark = isSystemInDarkTheme()
        val palette = remember(dark) { IdentityColors(dark) }
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        var state by remember { mutableStateOf(probe.snapshot()) }
        var capturedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var capturedElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
        var switching by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        fun refresh() {
            state = probe.snapshot()
            capturedAt = System.currentTimeMillis()
            capturedElapsed = SystemClock.elapsedRealtime()
        }
        LaunchedEffect(foreground, revision) {
            if (foreground) {
                repeat(9) { index -> refresh(); if (index < 8) delay(400) }
                while (true) { delay(2_000); refresh() }
            }
        }
        val perform: (() -> Unit) -> Boolean = { action ->
            var completed = false
            try { action(); refresh(); notice = null; completed = true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { notice = "操作失败：${error.javaClass.simpleName}" }
            revision++
            completed
        }
        val start: () -> Unit = {
            if (!switching) {
                switching = true
                notice = null
                scope.launch {
                    try {
                        var previous = probe.finish()
                        state = previous
                        var attempts = 0
                        while (foreground && previous.rawActive.isNotEmpty() && attempts < 40) {
                            delay(50)
                            if (!foreground) break
                            previous = probe.snapshot()
                            state = previous
                            attempts++
                        }
                        if (!foreground) {
                            notice = "启动已暂停，回到页面后请重新开始测试"
                        } else if (previous.rawActive.isNotEmpty()) {
                            notice = "上一次原始通知尚未撤除，请稍后重试"
                        } else {
                            state = probe.start(VivoProbeMode.SUPERX_NAVIGATION)
                        }
                        capturedAt = System.currentTimeMillis()
                        capturedElapsed = SystemClock.elapsedRealtime()
                        revision++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = "启动失败：${error.javaClass.simpleName}" }
                    finally { switching = false }
                }
            }
        }
        CompositionLocalProvider(LocalIdentityColors provides palette) {
            Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                        .systemBarsPadding().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    IdentityText(getString(R.string.live_probe_title), size = 28, bold = true)
                    IdentityText("当前包身份：$packageName", bold = true)
                    IdentityText("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", muted = true)
                    IdentityText(Build.DISPLAY, muted = true)
                    IdentityText("同一 NAVIGATION 测试，对照包名身份。若已安装高德地图，同包名测试包无法与它并存。", muted = true)
                    IdentityCard {
                        IdentityText("1  通知权限", bold = true, size = 20)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IdentityButton("允许通知 / 设置", "identity_permission", Modifier.weight(1f), enabled = !switching) {
                                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else openSettings(false)
                            }
                            IdentityButton("提升设置", "identity_promotion", Modifier.weight(1f), enabled = !switching) { openSettings(true) }
                        }
                    }
                    IdentityCard {
                        IdentityText("2  NAVIGATION 测试", bold = true, size = 20)
                        IdentityButton("开始测试", "identity_start", primary = true, enabled = !switching && Build.VERSION.SDK_INT >= 36, onClick = start)
                        if (Build.VERSION.SDK_INT < 36) IdentityText("此对照实验需要 Android 16 / API 36 及以上。", muted = true)
                        if (switching) IdentityText("正在结束上一次测试…", muted = true)
                        IdentityText("原始活动通知数：${state.rawActive.size}", bold = true)
                        IdentityText("会话匹配通知：${if (state.current != null) "已匹配" else "未匹配"}")
                        IdentityText("未匹配仅表示未找到本会话标记，不能据此判定系统完全没有通知。", muted = true)
                        IdentityText("请求进度：${state.progress}%")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            IdentityButton("推进 20%", "identity_advance", Modifier.weight(1f), enabled = !switching && state.current != null) {
                                perform { probe.advance() }
                            }
                            IdentityButton("结束测试", "identity_finish", Modifier.weight(1f), enabled = !switching && state.mode != null) {
                                perform { probe.finish() }
                            }
                        }
                        IdentityText("点开始后回桌面观察 10–20 秒，再回来记录。", muted = true)
                        IdentityText("开始 epoch ms：${state.startedAt ?: "—"}", muted = true)
                        IdentityText("状态采集 epoch ms：$capturedAt · elapsed ms：$capturedElapsed", muted = true)
                        state.lastError?.let { IdentityText(it, bold = true) }
                    }
                    IdentityCard {
                        IdentityText("3  肉眼观察与诊断", bold = true, size = 20)
                        listOf("原子岛出现", "只有通知栏", "完全无显示").forEachIndexed { index, result ->
                            IdentityButton(result, "identity_observe_$index", enabled = !switching && state.mode == VivoProbeMode.SUPERX_NAVIGATION) {
                                if (perform { probe.recordObservation(result) }) notice = "已记录：$result"
                            }
                        }
                        IdentityText("当前记录：${state.observations[VivoProbeMode.SUPERX_NAVIGATION] ?: "未记录"}", muted = true)
                        IdentityButton("复制全部诊断", "identity_copy", primary = true, enabled = !switching) {
                            try {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText(getString(R.string.live_probe_title), diagnostics()),
                                )
                                notice = "全部诊断已复制"
                            } catch (error: Exception) { notice = "复制失败：${error.javaClass.simpleName}" }
                        }
                        IdentityText("未开始或未记录观察结果，也可以复制诊断。每次测试最长 10 分钟，无后台重发。", muted = true)
                    }
                    notice?.let { IdentityText(it, bold = true, modifier = Modifier.testTag("identity_notice")) }
                }
            }
        }
    }
}

private class IdentityColors(dark: Boolean) {
    val background = if (dark) Color(0xFF171D20) else Color(0xFFF1F6F5)
    val surface = if (dark) Color(0xFF222B2E) else Color.White
    val ink = if (dark) Color(0xFFEAF4F0) else Color(0xFF16352D)
    val muted = if (dark) Color(0xFFB3C6BF) else Color(0xFF536D64)
    val accent = if (dark) Color(0xFF9DD8C3) else Color(0xFF276D57)
    val onAccent = if (dark) Color(0xFF13392C) else Color.White
}

private val LocalIdentityColors = staticCompositionLocalOf { IdentityColors(false) }

@Composable
private fun IdentityText(text: String, modifier: Modifier = Modifier, size: Int = 16, bold: Boolean = false, muted: Boolean = false) {
    val colors = LocalIdentityColors.current
    BasicText(text, modifier, TextStyle(color = if (muted) colors.muted else colors.ink, fontSize = size.sp,
        lineHeight = (size * 1.4).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}

@Composable
private fun IdentityCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalIdentityColors.current.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun IdentityButton(label: String, tag: String, modifier: Modifier = Modifier, primary: Boolean = false,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalIdentityColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().heightIn(min = 50.dp).clip(shape)
        .background(if (primary) colors.accent.copy(alpha = if (enabled) 1f else .3f) else colors.background)
        .border(1.dp, colors.muted.copy(alpha = .15f), shape).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(color = (if (primary) colors.onAccent else colors.ink).copy(alpha = if (enabled) 1f else .4f),
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium))
    }
}
