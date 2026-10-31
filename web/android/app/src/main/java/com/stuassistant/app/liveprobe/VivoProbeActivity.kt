package com.stuassistant.app.liveprobe

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Separate manual experiment; never starts the account store or a background poster. */
class VivoProbeActivity : ComponentActivity() {
    private lateinit var probe: VivoIslandProbe
    private val evidence by lazy { getSharedPreferences("vivo_probe_evidence", Context.MODE_PRIVATE) }
    private var foreground by mutableStateOf(false)
    private var revision by mutableIntStateOf(0)
    private var notice by mutableStateOf<String?>(null)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++
        notice = if (it) "已允许通知，请选择测试" else "需要通知权限才能发送测试"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        probe = VivoIslandProbe(this)
        setContent { Page() }
    }

    override fun onResume() { super.onResume(); foreground = true; revision++ }
    override fun onPause() { foreground = false; super.onPause() }

    private fun settings(promoted: Boolean) {
        val original = LiveUpdateProbe(this)
        try { startActivity(if (promoted) original.promotionSettingsIntent() else original.notificationSettingsIntent()) }
        catch (error: Exception) { notice = "无法打开设置：${error.javaClass.simpleName}" }
    }

    private fun diagnostics(): String {
        val s = probe.snapshot()
        return buildString {
            appendLine("OriginOS 6 自有包名对照实验；肉眼结果与系统标志分别记录")
            appendLine(VivoProbeDiagnostics.capture(this@VivoProbeActivity, s.current))
            appendLine(probe.historyText())
            appendLine("selfPackageSuperXStatus=" + VivoSuperXPayload.readStatus(
                getSystemService(NotificationManager::class.java), packageName))
            appendLine("selfPackageNavigationStatus=" + VivoSuperXPayload.readStatus(
                getSystemService(NotificationManager::class.java), packageName, "NAVIGATION"))
            appendLine("E参考Sleepy混合payload；本包手动notify，不使用其前台服务/15秒更新/场景名单写入，不能视为完整复现。")
            appendLine("全局场景名单未写入；未冒用其他应用包名；未绕过系统审核或权限")
            appendLine("--- 每项手动记录时的快照 ---")
            VivoProbeMode.entries.forEach { mode ->
                appendLine(evidence.getString(mode.name, "${mode.name}: 尚未记录"))
            }
        }
    }

    private fun observe(value: String) {
        val s = probe.snapshot()
        val mode = s.mode ?: return
        probe.recordObservation(value)
        evidence.edit().putString(mode.name, buildString {
            appendLine("mode=$mode; userObserved=$value; startedAt=${s.startedAt}; expiresAt=${s.expiresAt}")
            append(VivoProbeDiagnostics.capture(this@VivoProbeActivity, s.current))
        }).apply()
        notice = "已记录：$value"
        revision++
    }

    @Composable private fun Page() {
        val dark = isSystemInDarkTheme()
        val palette = remember(dark) { VivoColors(dark) }
        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        var state by remember { mutableStateOf(probe.snapshot()) }
        var switching by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(foreground, revision) {
            if (foreground) {
                repeat(6) { state = probe.snapshot(); delay(400) }
                while (true) { state = probe.snapshot(); delay(2_000) }
            }
        }
        val perform: (() -> Unit) -> Unit = { action ->
            try { action(); state = probe.snapshot(); notice = null }
            catch (error: Exception) { notice = "操作失败：${error.javaClass.simpleName}" }
            revision++
        }
        val start: (VivoProbeMode) -> Unit = { mode ->
            if (!switching) {
                switching = true
                notice = null
                scope.launch {
                    try {
                        probe.finish()
                        var attempts = 0
                        while (probe.snapshot().active && attempts++ < 40) delay(50)
                        if (probe.snapshot().active) notice = "上一项通知尚未结束，请稍后再试"
                        else {
                            // A new run must not inherit the previous run's visual result.
                            evidence.edit().remove(mode.name).apply()
                            state = probe.start(mode)
                        }
                        revision++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { notice = "启动失败：${error.javaClass.simpleName}" }
                    finally { switching = false }
                }
            }
        }
        CompositionLocalProvider(LocalVivoColors provides palette) {
            Box(Modifier.fillMaxSize().background(palette.background), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    VText("STU 原子岛验证", size = 28, bold = true)
                    VText("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", muted = true)
                    VText(Build.DISPLAY, muted = true)
                    VCard {
                        VText("1  通知权限", bold = true, size = 20)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VButton("允许通知 / 设置", "vivo_permission", Modifier.weight(1f)) {
                                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else settings(false)
                            }
                            VButton("提升设置", "vivo_promotion", Modifier.weight(1f)) { settings(true) }
                        }
                    }
                    VCard {
                        VText("2  逐项测试", bold = true, size = 20)
                        VText("每次点一项，回桌面观察 10–20 秒，再回来记录。", muted = true)
                        listOf(VivoProbeMode.STANDARD_PLAIN, VivoProbeMode.STANDARD_COLORIZED,
                            VivoProbeMode.SUPERX, VivoProbeMode.CONTROL, VivoProbeMode.SUPERX_HYBRID).forEach { mode ->
                            VButton(mode.label(), "vivo_start_${mode.name}", primary = state.mode == mode,
                                enabled = !switching && (Build.VERSION.SDK_INT >= 36 || mode == VivoProbeMode.CONTROL)) { start(mode) }
                        }
                        VText("C/E 使用 STU 自有包名。E 参考 Sleepy 混合协议，采用手动投递。", muted = true)
                        if (Build.VERSION.SDK_INT < 36) VText("此验证包的 A/B/C/E 面向 Android 16 及以上；当前仅可测 D。", muted = true)
                    }
                    VCard {
                        VText(state.mode?.label() ?: "尚未开始", bold = true, size = 20)
                        VText("系统通知：${if (state.active) "存在" else "未发现"} · 进度 ${state.progress}%")
                        val allowed = if (Build.VERSION.SDK_INT >= 36) runCatching {
                            getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
                        }.getOrNull() else null
                        VText("标准提升：${when (allowed) { true -> "系统允许"; false -> "系统未允许"; null -> "未知 / 不可用" }}", muted = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            VButton("推进 20%", "vivo_advance", Modifier.weight(1f), enabled = state.active && !switching) { perform { probe.advance() } }
                            VButton("结束测试", "vivo_finish", Modifier.weight(1f), enabled = state.mode != null && !switching) { perform { probe.finish() } }
                        }
                        VText("记录刚才实际看到的结果", bold = true)
                        listOf("原子岛出现", "只有通知栏", "完全无显示").forEachIndexed { index, label ->
                            VButton(label, "vivo_observe_$index", enabled = state.mode != null && !switching) { observe(label) }
                        }
                        state.lastError?.let { VText(it) }
                    }
                    VCard {
                        VText("3  汇总结果", bold = true, size = 20)
                        listOf(VivoProbeMode.STANDARD_PLAIN, VivoProbeMode.STANDARD_COLORIZED,
                            VivoProbeMode.SUPERX, VivoProbeMode.CONTROL, VivoProbeMode.SUPERX_HYBRID).forEach { mode ->
                            VText("${mode.label()}：${state.observations[mode] ?: "未记录"}", muted = true)
                        }
                        VButton("复制全部诊断与结果", "vivo_copy", primary = true) {
                            try {
                                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("STU 原子岛验证", diagnostics()))
                                notice = "全部诊断与结果已复制"
                            } catch (error: Exception) { notice = "复制失败：${error.javaClass.simpleName}" }
                        }
                        VText(if (Build.VERSION.SDK_INT >= 26) "每项最长 10 分钟，无后台重发；系统标志不代替肉眼结果。"
                            else "此系统需手动结束通知，无后台重发；系统标志不代替肉眼结果。", muted = true)
                    }
                    notice?.let { VText(it, bold = true, modifier = Modifier.testTag("vivo_notice")) }
                }
            }
        }
    }
}

private fun VivoProbeMode.label() = when (this) {
    VivoProbeMode.STANDARD_PLAIN -> "A  标准 Live Updates · 不着色"
    VivoProbeMode.STANDARD_COLORIZED -> "B  标准 Live Updates · 着色兼容"
    VivoProbeMode.SUPERX -> "C  vivo SuperX · 私有扩展"
    VivoProbeMode.CONTROL -> "D  普通进度通知 · 对照"
    VivoProbeMode.SUPERX_HYBRID -> "E  标准 + vivo 扩展 · 混合协议"
}

private class VivoColors(dark: Boolean) {
    val background = if (dark) androidx.compose.ui.graphics.Color(0xFF171D20) else androidx.compose.ui.graphics.Color(0xFFF1F6F5)
    val surface = if (dark) androidx.compose.ui.graphics.Color(0xFF222B2E) else androidx.compose.ui.graphics.Color.White
    val ink = if (dark) androidx.compose.ui.graphics.Color(0xFFEAF4F0) else androidx.compose.ui.graphics.Color(0xFF16352D)
    val muted = if (dark) androidx.compose.ui.graphics.Color(0xFFB3C6BF) else androidx.compose.ui.graphics.Color(0xFF536D64)
    val accent = if (dark) androidx.compose.ui.graphics.Color(0xFF9DD8C3) else androidx.compose.ui.graphics.Color(0xFF276D57)
    val onAccent = if (dark) androidx.compose.ui.graphics.Color(0xFF13392C) else androidx.compose.ui.graphics.Color.White
}
private val LocalVivoColors = staticCompositionLocalOf { VivoColors(false) }
@Composable private fun VText(text: String, modifier: Modifier = Modifier, size: Int = 16, bold: Boolean = false, muted: Boolean = false) {
    val c = LocalVivoColors.current
    BasicText(text, modifier, TextStyle(color = if (muted) c.muted else c.ink, fontSize = size.sp,
        lineHeight = (size * 1.4).sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
}
@Composable private fun VCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalVivoColors.current.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
@Composable private fun VButton(label: String, tag: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalVivoColors.current
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.fillMaxWidth().heightIn(min = 50.dp).clip(shape)
        .background(if (primary) c.accent.copy(alpha = if (enabled) 1f else .3f) else c.background)
        .border(1.dp, c.muted.copy(alpha = .15f), shape).testTag(tag)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(12.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(color = (if (primary) c.onAccent else c.ink).copy(alpha = if (enabled) 1f else .4f),
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium))
    }
}
