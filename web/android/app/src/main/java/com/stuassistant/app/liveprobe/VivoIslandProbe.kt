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
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import com.stuassistant.app.R
import org.json.JSONArray
import java.util.UUID

enum class VivoProbeMode { CONTROL, STANDARD_PLAIN, STANDARD_COLORIZED, SUPERX, SUPERX_HYBRID, SUPERX_NAVIGATION }

data class VivoProbeSnapshot(
    val active: Boolean,
    val mode: VivoProbeMode?,
    val progress: Int,
    val startedAt: Long?,
    val expiresAt: Long?,
    val current: StatusBarNotification?,
    val rawActive: List<StatusBarNotification>,
    val lastError: String?,
    val lastAction: String,
    val observations: Map<VivoProbeMode, String>,
    val history: List<String>,
)

/** Manual, isolated notification comparisons; no worker, alarm, service or automatic repost. */
class VivoIslandProbe(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("vivo_island_probe", Context.MODE_PRIVATE)
    private val manager = app.getSystemService(NotificationManager::class.java)

    init {
        runCatching { ensureChannel() }.onFailure { recordError("通知通道创建失败", it) }
    }

    fun snapshot(): VivoProbeSnapshot = synchronized(LOCK) {
        var readError: String? = null
        val session = prefs.getString("session", null)
        val rawActive = try {
            manager?.activeNotifications?.filter {
                it.packageName == app.packageName && it.user == android.os.Process.myUserHandle()
            }.orEmpty()
        } catch (error: RuntimeException) {
            readError = describe("活动通知读取失败", error)
            emptyList()
        } catch (error: LinkageError) {
            readError = describe("活动通知接口不可用", error)
            emptyList()
        }
        val current = rawActive.firstOrNull {
            it.id == NOTIFICATION_ID && it.tag == notificationTag(savedMode()) && session != null &&
                it.notification.extras.getString(EXTRA_SESSION) == session
        }
        if (current != null && current.notification.extras.getString(EXTRA_SESSION) == session) {
            if (prefs.getLong("firstObservedAt", 0L) == 0L) {
                val now = System.currentTimeMillis()
                prefs.edit().putLong("firstObservedAt", now).apply()
                appendHistory("首次从系统读到本会话通知 postTime=${current.postTime}")
            }
            prefs.edit().putLong("lastObservedAt", System.currentTimeMillis()).apply()
            savedMode()?.let { mode ->
                val n = current.notification
                val eligible = if (Build.VERSION.SDK_INT >= 36) runCatching { n.hasPromotableCharacteristics() }.getOrNull() else null
                prefs.edit().putString("readback_${mode.name}", "capturedAt=${System.currentTimeMillis()}; postTime=${current.postTime}; requestedProgress=${prefs.getInt("progress", 0)}; flags=${n.flags}; requestPromoted=${n.extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false)}; colorized=${n.extras.getBoolean(Notification.EXTRA_COLORIZED, false)}; postedEligible=$eligible; superx=${n.extras.containsKey("notification.superx.operation")}").apply()
            }
        }
        VivoProbeSnapshot(
            active = current != null,
            mode = savedMode(),
            progress = prefs.getInt("progress", 0),
            startedAt = prefs.getLong("startedAt", 0L).takeIf { it > 0L },
            expiresAt = prefs.getLong("expiresAt", 0L).takeIf { it > 0L },
            current = current,
            rawActive = rawActive,
            lastError = readError ?: prefs.getString("lastError", null),
            lastAction = prefs.getString("lastAction", "尚未开始") ?: "尚未开始",
            observations = VivoProbeMode.entries.associateWith { prefs.getString("observation_${it.name}", "未记录") ?: "未记录" },
            history = history(),
        )
    }

    fun start(mode: VivoProbeMode): VivoProbeSnapshot = synchronized(LOCK) {
        if ((mode == VivoProbeMode.STANDARD_PLAIN || mode == VivoProbeMode.STANDARD_COLORIZED || mode == VivoProbeMode.SUPERX_HYBRID) && Build.VERSION.SDK_INT < 36) {
            recordError("此模式需要 Android 16 / API 36", UnsupportedOperationException())
            return@synchronized snapshot()
        }
        // The vendor protocol routes by a distinct tag. Retire this app's old
        // session before changing tags so a direct controller call cannot leave
        // two different comparison modes posted together.
        stop("准备手动开始新会话")
        val now = System.currentTimeMillis()
        prefs.edit().putString("session", UUID.randomUUID().toString()).putString("mode", mode.name)
            .putInt("progress", 0).putLong("startedAt", now).putLong("startedElapsed", SystemClock.elapsedRealtime())
            .putLong("expiresAt", now + TIMEOUT_MS).putLong("firstObservedAt", 0L).putLong("lastObservedAt", 0L)
            .putBoolean("stopped", false).remove("lastError").remove("lastBuiltEligible")
            .remove("lastBuiltRequest").remove("built_${mode.name}")
            .remove("observation_${mode.name}").remove("readback_${mode.name}")
            .putString("lastAction", "手动开始 ${mode.name}").apply()
        if (mode == VivoProbeMode.SUPERX_NAVIGATION) prefs.edit().remove("beforePostSceneStatus").apply()
        appendHistory("开始 ${mode.name}; expiresAt=${now + TIMEOUT_MS}")
        publish(mode)
        snapshot()
    }

    fun advance(): VivoProbeSnapshot = synchronized(LOCK) {
        val mode = savedMode()
        if (mode == null || prefs.getBoolean("stopped", true)) {
            prefs.edit().putString("lastError", "请先手动开始测试；已结束的通知不会重发").apply()
            return@synchronized snapshot()
        }
        if (System.currentTimeMillis() >= prefs.getLong("expiresAt", 0L) ||
            SystemClock.elapsedRealtime() - prefs.getLong("startedElapsed", 0L) >= TIMEOUT_MS) {
            return@synchronized stop("已到10分钟上限")
        }
        val before = snapshot()
        if (before.current == null || before.current.notification.extras.getString(EXTRA_SESSION) != prefs.getString("session", null)) {
            prefs.edit().putString("lastError", "当前会话通知尚未出现或已移除，请重新手动开始").apply()
            return@synchronized before.copy(lastError = "当前会话通知尚未出现或已移除，请重新手动开始")
        }
        val progress = (prefs.getInt("progress", 0) + 20).coerceAtMost(100)
        prefs.edit().putInt("progress", progress).remove("lastError")
            .putString("lastAction", "手动更新 ${mode.name} 至 $progress%").apply()
        appendHistory("更新 ${mode.name} progress=$progress")
        publish(mode)
        snapshot()
    }

    fun finish(): VivoProbeSnapshot = synchronized(LOCK) { stop("用户手动结束") }

    fun recordObservation(value: String): VivoProbeSnapshot = synchronized(LOCK) {
        val mode = savedMode() ?: return@synchronized snapshot()
        if (value !in OBSERVATIONS) return@synchronized snapshot()
        val now = System.currentTimeMillis()
        prefs.edit().putString("observation_${mode.name}", "$value @ $now")
            .putString("lastAction", "已记录 ${mode.name}：$value").apply()
        appendHistory("用户肉眼观察 ${mode.name}=$value")
        snapshot()
    }

    fun historyText(): String = synchronized(LOCK) {
        val s = snapshot()
        buildString {
            appendLine("mode=${s.mode}; progress=${s.progress}; active=${s.active}")
            appendLine("startedAt=${s.startedAt}; expiresAt=${s.expiresAt}; stopped=${prefs.getBoolean("stopped", true)}")
            appendLine("startedElapsed=${prefs.getLong("startedElapsed", 0L)}; firstObservedAt=${prefs.getLong("firstObservedAt", 0L)}; lastObservedAt=${prefs.getLong("lastObservedAt", 0L)}")
            appendLine("lastBuiltEligible=${if (prefs.contains("lastBuiltEligible")) prefs.getBoolean("lastBuiltEligible", false) else null}")
            appendLine("lastBuiltRequest=${prefs.getString("lastBuiltRequest", "尚未构造")}")
            appendLine("beforePostSceneStatus=${prefs.getString("beforePostSceneStatus", "尚未采集纯NAV首次发布前状态")}")
            appendLine(channelDiagnostics())
            appendLine("observedSessionMatches=${s.current?.notification?.extras?.getString(EXTRA_SESSION)?.let { it == prefs.getString("session", null) }}")
            appendLine("rawActiveCount=${s.rawActive.size}; strictActive=${s.active}")
            s.rawActive.forEachIndexed { index, raw ->
                val extras = raw.notification.extras
                appendLine("raw[$index]: id=${raw.id}; tag=${raw.tag}; postTime=${raw.postTime}; channel=${if (Build.VERSION.SDK_INT >= 26) raw.notification.channelId else "unsupported"}; sessionPresent=${extras.containsKey(EXTRA_SESSION)}; sessionMatch=${extras.getString(EXTRA_SESSION)?.let { it == prefs.getString("session", null) }}; mode=${extras.getString(EXTRA_MODE)}; modeMatch=${extras.getString(EXTRA_MODE)?.let { it == s.mode?.name }}; scene=${extras.getString("notification.superx.scene")}; operation=${if (extras.containsKey(VivoSuperXPayload.OPERATION)) extras.getInt(VivoSuperXPayload.OPERATION) else null}; style=${extras.getString(Notification.EXTRA_TEMPLATE)}; requestPromoted=${extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false)}; colorized=${extras.getBoolean(Notification.EXTRA_COLORIZED, false)}; flags=${raw.notification.flags}")
            }
            appendLine("lastAction=${s.lastAction}; lastError=${s.lastError ?: "无"}")
            appendLine("每模式人工观察（带记录时刻，不代表最新会话）：")
            s.observations.forEach { (mode, result) -> appendLine("${mode.name}=$result") }
            appendLine("每模式最近一次系统通知读回（可能属于较早会话）：")
            VivoProbeMode.entries.forEach { appendLine("${it.name}=${prefs.getString("readback_${it.name}", "未读到")}") }
            appendLine("每模式最近构造请求（notify调用前保存，不代表系统接收）：")
            VivoProbeMode.entries.forEach { appendLine("${it.name}=${prefs.getString("built_${it.name}", "尚未构造")}") }
            appendLine("最近操作历史（epoch ms / elapsed ms）：")
            s.history.forEach { appendLine(it) }
        }
    }

    internal fun removed(session: String?, dismissed: Boolean) = synchronized(LOCK) {
        if (session.isNullOrBlank() || session != prefs.getString("session", null)) return@synchronized
        stop(if (dismissed) "用户移除通知；不会自动重发" else "用户点击通知的结束操作", sendEnd = !dismissed)
        Unit
    }

    private fun publish(mode: VivoProbeMode) {
        try {
            ensureChannel()
            if (manager == null || !manager.areNotificationsEnabled() ||
                (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) ||
                (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) {
                prefs.edit().putString("lastError", "请允许应用通知及测试通道后重新开始").apply()
                appendHistory("notify未调用：通知权限/通道未开启")
                return
            }
            if (mode == VivoProbeMode.SUPERX_NAVIGATION && !prefs.contains("beforePostSceneStatus")) {
                val capturedAt = System.currentTimeMillis()
                val status = VivoSuperXPayload.readStatus(manager, app.packageName, "NAVIGATION")
                prefs.edit().putString("beforePostSceneStatus", "capturedAt=$capturedAt; scene=NAVIGATION; sceneStatus=${status.sceneStatus}; customFun=${status.customFun}").apply()
                // A false/unknown getter result is evidence, not authorization to
                // skip this explicit manual protocol experiment or write a setter.
            }
            val notification = buildNotification(mode)
            val eligible = if (Build.VERSION.SDK_INT >= 36) runCatching { notification.hasPromotableCharacteristics() }.getOrNull() else null
            val extras = notification.extras
            val builtSummary = "builtAt=${System.currentTimeMillis()}; builtElapsed=${SystemClock.elapsedRealtime()}; mode=$mode; tag=${notificationTag(mode)}; id=$NOTIFICATION_ID; channel=$CHANNEL_ID; scene=${extras.getString("notification.superx.scene")}; operation=${if (extras.containsKey(VivoSuperXPayload.OPERATION)) extras.getInt(VivoSuperXPayload.OPERATION) else null}; style=${extras.getString(Notification.EXTRA_TEMPLATE)}; requestPromoted=${extras.getBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, false)}; colorized=${extras.getBoolean(Notification.EXTRA_COLORIZED, false)}; eligible=$eligible"
            prefs.edit().apply {
                if (eligible == null) remove("lastBuiltEligible") else putBoolean("lastBuiltEligible", eligible)
                putString("lastBuiltRequest", builtSummary)
                putString("built_${mode.name}", builtSummary)
            }.apply()
            manager.notify(notificationTag(mode), NOTIFICATION_ID, notification)
            prefs.edit().remove("lastError").apply()
            appendHistory("notify返回无异常 ${mode.name}; eligible=$eligible; progress=${prefs.getInt("progress", 0)}")
        } catch (error: RuntimeException) { recordError("通知发布失败", error) }
        catch (error: LinkageError) { recordError("通知接口不可用", error) }
    }

    private fun buildNotification(mode: VivoProbeMode): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        val session = prefs.getString("session", "") ?: ""
        val progress = prefs.getInt("progress", 0)
        val targetActivity = if (mode == VivoProbeMode.SUPERX_NAVIGATION) "com.stuassistant.app.liveprobe.VivoIdentityProbeActivity" else "com.stuassistant.app.liveprobe.VivoProbeActivity"
        val content = Intent().setClassName(app.packageName, targetActivity)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(app, 0, content, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.setSmallIcon(R.drawable.ic_live_probe).setContentTitle("原子岛对照测试")
            .setContentText("$progress% · ${mode.name}")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(tap).setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(receiverIntent(ACTION_DISMISSED, session))
            .addAction(Notification.Action.Builder(R.drawable.ic_live_probe, "结束测试", receiverIntent(ACTION_FINISH, session)).build())
            .addExtras(Bundle().apply { putString(EXTRA_SESSION, session); putString(EXTRA_MODE, mode.name) })
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter((prefs.getLong("expiresAt", 0L) - System.currentTimeMillis()).coerceIn(1L, TIMEOUT_MS))
        if (mode == VivoProbeMode.CONTROL) {
            builder.setProgress(100, progress, false)
        } else if (mode == VivoProbeMode.SUPERX || mode == VivoProbeMode.SUPERX_NAVIGATION) {
            builder.setProgress(100, progress, false)
                .addExtras(VivoSuperXPayload.create(
                    title = "原子岛协议测试", content = "$progress% · 手动验证",
                    icon = Icon.createWithResource(app, R.drawable.ic_live_probe), clickIntent = tap,
                ).apply {
                    if (mode == VivoProbeMode.SUPERX_NAVIGATION) putString("notification.superx.scene", "NAVIGATION")
                })
        } else if (Build.VERSION.SDK_INT >= 36) {
            builder.setColorized(mode == VivoProbeMode.STANDARD_COLORIZED)
                .setStyle(Notification.ProgressStyle().setProgress(progress)
                    .addProgressSegment(Notification.ProgressStyle.Segment(100).setColor(Color.rgb(45, 108, 223))))
                .setShortCriticalText("$progress%")
                .addExtras(Bundle().apply { putBoolean(LiveUpdateProbe.REQUEST_PROMOTED_EXTRA, true) })
            if (mode == VivoProbeMode.SUPERX_HYBRID) {
                builder.addExtras(VivoSuperXPayload.createHybrid(
                    title = "原子岛混合协议测试", content = "$progress% · 手动验证", progress = progress,
                    icon = Icon.createWithResource(app, R.drawable.ic_live_probe), clickIntent = tap,
                ))
            }
        }
        return builder.build()
    }

    private fun receiverIntent(action: String, session: String): PendingIntent {
        val intent = Intent(app, VivoProbeDismissReceiver::class.java).setAction(action)
            .setData(Uri.Builder().scheme("vivoprobe").authority(app.packageName).appendPath(session).appendPath(action).build())
            .putExtra(EXTRA_SESSION, session)
        return PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stop(action: String, sendEnd: Boolean = true): VivoProbeSnapshot {
        val before = snapshot()
        prefs.edit().putBoolean("stopped", true).putString("lastAction", action).remove("lastError").apply()
        appendHistory(action)
        try {
            if (sendEnd && before.mode in setOf(VivoProbeMode.SUPERX, VivoProbeMode.SUPERX_HYBRID, VivoProbeMode.SUPERX_NAVIGATION) && before.current != null &&
                before.current.notification.extras.getString(EXTRA_SESSION) == prefs.getString("session", null) &&
                (Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
                val ended = Notification.Builder.recoverBuilder(app, before.current.notification)
                    .addExtras(VivoSuperXPayload.endExtras()).build()
                manager?.notify(notificationTag(before.mode), NOTIFICATION_ID, ended)
                appendHistory("SuperX结束 operation=2 返回无异常")
            }
        } catch (error: RuntimeException) { recordError("SuperX结束写入失败", error) }
        catch (error: LinkageError) { recordError("SuperX结束接口不可用", error) }
        finally {
            val ownedRaw = before.rawActive.filter {
                it.packageName == app.packageName && it.user == android.os.Process.myUserHandle() &&
                    (Build.VERSION.SDK_INT < 29 || it.uid == android.os.Process.myUid()) &&
                    it.id == NOTIFICATION_ID
            }
            // A ROM can rewrite the tag or remove our session extras. Explicit
            // stop still retires only this app/user/UID's reserved probe ID;
            // it never authorizes an update or touches another notification ID.
            val targets = (listOf(NOTIFICATION_TAG to NOTIFICATION_ID, SUPERX_TAG to NOTIFICATION_ID) +
                ownedRaw.map { it.tag to it.id }).distinct()
            targets.forEach { (tag, id) ->
                try { manager?.cancel(tag, id) }
                catch (error: RuntimeException) { recordError("通知取消失败", error) }
                catch (error: LinkageError) { recordError("通知取消接口不可用", error) }
            }
        }
        return snapshot()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26 && manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "OriginOS 原子岛对照验证", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "仅用户手动启动的本地通知实验"
                setSound(null, null)
                enableVibration(false)
            })
        }
    }

    private fun savedMode(): VivoProbeMode? = runCatching { prefs.getString("mode", null)?.let(VivoProbeMode::valueOf) }.getOrNull()

    private fun notificationTag(mode: VivoProbeMode?): String = if (mode == VivoProbeMode.SUPERX || mode == VivoProbeMode.SUPERX_NAVIGATION) SUPERX_TAG else NOTIFICATION_TAG

    private fun channelDiagnostics(): String {
        if (Build.VERSION.SDK_INT < 26) return "channelRequestedId=$CHANNEL_ID; requestedImportance=${NotificationManager.IMPORTANCE_HIGH}; currentImportance=unsupported; hasUserSetImportance=unsupported"
        return try {
            val channel = manager?.getNotificationChannel(CHANNEL_ID)
            "channelRequestedId=$CHANNEL_ID; requestedImportance=${NotificationManager.IMPORTANCE_HIGH}; currentImportance=${channel?.importance}; hasUserSetImportance=${if (Build.VERSION.SDK_INT >= 29) channel?.hasUserSetImportance() else "unsupported"}"
        } catch (error: RuntimeException) {
            "channelRequestedId=$CHANNEL_ID; requestedImportance=${NotificationManager.IMPORTANCE_HIGH}; currentImportance=unknown:${error.javaClass.simpleName}; hasUserSetImportance=unknown"
        } catch (error: LinkageError) {
            "channelRequestedId=$CHANNEL_ID; requestedImportance=${NotificationManager.IMPORTANCE_HIGH}; currentImportance=unknown:${error.javaClass.simpleName}; hasUserSetImportance=unknown"
        }
    }

    private fun history(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("history", "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun appendHistory(text: String) {
        val entries = (history() + "${System.currentTimeMillis()} / ${SystemClock.elapsedRealtime()} $text").takeLast(20)
        prefs.edit().putString("history", JSONArray(entries).toString()).apply()
    }

    private fun recordError(label: String, error: Throwable) {
        val text = describe(label, error)
        prefs.edit().putString("lastError", text).apply()
        appendHistory(text)
    }

    private fun describe(label: String, error: Throwable) = "$label（${error.javaClass.simpleName}）"

    companion object {
        const val CHANNEL_ID = "vivo_island_identity_v2"
        const val NOTIFICATION_TAG = "vivo_island_probe"
        const val SUPERX_TAG = "VIVO_SUPERX_TAG"
        const val NOTIFICATION_ID = 41738
        const val ACTION_FINISH = "com.stuassistant.app.vivoprobe.FINISH"
        const val ACTION_DISMISSED = "com.stuassistant.app.vivoprobe.DISMISSED"
        const val EXTRA_SESSION = "vivo_probe_session"
        const val EXTRA_MODE = "vivo_probe_mode"
        const val TIMEOUT_MS = 600_000L
        val OBSERVATIONS = listOf("原子岛出现", "只有通知栏", "完全无显示", "未记录")
        private val LOCK = Any()
    }
}
