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
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.stuassistant.app.R
import java.util.UUID

data class HuaweiLiveViewSnapshot(
    val active: Boolean,
    val endsAt: Long?,
    val operation: Int?,
    val extrasObserved: Boolean?,
    val lastError: String?,
    val lastAction: String,
)

/**
 * Manual experiment using Huawei's published Android notification extension protocol.
 * This is NOT the HarmonyOS ArkTS LiveViewKit, an entitlement check, or proof that a
 * compatibility container forwards notifications to the host's real Live View.
 *
 * Local lifecycle:
 * https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893
 * Exact Android keys/types (legacy Beta documentation):
 * https://developer.huawei.com/consumer/cn/doc/HMSCore-References/android-live-0000001613100205
 * Scene rights/test-device prerequisites, NOT automatically granted by notify():
 * https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-right-0000001562619196
 *
 * No HMS SDK, cloud request, account access, worker, alarm, overlay or automatic repost.
 */
class HuaweiLiveViewProbe(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("huawei_live_probe", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(NotificationManager::class.java)

    init {
        try { ensureChannel() }
        catch (error: RuntimeException) { recordError("通知通道创建失败", error) }
        catch (error: LinkageError) { recordError("系统通知通道接口不可用", error) }
    }

    fun snapshot(): HuaweiLiveViewSnapshot = synchronized(LOCK) {
        val errors = mutableListOf<String>()
        val current = activeNotification(errors)
        HuaweiLiveViewSnapshot(
            active = current != null,
            endsAt = prefs.getLong("endsAt", 0L).takeIf { it > 0L },
            operation = if (prefs.contains("operation")) prefs.getInt("operation", 0) else null,
            extrasObserved = current?.let { observedExtras(it.notification) },
            lastError = errors.firstOrNull() ?: prefs.getString("lastError", null),
            lastAction = prefs.getString("lastAction", "尚未开始") ?: "尚未开始",
        )
    }

    fun start(): HuaweiLiveViewSnapshot = synchronized(LOCK) {
        prefs.edit().putString("session", UUID.randomUUID().toString())
            .putLong("endsAt", System.currentTimeMillis() + COUNTDOWN_MS)
            .putBoolean("stopped", false).remove("operation").remove("lastError")
            .putString("lastAction", "用户手动开始华为 TIMER 三分钟实验").apply()
        publish(0)
        snapshot()
    }

    fun refresh(): HuaweiLiveViewSnapshot = synchronized(LOCK) {
        if (prefs.getBoolean("stopped", true) || prefs.getString("session", null).isNullOrBlank()) {
            prefs.edit().putString("lastError", "请先手动开始一次测试；已结束的通知不会重新发布").apply()
            return@synchronized snapshot()
        }
        if (System.currentTimeMillis() >= prefs.getLong("endsAt", 0L)) {
            return@synchronized stop("倒计时已结束；不会重新发布", sendEnd = true)
        }
        val errors = mutableListOf<String>()
        val current = activeNotification(errors)
        if (current == null) {
            prefs.edit().putBoolean("stopped", true)
                .putString("lastAction", "通知已移除或未被系统报告；不会重新发布")
                .putString("lastError", errors.firstOrNull() ?: "未发现自己的活动通知，请重新手动开始").apply()
            return@synchronized snapshot()
        }
        // The ID is exclusive to this app/probe. A present but different session
        // marker must never be updated by an old controller or PendingIntent.
        val observedSession = current.notification.extras?.getString(EXTRA_SESSION)
        if (observedSession != null && observedSession != prefs.getString("session", null)) {
            prefs.edit().putBoolean("stopped", true)
                .putString("lastError", "观察到不同会话的通知，本次不更新")
                .putString("lastAction", "会话校验未通过").apply()
            return@synchronized snapshot()
        }
        prefs.edit().remove("lastError")
            .putString("lastAction", "用户手动刷新；三分钟结束时间保持不变").apply()
        publish(1)
        snapshot()
    }

    fun finish(): HuaweiLiveViewSnapshot = synchronized(LOCK) {
        stop("用户手动结束华为 TIMER 实验", sendEnd = true)
    }

    internal fun removed(session: String?, dismissed: Boolean) = synchronized(LOCK) {
        if (session.isNullOrBlank() || session != prefs.getString("session", null)) return@synchronized
        stop(if (dismissed) "用户移除通知；不会自动重发" else "用户点击通知的结束操作", sendEnd = !dismissed)
        Unit
    }

    fun diagnosticText(): String = synchronized(LOCK) {
        val s = snapshot()
        val errors = mutableListOf<String>()
        val enabled = notificationsEnabled(errors)
        val channel = channelEnabled(errors)
        val version = try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName ?: "unknown"} ($code)"
        } catch (_: PackageManager.NameNotFoundException) { "unknown" }
        catch (_: RuntimeException) { "unknown" }
        buildString {
            appendLine("华为 Android 本地实况窗 TIMER 协议实验；不是接入资格或宿主胶囊成功证明")
            appendLine("package=${app.packageName}; version=$version")
            appendLine("runtimeManufacturer=${Build.MANUFACTURER}; runtimeBrand=${Build.BRAND}; runtimeModel=${Build.MODEL}")
            appendLine("runtimeSDK=${Build.VERSION.SDK_INT}; runtimeAndroidRelease=${Build.VERSION.RELEASE}; runtimeBuild=${Build.DISPLAY}")
            appendLine("以上是 APK 运行环境信息，不据此推断宿主 HarmonyOS 版本或宿主机型。")
            appendLine("method=Notification.Builder.addExtras + NotificationManager.notify(id); untagged id=$NOTIFICATION_ID")
            appendLine("notificationsEnabled=$enabled; channelEnabled=$channel; silent HIGH channel=$CHANNEL_ID")
            appendLine("event=TIMER; type=1; capsuleType=2; operation(last successfully posted)=${s.operation}")
            appendLine("active(compat NotificationManager)=${s.active}; documentedExtrasObserved=${s.extrasObserved}")
            appendLine("endsAt=${s.endsAt}; stopped=${prefs.getBoolean("stopped", true)}")
            appendLine("权益/场景权限/设备白名单=未核验；未申请或声称获得正式权益。")
            appendLine("宿主实况窗卡片/状态栏胶囊/锁屏倒计时=请在手机上人工确认，API无异常或extras保留不等于成功。")
            appendLine("文档适用于旧 Android/HarmonyOS 3.1/4.0及以下体系且仍标Beta；HarmonyOS 7兼容容器透传能力未知。")
            appendLine("无后台重发；API26+倒计时结束后约1秒系统撤除，API24/25需手动结束。")
            appendLine("lastAction=${s.lastAction}")
            append("lastError=${s.lastError ?: errors.firstOrNull() ?: "无"}")
        }
    }

    private fun stop(action: String, sendEnd: Boolean): HuaweiLiveViewSnapshot {
        // Store this before any binder call, so a concurrent refresh cannot revive it.
        prefs.edit().putBoolean("stopped", true).putString("lastAction", action).remove("lastError").apply()
        val errors = mutableListOf<String>()
        // Never create a fresh end notification after timeout/dismissal. The official
        // lifecycle uses operation=2 for an existing notification, then cancels it.
        if (sendEnd && activeNotification(errors) != null) publish(2)
        try { manager?.cancel(NOTIFICATION_ID) }
        catch (error: RuntimeException) { recordError("通知取消失败", error) }
        catch (error: LinkageError) { recordError("系统通知取消接口不可用", error) }
        if (errors.isNotEmpty() && prefs.getString("lastError", null) == null) {
            prefs.edit().putString("lastError", errors.first()).apply()
        }
        return snapshot()
    }

    private fun publish(operation: Int) {
        val errors = mutableListOf<String>()
        try { ensureChannel() }
        catch (error: RuntimeException) { recordError("通知通道创建失败", error); return }
        catch (error: LinkageError) { recordError("系统通知通道接口不可用", error); return }
        if (manager == null || !notificationsEnabled(errors) || !channelEnabled(errors)) {
            prefs.edit().putString("lastError", errors.firstOrNull() ?: "请开启应用通知及华为测试通道，再手动开始").apply()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putString("lastError", "请先允许通知权限，再手动开始").apply()
            return
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(operation))
            prefs.edit().putInt("operation", operation).remove("lastError").apply()
        } catch (error: SecurityException) { recordError("通知发布被系统拒绝，请检查通知权限", error) }
        catch (error: RuntimeException) { recordError("通知发布失败", error) }
        catch (error: LinkageError) { recordError("系统通知接口不可用", error) }
    }

    private fun buildNotification(operation: Int): Notification {
        val session = prefs.getString("session", "") ?: ""
        val endsAt = prefs.getLong("endsAt", 0L)
        val capsule = Bundle().apply {
            putInt("notification.live.capsuleStatus", if (operation == 2) -1 else 1)
            putInt("notification.live.capsuleType", 2)
            putParcelable("notification.live.capsuleIcon", Icon.createWithResource(app, R.drawable.ic_live_probe))
            putInt("notification.live.capsuleBgColor", Color.rgb(45, 108, 223))
            putString("notification.live.capsuleContent", "三分钟手动计时实验")
            putLong("notification.live.capsuleTime", endsAt)
            putBoolean("notification.live.capsuleCountdown", true)
            putBoolean("notification.live.capsulePause", false)
        }
        val extras = Bundle().apply {
            putString(EXTRA_SESSION, session) // Our own marker, not a Huawei extension.
            putInt("notification.live.operation", operation)
            putString("notification.live.event", "TIMER")
            putInt("notification.live.type", 1)
            // This is retention AFTER finishing, not a maximum activity duration.
            putLong("notification.live.keepTime", 0L)
            putBundle("notification.live.feature", Bundle().apply {
                putInt("notification.live.feature.chronometerPosition", 1)
            })
            putBundle("notification.live.capsule", capsule)
        }
        val content = Intent().setClassName(app.packageName, "com.stuassistant.app.liveprobe.LiveProbeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(app, NOTIFICATION_ID, content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        builder.setSmallIcon(R.drawable.ic_live_probe)
            .setContentTitle(if (operation == 2) "华为计时实验已结束" else "华为三分钟计时实验")
            .setContentText("手动本地 TIMER；宿主胶囊显示需人工确认")
            .setContentIntent(tap).setDeleteIntent(receiverIntent(ACTION_DISMISSED, session))
            .addAction(Notification.Action.Builder(R.drawable.ic_live_probe, "结束测试", receiverIntent(ACTION_FINISH, session)).build())
            .setOngoing(operation != 2).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_PROGRESS).setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(0).setSound(null).setWhen(endsAt).setShowWhen(true)
            .setUsesChronometer(operation != 2).setChronometerCountDown(true).addExtras(extras)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setTimeoutAfter(if (operation == 2) 1L else
                (endsAt + 1_000L - System.currentTimeMillis()).coerceIn(1L, COUNTDOWN_MS + 1_000L))
        }
        return builder.build()
    }

    private fun receiverIntent(action: String, session: String): PendingIntent {
        val intent = Intent(app, HuaweiLiveViewDismissReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("huaweiprobe").authority(app.packageName).appendPath(session).appendPath(action).build())
            .putExtra(EXTRA_SESSION, session)
        return PendingIntent.getBroadcast(app, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun observedExtras(notification: Notification): Boolean {
        return try {
            val extras = notification.extras ?: return false
            val capsule = extras.getBundle("notification.live.capsule") ?: return false
            val feature = extras.getBundle("notification.live.feature") ?: return false
            extras.getString(EXTRA_SESSION) == prefs.getString("session", null) &&
                extras.getString("notification.live.event") == "TIMER" &&
                extras.getInt("notification.live.type", -1) == 1 &&
                extras.getInt("notification.live.operation", -1) == prefs.getInt("operation", -2) &&
                extras.containsKey("notification.live.keepTime") && extras.getLong("notification.live.keepTime") == 0L &&
                feature.getInt("notification.live.feature.chronometerPosition", -1) == 1 &&
                capsule.getInt("notification.live.capsuleStatus", 0) == (if (prefs.getInt("operation", 0) == 2) -1 else 1) &&
                capsule.getInt("notification.live.capsuleType", 0) == 2 &&
                capsule.getLong("notification.live.capsuleTime", 0L) == prefs.getLong("endsAt", -1L) &&
                capsule.getBoolean("notification.live.capsuleCountdown", false)
        } catch (_: RuntimeException) { false }
    }

    private fun activeNotification(errors: MutableList<String>): StatusBarNotification? = try {
        manager?.activeNotifications?.firstOrNull { it.id == NOTIFICATION_ID && it.tag == null }
    } catch (error: RuntimeException) { errors += describe("无法读取兼容环境中的活动通知", error); null }
    catch (error: LinkageError) { errors += describe("兼容环境未提供通知状态接口", error); null }

    private fun notificationsEnabled(errors: MutableList<String>): Boolean = try {
        manager?.areNotificationsEnabled() == true && (Build.VERSION.SDK_INT < 33 ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    } catch (error: RuntimeException) { errors += describe("无法读取通知权限", error); false }
    catch (error: LinkageError) { errors += describe("兼容环境未提供通知权限接口", error); false }

    private fun channelEnabled(errors: MutableList<String>): Boolean {
        if (Build.VERSION.SDK_INT < 26) return true
        return try { manager?.getNotificationChannel(CHANNEL_ID)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } ?: false }
        catch (error: RuntimeException) { errors += describe("无法读取通知通道", error); false }
        catch (error: LinkageError) { errors += describe("兼容环境未提供通知通道接口", error); false }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, "华为本地 TIMER 协议实验", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "仅用户手动启动的三分钟本地计时，不读账号或课表，不证明已获得实况窗权益"
            channel.setSound(null, null)
            channel.enableVibration(false)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun recordError(label: String, error: Throwable) {
        prefs.edit().putString("lastError", describe(label, error)).apply()
    }

    private fun describe(label: String, error: Throwable) = "$label（${error.javaClass.simpleName}）"

    companion object {
        const val CHANNEL_ID = "huawei_local_probe_v1"
        const val NOTIFICATION_ID = 41737
        const val ACTION_FINISH = "com.stuassistant.app.huaweiprobe.FINISH"
        const val ACTION_DISMISSED = "com.stuassistant.app.huaweiprobe.DISMISSED"
        const val EXTRA_SESSION = "probe_session"
        const val COUNTDOWN_MS = 180_000L
        private val LOCK = Any()
    }
}
