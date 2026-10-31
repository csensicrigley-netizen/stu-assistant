package com.stuassistant.app.liveprobe

import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Bundle
import java.lang.reflect.InvocationTargetException

/** Results of read-only, undocumented OriginOS probes; neither is proof of a visible island. */
data class VivoSuperXStatus(val sceneStatus: String, val customFun: String)

/**
 * Independently written SuperX wire-format experiment using this app's own identity.
 * Protocol references (not a copied implementation): CunnyPlayground 9e9f245 and
 * originos-toolkit b72f9b9 docs/ORIGINISLAND.md and core/OriginIslandTemplates.kt.
 *
 * TRAIN is the upstream experiment's scene, not an assertion of a train booking or
 * granted vendor entitlement. These opaque extras can be ignored by the ROM.
 * No scene-registration setter, package substitution, hidden-API exemption, or
 * request on behalf of another package is performed here.
 *
 * Display targeting and force-show follow the Origin Isle author's device observations:
 * https://github.com/fvhde/origin-isle/blob/080f02785c81f8934014133802756495d6838a95/docs/PROTOCOL.md
 * These field facts are not evidence that this app has that author's package privileges.
 */
object VivoSuperXPayload {
    const val SCENE = "TRAIN"
    const val HYBRID_SCENE = "NAVIGATION"
    const val OPERATION = "notification.superx.operation"
    const val BASE_INFOS = "notification.superx.baseInfos"
    const val CAPSULE = "notification.superx.capsule"
    const val ISLAND = "notification.superx.island"

    /** All icon slots carry an Android Icon backed by this application's resources. */
    fun create(
        title: String,
        content: String,
        icon: Icon,
        clickIntent: PendingIntent,
        operation: Int = 0,
    ): Bundle {
        require(operation == 0) { "This experiment uses operation 0 for both create and update" }
        val base = Bundle().apply {
            putCharSequence("notification.superx.baseInfos.title", title)
            putCharSequence("notification.superx.baseInfos.content", content)
            putParcelable("notification.superx.baseInfos.icon", icon)
        }
        val capsule = Bundle().apply {
            putInt("notification.superx.capsule.state", 1)
            putCharSequence("notification.superx.capsule.content", title)
            putParcelable("notification.superx.capsule.icon", icon)
        }
        val info = Bundle().apply {
            putString("notification.superx.infos.describe", title)
            putString("notification.superx.infos.coreInfo", content)
            putParcelable("notification.superx.infos.image", icon)
        }
        val shortInfo = Bundle().apply {
            putString("notification.superx.shortInfos.describeShort", title)
            putString("notification.superx.shortInfos.coreInfoShort", content)
            putParcelable("notification.superx.shortInfos.image", icon)
        }
        val island = Bundle().apply {
            putBoolean("island.superx.forceShow", true)
            putInt("island.superx.leftTemplate", 1)
            putInt("island.superx.rightTemplate", 4)
            putBundle("island.superx.leftInfo", Bundle().apply {
                putString("island.superx.leftInfo.content", title)
                putParcelable("island.superx.leftInfo.icon", icon)
            })
            putBundle("island.superx.rightInfo", Bundle().apply {
                putString("island.superx.rightInfo.content", content)
                putParcelable("island.superx.rightInfo.icon", icon)
            })
        }
        return Bundle().apply {
            putInt(OPERATION, operation)
            putBoolean("notification.superx.showNotify", true)
            // Author-observed surfaces: notification | lock screen | status bar | AOD.
            putInt("notification.superx.displays", 65809)
            putInt("notification.superx.template", 1)
            putString("notification.superx.scene", SCENE)
            putInt("notification.superx.changedRecord", 0)
            putParcelable("notification.superx.clickResp", clickIntent)
            putBundle(BASE_INFOS, base)
            putBundle(CAPSULE, capsule)
            putBundle("notification.superx.infos", info)
            putBundle("notification.superx.shortInfos", shortInfo)
            putBundle(ISLAND, island)
        }
    }

    /**
     * Independent progress/expanded-card protocol comparison, based on fields in:
     * https://github.com/lingion/sleepy/blob/8b0729cf106e0dd167cabb66537989778639a50e/app/src/main/java/com/lingion/sleepy/widget/notification/VendorLiveCardRenderer.kt
     * Its X300 Pro report used a mixed standard/SuperX foreground-service notification.
     * This builds extras only; it neither starts a service nor registers any scene.
     */
    fun createHybrid(
        title: String,
        content: String,
        progress: Int,
        icon: Icon,
        clickIntent: PendingIntent,
        operation: Int = 0,
    ): Bundle {
        require(operation in 0..1) { "Use endExtras() to end the experiment" }
        val percent = progress.coerceIn(0, 100)
        val progressState = if (percent < 100) 0 else 1
        return create(title, content, icon, clickIntent).apply {
            putInt(OPERATION, operation)
            putString("notification.superx.scene", HYBRID_SCENE)
            putInt("notification.superx.template", 2)
            // This manual probe uses its monotonic progress as the change record.
            putInt("notification.superx.changedRecord", percent)
            putBoolean("notification.superx.islandNotify", true)
            getBundle(BASE_INFOS)?.putInt("notification.superx.baseInfos.progressState", progressState)
            putBundle("notification.superx.infos", Bundle().apply {
                putInt("notification.superx.infos.progress", percent)
                putParcelableArrayList("notification.superx.infos.nodeIcon", arrayListOf(icon, icon))
            })
            getBundle("notification.superx.shortInfos")?.putParcelable(
                "notification.superx.shortInfos.imageClickResp", clickIntent,
            )
            getBundle(CAPSULE)?.apply {
                putString("notification.superx.capsule.content", "$percent%")
                putParcelable("notification.superx.capsule.clickResp", clickIntent)
            }
            putBundle(ISLAND, Bundle().apply {
                putInt("island.superx.leftTemplate", 1)
                putInt("island.superx.rightTemplate", 2)
                putBoolean("island.superx.forceShow", true)
                putBoolean("island.superx.showBarWhenCard", false)
                putInt("island.superx.template", 4)
                putInt("island.superx.islandClick", 0)
                putParcelable("island.superx.clickResp", clickIntent)
                putBundle("island.superx.baseInfos", Bundle().apply {
                    // The expanded-card bundle retains notification.superx.* inner keys.
                    putParcelable("notification.superx.baseInfos.icon", icon)
                    putCharSequence("notification.superx.baseInfos.title", title)
                    putCharSequence("notification.superx.baseInfos.content", content)
                    putInt("notification.superx.baseInfos.subInfo", 5)
                    putInt("notification.superx.baseInfos.subProgress", percent)
                    putInt("notification.superx.baseInfos.progressState", progressState)
                    putInt("notification.superx.baseInfos.subProgressColor", PROGRESS_COLOR)
                })
                putBundle("island.superx.leftInfo", Bundle().apply {
                    putString("island.superx.leftInfo.content", title)
                    putParcelable("island.superx.leftInfo.icon", icon)
                })
                putBundle("island.superx.rightInfo", Bundle().apply {
                    putInt("island.superx.rightInfo.progressValue", percent)
                    putInt("island.superx.rightInfo.progressState", progressState)
                    putInt("island.superx.rightInfo.progressColor", PROGRESS_COLOR)
                    putString("island.superx.rightInfo.progressContent", "$percent%")
                    putParcelable("island.superx.rightInfo.clickResp", clickIntent)
                })
            })
        }
    }

    fun endExtras(): Bundle = Bundle().apply { putInt(OPERATION, 2) }

    /** Reports payload retention only; the sender and renderer can have different acceptance rules. */
    fun extrasObserved(extras: Bundle, scene: String = SCENE): Boolean =
        extras.containsKey(OPERATION) &&
            extras.getString("notification.superx.scene") == scene &&
            extras.getBundle(BASE_INFOS)?.containsKey("notification.superx.baseInfos.title") == true &&
            extras.getBundle(CAPSULE)?.getInt("notification.superx.capsule.state", -1) == 1 &&
            extras.getBundle(ISLAND)?.getInt("island.superx.rightTemplate", -1) ==
                (if (scene == HYBRID_SCENE) 2 else 4)

    /** Calls only getters for the actual calling package and this experiment's scene. */
    fun readStatus(manager: NotificationManager, packageName: String, scene: String = SCENE): VivoSuperXStatus = VivoSuperXStatus(
        sceneStatus = readBoolean(manager, "getSceneStatus", packageName, scene),
        customFun = readBoolean(manager, "isSupportCustomFun", packageName, scene),
    )

    private fun readBoolean(manager: NotificationManager, name: String, packageName: String, scene: String): String {
        return try {
            val method = NotificationManager::class.java.getMethod(name, String::class.java, String::class.java)
            when (val value = method.invoke(manager, packageName, scene)) {
                is Boolean -> value.toString()
                null -> "unknown:null_return"
                else -> "unknown:return_type_${value.javaClass.simpleName}"
            }
        } catch (_: NoSuchMethodException) {
            "unknown:method_absent_or_hidden"
        } catch (error: InvocationTargetException) {
            describe(error.targetException ?: error)
        } catch (error: Exception) {
            describe(error)
        } catch (error: LinkageError) {
            describe(error)
        }
    }

    private fun describe(error: Throwable): String {
        val message = error.message.orEmpty().replace('\n', ' ').replace('\r', ' ').take(160)
        return "unknown:${error.javaClass.simpleName}${if (message.isEmpty()) "" else ":$message"}"
    }

    private const val PROGRESS_COLOR = -13800225 // opaque blue, 0xFF2D6CDF
}
