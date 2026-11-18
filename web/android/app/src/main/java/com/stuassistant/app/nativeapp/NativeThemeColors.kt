package com.stuassistant.app.nativeapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import com.stuassistant.app.glass.liquidGlassThemeProgress

/** Keep semantic and course colours on the same clock as the native shell. */
@Composable
internal fun currentNativeSemanticTones(): NativeSemanticTones {
    val palette = LocalNativePalette.current
    val light = remember(palette) { nativeSemanticTones(false, palette) }
    val dark = remember(palette) { nativeSemanticTones(true, palette) }
    val progress = liquidGlassThemeProgress()
    return NativeSemanticTones(
        soft = lerp(light.soft, dark.soft, progress),
        softInk = lerp(light.softInk, dark.softInk, progress),
        amber = lerp(light.amber, dark.amber, progress),
        dangerInk = lerp(light.dangerInk, dark.dangerInk, progress),
        leaf = lerp(light.leaf, dark.leaf, progress),
    )
}

@Composable
internal fun currentNativeCourseSwatch(hue: Int): NativeCourseSwatch {
    val palette = LocalNativePalette.current
    val light = remember(hue, palette) { nativeCourseSwatch(hue, false, palette) }
    val dark = remember(hue, palette) { nativeCourseSwatch(hue, true, palette) }
    val progress = liquidGlassThemeProgress()
    return NativeCourseSwatch(
        mark = lerp(light.mark, dark.mark, progress),
        soft = lerp(light.soft, dark.soft, progress),
        mid = lerp(light.mid, dark.mid, progress),
        ink = lerp(light.ink, dark.ink, progress),
    )
}
