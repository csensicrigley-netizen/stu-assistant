package com.stuassistant.app.liveprobe

import android.Manifest
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification

/** Self-only, read-only diagnostics. Missing ROM APIs and unknown operations are not denials. */
object VivoProbeDiagnostics {
    private const val PROMOTED_PERMISSION = "android.permission.POST_PROMOTED_NOTIFICATIONS"
    private const val PROMOTED_OP = "android:post_promoted_notifications"
    private const val NOTIFICATION_OP = "android:post_notification"
    private const val REQUEST_PROMOTED_EXTRA = "android.requestPromotedOngoing"

    /** The caller selects its own probe notification; this method never posts or changes permissions. */
    @Suppress("DEPRECATION")
    fun capture(context: Context, posted: StatusBarNotification? = null): String {
        val app = context.applicationContext
        val capturedAt = System.currentTimeMillis()
        val capturedElapsed = SystemClock.elapsedRealtime()
        val info = try { app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS) }
        catch (_: RuntimeException) { null }
        catch (_: PackageManager.NameNotFoundException) { null }
        val manager = app.getSystemService(NotificationManager::class.java)
        return buildString {
            appendLine("capturedAtEpochMs=$capturedAt; elapsedRealtimeMs=$capturedElapsed")
            appendLine("package=${app.packageName}; uid=${Process.myUid()}")
            val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() }
            appendLine("version=${info?.versionName ?: "unknown"} (${code ?: "unknown"}); targetSdk=${app.applicationInfo.targetSdkVersion}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}; brand=${Build.BRAND}; Android=${Build.VERSION.RELEASE}; sdk=${Build.VERSION.SDK_INT}")
            appendLine("build=${Build.DISPLAY}; securityPatch=${Build.VERSION.SECURITY_PATCH}")
            appendLine(permissionLine(app, info, Manifest.permission.POST_NOTIFICATIONS))
            appendLine(permissionLine(app, info, PROMOTED_PERMISSION))
            appendLine(appOpLine(app, Manifest.permission.POST_NOTIFICATIONS, NOTIFICATION_OP))
            appendLine(appOpLine(app, PROMOTED_PERMISSION, PROMOTED_OP))
            appendLine("notificationsEnabled=${read { manager?.areNotificationsEnabled() ?: "unknown(no manager)" }}")
            appendLine("promotionAllowed=${if (Build.VERSION.SDK_INT >= 36) read { manager?.canPostPromotedNotifications() ?: "unknown(no manager)" } else "unavailable(API<36)"}")
            appendLine("AppOps 为本应用的只读原始值；DEFAULT 不等于拒绝，未知 API/权限/操作不等于拒绝。")
            if (posted == null) {
                append("posted=none at capture; missing now does not prove never posted")
            } else if (posted.packageName != app.packageName || posted.user != Process.myUserHandle() ||
                (Build.VERSION.SDK_INT >= 29 && posted.uid != Process.myUid())) {
                append("posted=ignored(non-self notification)")
            } else {
                val n = posted.notification
                appendLine("posted=true; id=${posted.id}; tag=${posted.tag}; postTimeEpochMs=${posted.postTime}; postAgeWallMs=${capturedAt - posted.postTime}")
                appendLine("posted.flags=0x${Integer.toHexString(n.flags)}; ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0}; promoted=${if (Build.VERSION.SDK_INT >= 36) n.flags and Notification.FLAG_PROMOTED_ONGOING != 0 else "unavailable(API<36)"}")
                appendLine("posted.template=${read { n.extras?.getString(Notification.EXTRA_TEMPLATE) ?: "none" }}; category=${n.category}; group=${n.group}; visibility=${n.visibility}")
                appendLine("posted.requestPromoted=${read { n.extras?.getBoolean(REQUEST_PROMOTED_EXTRA, false) ?: false }}; colorized=${read { n.extras?.getBoolean(Notification.EXTRA_COLORIZED, false) ?: false }}")
                appendLine("posted.eligible=${if (Build.VERSION.SDK_INT >= 36) read { n.hasPromotableCharacteristics() } else "unavailable(API<36)"}")
                appendLine("posted.customViews=${n.contentView != null || n.bigContentView != null || n.headsUpContentView != null}; groupSummary=${n.flags and Notification.FLAG_GROUP_SUMMARY != 0}; actions=${n.actions?.size ?: 0}")
                appendLine("posted.whenEpochMs=${n.`when`}; showWhen=${read { n.extras?.getBoolean(Notification.EXTRA_SHOW_WHEN, false) ?: false }}; chronometer=${read { n.extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) ?: false }}; countdown=${read { n.extras?.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false) ?: false }}")
                appendLine("posted.shortCriticalText=${if (Build.VERSION.SDK_INT >= 36) read { n.shortCriticalText ?: "none" } else "unavailable(API<36)"}")
                appendLine("posted.timeoutAfterMs=${if (Build.VERSION.SDK_INT >= 26) read { n.timeoutAfter } else "unavailable(API<26)"}")
                appendLine(superXFields(n.extras))
                if (Build.VERSION.SDK_INT >= 26) {
                    append("posted.channel=${n.channelId}; importance=${read { manager?.getNotificationChannel(n.channelId)?.importance ?: "unknown(no channel)" }}")
                } else append("posted.channel=unavailable(API<26)")
            }
        }
    }

    /** Raw readback only. Retained opaque extras do not prove a system island was rendered. */
    @Suppress("DEPRECATION")
    private fun superXFields(extras: Bundle?): String {
        fun nested(parent: Bundle?, key: String): Bundle? = try { parent?.getBundle(key) }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
        fun raw(parent: Bundle?, key: String): String = read { parent?.get(key)?.toString() ?: "null" }
        fun bundlePresent(parent: Bundle?, key: String): String = read {
            when {
                parent == null || !parent.containsKey(key) -> false
                parent.get(key) is Bundle -> true
                else -> "present(non-Bundle/null)"
            }
        }
        val island = nested(extras, "notification.superx.island")
        val capsule = nested(extras, "notification.superx.capsule")
        val base = nested(extras, "notification.superx.baseInfos")
        val info = nested(extras, "notification.superx.infos")
        val expanded = nested(island, "island.superx.baseInfos")
        val right = nested(island, "island.superx.rightInfo")
        return buildString {
            appendLine("posted.SuperX raw extras only; retention is not rendering; missing scalar=null")
            appendLine("posted.SuperX.operation=${raw(extras, "notification.superx.operation")}; scene=${raw(extras, "notification.superx.scene")}; template=${raw(extras, "notification.superx.template")}; displays=${raw(extras, "notification.superx.displays")}")
            appendLine("posted.SuperX.islandNotify=${raw(extras, "notification.superx.islandNotify")}; showNotify=${raw(extras, "notification.superx.showNotify")}; changedRecord=${raw(extras, "notification.superx.changedRecord")}")
            appendLine("posted.SuperX.bundles.baseInfos=${bundlePresent(extras, "notification.superx.baseInfos")}; infos=${bundlePresent(extras, "notification.superx.infos")}; shortInfos=${bundlePresent(extras, "notification.superx.shortInfos")}; capsule=${bundlePresent(extras, "notification.superx.capsule")}; island=${bundlePresent(extras, "notification.superx.island")}")
            appendLine("posted.SuperX.island.leftTemplate=${raw(island, "island.superx.leftTemplate")}; rightTemplate=${raw(island, "island.superx.rightTemplate")}; expandedTemplate=${raw(island, "island.superx.template")}; forceShow=${raw(island, "island.superx.forceShow")}; islandClick=${raw(island, "island.superx.islandClick")}; showBarWhenCard=${raw(island, "island.superx.showBarWhenCard")}")
            appendLine("posted.SuperX.island.bundles.leftInfo=${bundlePresent(island, "island.superx.leftInfo")}; rightInfo=${bundlePresent(island, "island.superx.rightInfo")}; expandedBaseInfos=${bundlePresent(island, "island.superx.baseInfos")}")
            appendLine("posted.SuperX.capsuleState=${raw(capsule, "notification.superx.capsule.state")}; baseProgressState=${raw(base, "notification.superx.baseInfos.progressState")}; infoProgress=${raw(info, "notification.superx.infos.progress")}")
            append("posted.SuperX.expanded.subInfo=${raw(expanded, "notification.superx.baseInfos.subInfo")}; subProgress=${raw(expanded, "notification.superx.baseInfos.subProgress")}; progressState=${raw(expanded, "notification.superx.baseInfos.progressState")}; rightProgressValue=${raw(right, "island.superx.rightInfo.progressValue")}; rightProgressState=${raw(right, "island.superx.rightInfo.progressState")}")
        }
    }

    private fun permissionLine(app: Context, info: PackageInfo?, permission: String): String {
        val known = try { app.packageManager.getPermissionInfo(permission, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
        catch (_: RuntimeException) { null }
        catch (_: LinkageError) { null }
        val index = info?.requestedPermissions?.indexOf(permission) ?: -1
        val declared: Any = if (info == null) "unknown(package info)" else index >= 0
        val packageGranted: Any = if (info == null) "unknown(package info)" else if (index < 0) "not declared"
            else info.requestedPermissionsFlags?.getOrNull(index)?.let { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                ?: "unknown(package flags)"
        val granted = when (known) {
            true -> read { app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }
            false -> "unknown(permission absent on ROM)"
            null -> "unknown(permission lookup failed)"
        }
        return "permission=$permission; known=${known ?: "unknown"}; declared=$declared; packageGrantedBit=$packageGranted; checkSelfPermission=$granted"
    }

    @Suppress("DEPRECATION")
    private fun appOpLine(app: Context, permission: String, fallbackOp: String): String {
        var mappingError: String? = null
        val mapped = try { AppOpsManager.permissionToOp(permission) }
        catch (error: RuntimeException) { mappingError = error.javaClass.simpleName; null }
        catch (error: LinkageError) { mappingError = error.javaClass.simpleName; null }
        val op = mapped ?: fallbackOp
        val prefix = "appOp=$op; permissionToOp=${mapped ?: "unmapped"}${mappingError?.let { "($it)" } ?: ""}"
        val appOps = app.getSystemService(AppOpsManager::class.java)
            ?: return "$prefix; recognized=unknown; rawMode=unknown(no manager)"
        // strOpToOp is not a public SDK API. Successfully querying the public string API
        // is evidence the ROM recognizes this op; IllegalArgumentException means it does not.
        return try {
            val (mode, method, raw) = if (Build.VERSION.SDK_INT >= 36) {
                try {
                    Triple(appOps.checkOpRawNoThrow(op, Process.myUid(), app.packageName, null), "checkOpRawNoThrow", true)
                } catch (_: NoSuchMethodError) {
                    Triple(appOps.unsafeCheckOpRawNoThrow(op, Process.myUid(), app.packageName), "unsafeCheckOpRawNoThrow(ROM fallback)", true)
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                Triple(appOps.unsafeCheckOpRawNoThrow(op, Process.myUid(), app.packageName), "unsafeCheckOpRawNoThrow", true)
            } else {
                Triple(appOps.checkOpNoThrow(op, Process.myUid(), app.packageName), "checkOpNoThrow", false)
            }
            "$prefix; recognized=true; mode=$mode(${modeName(mode)}); raw=$raw; method=$method"
        } catch (_: IllegalArgumentException) {
            "$prefix; recognized=false; rawMode=unknown(ROM does not recognize op)"
        } catch (error: RuntimeException) {
            "$prefix; recognized=unknown; rawMode=unknown(${error.javaClass.simpleName})"
        } catch (error: LinkageError) {
            "$prefix; recognized=unknown; rawMode=unknown(${error.javaClass.simpleName})"
        }
    }

    private fun modeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "ALLOWED"
        AppOpsManager.MODE_IGNORED -> "IGNORED"
        AppOpsManager.MODE_ERRORED -> "ERRORED"
        AppOpsManager.MODE_DEFAULT -> "DEFAULT"
        AppOpsManager.MODE_FOREGROUND -> "FOREGROUND"
        else -> "unrecognized mode"
    }

    private inline fun read(action: () -> Any): String = try { action().toString() }
    catch (error: RuntimeException) { "unknown(${error.javaClass.simpleName})" }
    catch (error: LinkageError) { "unknown(${error.javaClass.simpleName})" }
}
