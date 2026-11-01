package com.stuassistant.app.nativeapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.NativeActivity
import com.stuassistant.app.glass.LocalLiquidGlassReducedMotion
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Pixel evidence catches blank digits even when accessibility reports their value. */
@RunWith(AndroidJUnit4::class)
class NativeCalendarCounterInstrumentedTest {
    @Test fun rapidUpdatesStayVisibleAndSettleIncludingRollover() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val bounds = AtomicReference(Rect())
        val value = mutableStateOf("123456")
        val running = mutableStateOf(false)
        val reduced = mutableStateOf(false)
        ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext, NativeActivity::class.java).putExtra("preview", true)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                LaunchedEffect(running.value) {
                    if (running.value) repeat(1200) { index ->
                        if (!running.value) return@LaunchedEffect
                        // All cells change, in both directions, faster than one transition.
                        value.value = (100000 + ((index * 111119L) % 900000)).toString()
                        delay(16)
                    }
                }
                CompositionLocalProvider(LocalLiquidGlassReducedMotion provides reduced.value) {
                    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
                        CalendarCounter(value.value, value.value.toLong(), TextStyle(color = Color.Black, fontSize = 32.sp),
                            Modifier.onGloballyPositioned { node ->
                                val r = node.boundsInWindow()
                                bounds.set(Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt()))
                            })
                    }
                }
            } }
            Thread.sleep(800)
            fun ink(bitmap: Bitmap): Int {
                val r = bounds.get()
                var count = 0
                for (y in r.top.coerceAtLeast(0) until r.bottom.coerceAtMost(bitmap.height)) {
                    for (x in r.left.coerceAtLeast(0) until r.right.coerceAtMost(bitmap.width)) {
                        if (android.graphics.Color.red(bitmap.getPixel(x, y)) < 180) count++
                    }
                }
                return count
            }
            fun shot(name: String? = null): Bitmap = inst.uiAutomation.takeScreenshot().also { bmp ->
                if (name != null) File(inst.targetContext.getExternalFilesDir(null), name).outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val baseline = shot().let { bmp -> ink(bmp).also { bmp.recycle() } }
            assertTrue("Baseline text must be visible", baseline > 100)
            scenario.onActivity { running.value = true }
            repeat(16) { index ->
                Thread.sleep(45)
                val bmp = shot(if (index == 8) "calendar-counter-fast.png" else null)
                assertTrue("Fast update frame $index became blank", ink(bmp) > baseline * .3)
                bmp.recycle()
            }
            scenario.onActivity { running.value = false }
            inst.waitForIdleSync()
            scenario.onActivity { value.value = "1000000" }
            Thread.sleep(900)
            inst.uiAutomation.clearCache()
            assertTrue("Latest rollover value must be displayed",
                inst.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("1000000").isNotEmpty())
            val settled = shot("calendar-counter-settled.png")
            assertTrue("Rollover must settle into readable digits", ink(settled) > baseline * .7)
            settled.recycle()
            scenario.onActivity { reduced.value = true; value.value = "235959" }
            Thread.sleep(100)
            val still = shot()
            assertTrue("Reduced motion must keep text visible", ink(still) > baseline * .7)
            still.recycle()
        }
    }
}
