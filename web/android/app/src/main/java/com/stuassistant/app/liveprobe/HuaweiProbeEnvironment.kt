package com.stuassistant.app.liveprobe

import android.os.Build
import java.util.Locale

/** Public APK-runtime information, not detection of the host OS or Live View entitlement. */
data class HuaweiProbeEnvironment(
    val familyLabel: String,
    val routeLabel: String,
    val hint: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val release: String,
    val sdk: Int,
    val display: String,
) {
    fun diagnosticText(): String = buildString {
        appendLine("APK 运行环境；不据此推断宿主 HarmonyOS 版本或华为 SDK 资格")
        appendLine("family=$familyLabel")
        appendLine("manufacturer=$manufacturer; brand=$brand; model=$model")
        appendLine("runtimeRelease=$release; runtimeSdk=$sdk")
        appendLine("runtimeDisplay=$display")
        appendLine("route=$routeLabel")
        appendLine("hostLiveView=未验证，需实机观察")
        append("hint=$hint")
    }

    companion object {
        fun detect(): HuaweiProbeEnvironment = classify(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            display = Build.DISPLAY,
        )

        /** Classification uses public runtime identity only; a compatibility layer may report its own identity. */
        fun classify(
            manufacturer: String,
            brand: String,
            model: String,
            release: String,
            sdk: Int,
            display: String,
        ): HuaweiProbeEnvironment {
            val maker = manufacturer.trim().uppercase(Locale.ROOT)
            val deviceBrand = brand.trim().uppercase(Locale.ROOT)
            // Older HONOR devices can still report HUAWEI as their manufacturer.
            val honor = deviceBrand == "HONOR" || maker == "HONOR"
            val huawei = !honor && (deviceBrand == "HUAWEI" || maker == "HUAWEI")
            val family = when {
                honor -> "荣耀 APK 运行环境（非华为）"
                huawei -> "华为 APK 运行环境"
                else -> "第三方/兼容运行环境（宿主需确认）"
            }
            val route = if (sdk >= 36) "标准 Android 提升请求" else "普通 Android 通知基线"
            val routeHint = when {
                sdk >= 36 -> "可尝试标准提升，不代表宿主实况窗成功。"
                huawei -> "以普通通知为基线，华为本地扩展需实机尝试。"
                else -> "以普通通知为基线，扩展及宿主能力需实机确认。"
            }
            return HuaweiProbeEnvironment(
                familyLabel = family,
                routeLabel = route,
                hint = routeHint + "Android/API 仅表示 APK 运行环境，不代表宿主 HarmonyOS 版本；是否转发宿主实况窗需观察。",
                manufacturer = manufacturer,
                brand = brand,
                model = model,
                release = release,
                sdk = sdk,
                display = display,
            )
        }
    }
}
