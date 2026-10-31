package com.stuassistant.app.liveprobe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import com.stuassistant.app.R
import java.util.UUID

enum class ProbeKind { PROGRESS, COUNTDOWN }

data class ProbeSnapshot(
    val sdk: Int,
    val device: String,
    val notificationsEnabled: Boolean,
    val channelEnabled: Boolean,
    val promotionAllowed: Boolean?,
    val eligible: Boolean?,
    val active: Boolean,
    val promoted: Boolean?,
    val kind: ProbeKind?,
    val progress: Int,
    val endsAt: Long?,
    val lastError: String?,
    val lastAction: String,
)

/** Manual, account-independent platform probe. No worker, alarm, service or automatic repost. */
class LiveUpdateProbe(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("live_probe", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(NotificationManager::class.java)

    init {
        try { ensureChannel() }
        catch (error: RuntimeException) { recordError("通知通道创建失败", error) }
        catch (error: LinkageError) { recordError("系统通知通道接口不可用", error) }
    }

    fun snapshot(): ProbeSnapshot = synchronized(LOCK) {
        val errors = mutableListOf<String>()
        val enabled = notificationsEnabled(errors)
        val channel = channelEnabled(errors)
        val allowed = if (Build.VERSION.SDK_INT >= 36 && manager != null) {
            try { manager.canPostPromotedNotifications() }
            catch (error: RuntimeException) { errors += describe("无法读取推广开关", error); null }
            catch (error: LinkageError) { errors += describe("系统未提供推广开关接口", error); null }
        } else null
        val current = activeNotification(errors)
        val promoted = if (Build.VERSION.SDK_INT >= 36 && current != null) {
            (current.notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        } else null
        ProbeSnapshot(
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} / ${Build.DISPLAY}",
            notificationsEnabled = enabled,
            channelEnabled = channel,
            promotionAllowed = allowed,
            eligible = if (prefs.contains("eligible")) prefs.getBoolean("eligible", false) else null,
            active = current != null,
            promoted = promoted,
            kind = savedKind(),
            progress = prefs.getInt("progress", 0).coerceIn(0, 100),
            endsAt = prefs.getLong("endsAt", 0L).takeIf { it > 0 },
            lastError = errors.firstOrNull() ?: prefs.getString("lastError", null),
            lastAction = prefs.getString("lastAction", "尚未开始") ?: "尚未开始",
        )
    }

    fun start(kind: ProbeKind): ProbeSnapshot = synchronized(LOCK) {
        val now = System.currentTimeMillis()
        prefs.edit().putString("session", UUID.randomUUID().toString()).putString("kind", kind.name)
            .putInt("progress", 0).putLong("endsAt", if (kind == ProbeKind.COUNTDOWN) now + COUNTDOWN_MS else 0L)
            .putLong("expiresAt", now + TIMEOUT_MS).putBoolean("stopped", false)
            .remove("eligible").remove("lastError").putBoolean("compatColorized", false)
            .putString("lastAction", "用户手动开始 ${kind.name}").apply()
        publish(kind)
    }

    fun advance(): ProbeSnapshot = synchronized(LOCK) {
        val kind = savedKind()
        if (kind == null || prefs.getBoolean("stopped", true)) {
            prefs.edit().putString("lastError", "请先手动开始一次测试").apply()
            return@synchronized snapshot()
        }
        val errors = mutableListOf<String>()
        if (activeNotification(errors) == null) {
            prefs.edit().putBoolean("stopped", true).putString("lastAction", "通知已移除，不再发布")
                .putString("lastError", errors.firstOrNull() ?: "通知已被移除或尚未被系统报告，请重新手动开始").apply()
            return@synchronized snapshot()
        }
        val now = System.currentTimeMillis()
        if (now >= prefs.getLong("expiresAt", 0L)) return@synchronized stop("测试已到10分钟上限")
        if (kind == ProbeKind.COUNTDOWN && now >= prefs.getLong("endsAt", 0L))
            return@synchronized stop("用户刷新时倒计时已结束")
        val progress = if (kind == ProbeKind.PROGRESS) (prefs.getInt("progress", 0) + 20).coerceAtMost(100)
            else (((COUNTDOWN_MS - (prefs.getLong("endsAt", now) - now)) * 100 / COUNTDOWN_MS).toInt()).coerceIn(0, 100)
        prefs.edit().putInt("progress", progress).remove("lastError")
            .putString("lastAction", if (kind == ProbeKind.PROGRESS) "用户手动更新至 $progress%" else "用户手动刷新；倒计时结束时间不变").apply()
        publish(kind)
    }

    fun finish(): ProbeSnapshot = synchronized(LOCK) { stop("用户手动结束") }

    fun promotionSettingsIntent(): Intent {
        if (Build.VERSION.SDK_INT >= 36) {
            val promotion = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (promotion.resolveActivity(app.packageManager) != null) return promotion
        }
        return notificationSettingsIntent()
    }

    fun notificationSettingsIntent(): Intent {
        if (Build.VERSION.SDK_INT >= 26) {
            val notification = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (notification.resolveActivity(app.packageManager) != null) return notification
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun diagnosticText(): String {
        val s = snapshot()
        val version = try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "unknown"} ($code)"
        } catch (_: PackageManager.NameNotFoundException) { "unknown" }
        catch (_: RuntimeException) { "unknown" }
        return buildString {
            appendLine("标准 Live Updates 本地验证；不是厂商接入或审核资格证明")
            appendLine("package=${app.packageName}")
            appendLine("version=$version")
            appendLine("device=${s.device}")
            appendLine("sdk=${s.sdk}; notifications=${s.notificationsEnabled}; channel=${s.channelEnabled}")
            appendLine("promotionAllowed=${s.promotionAllowed}; eligible(last built)=${s.eligible}")
            appendLine("active=${s.active}; FLAG_PROMOTED_ONGOING(observed)=${s.promoted}")
            appendLine("kind=${s.kind}; progress=${s.progress}; endsAt=${s.endsAt}")
            appendLine("API36 legacy colorized compatibility=${prefs.getBoolean("compatColorized", false)}")
            appendLine("进度最长10分钟；倒计时3分钟结束后约1秒由系统撤除；无后台重发。API24/25无系统timeout，需手动结束。")
            appendLine("active/flag可能延迟；ROM也可能不报告flag。eligible/allowed/flag均不能代替肉眼确认系统岛。")
            appendLine("lastAction=${s.lastAction}")
            append("lastError=${s.lastError ?: "无"}")
        }
    }

    internal fun removed(session: String?, dismissed: Boolean) = synchronized(LOCK) {
        if (session.isNullOrBlank() || session != prefs.getString("session", null)) return@synchronized
        stop(if (dismissed) "用户移除通知；不会自动重发" else "用户点击通知的结束操作")
        Unit
    }

    private fun stop(action: String): ProbeSnapshot {
        // Persist first so a concurrent manual update cannot resurrect a dismissed session.
        prefs.edit().putBoolean("stopped", true).putString("lastAction", action).remove("lastError").apply()
        try { manager?.cancel(NOTIFICATION_TAG, NOTIFICATION_ID) }
        catch (error: RuntimeException) { recordError("通知取消失败", error) }
        return snapshot()
    }

    private fun publish(kind: ProbeKind): ProbeSnapshot {
        val errors = mutableListOf<String>()
        try { ensureChannel() }
        catch (error: RuntimeException) { recordError("通知通道创建失败", error); return snapshot() }
        catch (error: LinkageError) { recordError("系统通知通道接口不可用", error); return snapshot() }
        if (manager == null || !notificationsEnabled(errors) || !channelEnabled(errors)) {
            prefs.edit().putString("lastError", errors.firstOrNull() ?: "请开启应用通知及测试通道后重试").apply()
            return snapshot()
        }
        // Keep the permission check next to notify, including for Android lint's flow analysis.
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("lastError", "请先允许通知权限，再手动开始").apply()
            return snapshot()
        }
        try {
            var notification: Notification
            var eligible: Boolean? = null
            var compat = false
            var fallback: String? = null
            if (Build.VERSION.SDK_INT >= 36) {
                try {
                    notification = buildNotification(kind, true, false)
                    eligible = promotable(notification)
                    // Android 16.0 AOSP used the opposite colorized rule. Choose this
                    // candidate only when the platform itself proves the difference.
                    if (Build.VERSION.SDK_INT == 36 && eligible == false) {
                        val legacy = buildNotification(kind, true, true)
                        if (promotable(legacy) == true) { notification = legacy; eligible = true; compat = true }
                    }
                } catch (error: LinkageError) {
                    notification = buildNotification(kind, false, false)
                    fallback = describe("系统缺少新版样式，已退回普通通知", error)
                } catch (error: UnsupportedOperationException) {
                    notification = buildNotification(kind, false, false)
                    fallback = describe("系统未实现新版样式，已退回普通通知", error)
                }
            } else notification = buildNotification(kind, false, false)
            val editor = prefs.edit().putBoolean("compatColorized", compat)
            if (eligible == null) editor.remove("eligible") else editor.putBoolean("eligible", eligible)
            if (fallback == null) editor.remove("lastError") else editor.putString("lastError", fallback)
            editor.apply()
            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        } catch (error: SecurityException) { recordError("通知发布被系统拒绝，请检查通知权限", error) }
        catch (error: RuntimeException) { recordError("通知发布失败", error) }
        catch (error: LinkageError) { recordError("系统通知接口不可用", error) }
        return snapshot()
    }

    private fun buildNotification(kind: ProbeKind, modern: Boolean, colorized: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        val session = prefs.getString("session", "") ?: ""
        val content = Intent().setClassName(app.packageName, "com.stuassistant.app.liveprobe.LiveProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(app, 0, content, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setSmallIcon(R.drawable.ic_live_probe).setContentTitle(if (kind == ProbeKind.PROGRESS) "手动进度测试" else "三分钟倒计时测试")
            .setContentText(if (kind == ProbeKind.PROGRESS) "${prefs.getInt("progress", 0)}% · 手动更新，最多10分钟" else "系统计时；应用不会后台重发")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(tap)
            .setDeleteIntent(receiverIntent(ACTION_DISMISSED, session))
            .addAction(Notification.Action.Builder(R.drawable.ic_live_probe, "结束测试", receiverIntent(ACTION_FINISH, session)).build())
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PUBLIC)
        if (Build.VERSION.SDK_INT >= 26) {
            val timeoutAt = if (kind == ProbeKind.COUNTDOWN)
                minOf(prefs.getLong("expiresAt", 0L), prefs.getLong("endsAt", 0L) + 1_000L)
                else prefs.getLong("expiresAt", 0L)
            builder.setTimeoutAfter((timeoutAt - System.currentTimeMillis()).coerceIn(1L, TIMEOUT_MS))
        }
        if (kind == ProbeKind.COUNTDOWN) {
            builder.setWhen(prefs.getLong("endsAt", 0L)).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            // Do not set shortCriticalText, including an empty string: it takes priority over time.
        } else builder.setShowWhen(false)
        if (modern && Build.VERSION.SDK_INT >= 36) {
            builder.setColorized(colorized)
            builder.setStyle(Notification.ProgressStyle().setProgress(prefs.getInt("progress", 0))
                .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(45, 108, 223))))
            // Public wire key documented by Android; the builder convenience method and
            // named constant were added only in 36.1 and are missing on 36.0 devices.
            builder.addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_EXTRA, true) })
            if (kind == ProbeKind.PROGRESS) builder.setShortCriticalText("${prefs.getInt("progress", 0)}%")
        } else if (kind == ProbeKind.PROGRESS) builder.setProgress(100, prefs.getInt("progress", 0), false)
        return builder.build()
    }

    private fun receiverIntent(action: String, session: String): PendingIntent {
        val intent = Intent(app, LiveProbeDismissReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("liveprobe").authority(app.packageName).appendPath(session).appendPath(action).build())
            .putExtra(EXTRA_SESSION, session)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun promotable(notification: Notification): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try { notification.hasPromotableCharacteristics() }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
    }

    private fun notificationsEnabled(errors: MutableList<String>): Boolean = try {
        manager?.areNotificationsEnabled() == true && (Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    } catch (error: RuntimeException) { errors += describe("无法读取通知权限", error); false }

    private fun channelEnabled(errors: MutableList<String>): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return try { manager?.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: false }
        catch (error: RuntimeException) { errors += describe("无法读取通知通道", error); false }
        catch (error: LinkageError) { errors += describe("系统未提供通知通道接口", error); false }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "标准 Live Updates 验证", NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = "仅手动启动的本地测试，不读取账号、课表或其他应用通知"
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun activeNotification(errors: MutableList<String>): StatusBarNotification? = try {
        manager?.activeNotifications?.firstOrNull { it.id == NOTIFICATION_ID && it.tag == NOTIFICATION_TAG }
    } catch (error: RuntimeException) { errors += describe("无法读取已发布通知", error); null }
    catch (error: LinkageError) { errors += describe("系统未提供通知状态接口", error); null }

    private fun savedKind(): ProbeKind? = try { prefs.getString("kind", null)?.let(ProbeKind::valueOf) }
        catch (_: IllegalArgumentException) { null }

    private fun recordError(label: String, error: Throwable) {
        prefs.edit().putString("lastError", describe(label, error)).apply()
    }

    private fun describe(label: String, error: Throwable) = "$label（${error.javaClass.simpleName}）"

    companion object {
        const val CHANNEL_ID = "live_probe_standard_v1"
        const val NOTIFICATION_TAG = "live_probe_standard"
        const val NOTIFICATION_ID = 41736
        const val ACTION_FINISH = "com.stuassistant.app.liveprobe.FINISH"
        const val ACTION_DISMISSED = "com.stuassistant.app.liveprobe.DISMISSED"
        const val EXTRA_SESSION = "probe_session"
        const val COUNTDOWN_MS = 180_000L
        const val TIMEOUT_MS = 600_000L
        const val REQUEST_PROMOTED_EXTRA = "android.requestPromotedOngoing"
        private val LOCK = Any()
    }
}
