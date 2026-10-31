package com.stuassistant.app.liveprobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuaweiProbeEnvironmentTest {
    private fun environment(
        manufacturer: String = "HUAWEI",
        brand: String = "HUAWEI",
        sdk: Int = 35,
    ) = HuaweiProbeEnvironment.classify(manufacturer, brand, "Test model", "runtime release", sdk, "full build")

    @Test fun honorBrandOverridesLegacyHuaweiManufacturer() {
        val result = environment(manufacturer = "HUAWEI", brand = " honor ")
        assertEquals("荣耀 APK 运行环境（非华为）", result.familyLabel)
        assertFalse(result.hint.contains("华为本地扩展"))
    }

    @Test fun huaweiIdentityIsCaseInsensitiveButDoesNotGuessHostVersion() {
        val result = environment(manufacturer = " hUaWeI ", brand = "generic")
        assertEquals("华为 APK 运行环境", result.familyLabel)
        assertTrue(result.hint.contains("不代表宿主 HarmonyOS 版本"))
        assertTrue(result.hint.contains("华为本地扩展需实机尝试"))
    }

    @Test fun genericOrEmulatorIdentityDoesNotBecomeHuawei() {
        val result = environment(manufacturer = "Google", brand = "google", sdk = 37)
        assertEquals("第三方/兼容运行环境（宿主需确认）", result.familyLabel)
        assertTrue(result.diagnosticText().contains("hostLiveView=未验证"))
    }

    @Test fun standardPromotionRouteBeginsAtSdk36WithoutClaimingVisualSuccess() {
        assertEquals("普通 Android 通知基线", environment(sdk = 35).routeLabel)
        val result = environment(sdk = 36)
        assertEquals("标准 Android 提升请求", result.routeLabel)
        assertTrue(result.hint.contains("不代表宿主实况窗成功"))
    }

    @Test fun diagnosticPreservesPublicRuntimeValuesVerbatim() {
        val result = HuaweiProbeEnvironment.classify("Maker", "Brand", "Model", "Release", 37, "Full build (region)")
        val text = result.diagnosticText()
        assertTrue(text.contains("manufacturer=Maker; brand=Brand; model=Model"))
        assertTrue(text.contains("runtimeRelease=Release; runtimeSdk=37"))
        assertTrue(text.contains("runtimeDisplay=Full build (region)"))
        assertEquals("Full build (region)", result.display)
    }
}
