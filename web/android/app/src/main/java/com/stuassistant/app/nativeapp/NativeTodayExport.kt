package com.stuassistant.app.nativeapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import java.util.Locale

data class NativeTodayExportTicket(
    val code: String,
    val expiresIn: Int,
    val deepLink: String,
    val semester: String,
    val campus: String,
    val courseCount: Int,
)

internal const val TODAY_DOWNLOAD_URL = "https://today.oyishi.asia/"

private val TODAY_DEEP_LINK_PATTERN =
    Regex("^today://sheet/stu-import\\?code=[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$")

internal fun isAllowedTodayDeepLink(deepLink: String): Boolean =
    TODAY_DEEP_LINK_PATTERN.matches(deepLink)

internal fun formatTodayExportCode(code: String): String =
    code.trim().uppercase(Locale.ROOT).chunked(4).joinToString(" ")

internal fun todayCampusLabel(campus: String): String = when (campus.trim().lowercase(Locale.ROOT)) {
    "med" -> "汕大医学院"
    "main" -> "汕大本部"
    else -> "手动导入"
}

/**
 * Strictly whitelisted deep-link launcher for the `today` companion app.
 * Deliberately separate from `nativeOpenLink` so generic link handling remains http/https-only.
 */
internal fun openTodayImportDeepLink(context: Context, deepLink: String): Boolean {
    if (!isAllowedTodayDeepLink(deepLink)) return false
    return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

@Composable
internal fun NativeTodayExportFallbackDialog(
    ticket: NativeTodayExportTicket,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalNativeColors.current
    var copied by remember(ticket.code) { mutableStateOf(false) }
    val meta = listOf(
        ticket.semester,
        todayCampusLabel(ticket.campus),
        if (ticket.courseCount > 0) "${ticket.courseCount} 门课" else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val minutes = ((ticket.expiresIn.coerceAtLeast(60) + 59) / 60)

    NDialog("导入到 today", onDismiss = onDismiss) {
        if (meta.isNotBlank()) NTag(meta, colors.brand)
        NText("尚未检测到 today 应用。可复制下方 12 位一次性导入码，安装 today 后在「安排表 → 导入课表」中粘贴导入。", 14, muted = true)

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedRectangle(16.dp))
                .background(colors.bg)
                .border(1.dp, colors.border, RoundedRectangle(16.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NText("12 位一次性导入码", 12, muted = true)
            BasicText(
                text = formatTodayExportCode(ticket.code),
                style = nativeTextStyle(
                    color = colors.ink,
                    sizeSp = 22f,
                    lineHeightSp = 28f,
                    weight = FontWeight.Bold,
                    align = TextAlign.Center,
                    tabularNums = true,
                ),
            )
            NText("$minutes 分钟内有效 · 仅可使用一次 · 不含账号密码", 12, muted = true)
        }

        NButton(
            label = if (copied) "已复制导入码" else "复制导入码",
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("today 导入码", ticket.code))
                copied = clipboard != null
            },
            primary = true,
            modifier = Modifier.fillMaxWidth(),
            haptic = NativeButtonHaptic.Submit,
        )

        NButton(
            label = "前往下载 today",
            onClick = { nativeOpenLink(context, TODAY_DOWNLOAD_URL) },
            modifier = Modifier.fillMaxWidth(),
            haptic = NativeButtonHaptic.Navigation,
        )

        NButton(
            label = "关闭",
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            haptic = NativeButtonHaptic.Utility,
        )
    }
}
