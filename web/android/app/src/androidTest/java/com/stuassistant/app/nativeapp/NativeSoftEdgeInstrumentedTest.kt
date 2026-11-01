package com.stuassistant.app.nativeapp

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.stuassistant.app.NativeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Pixel regression: the real GPU effect must soften only the top viewport band. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class NativeSoftEdgeInstrumentedTest {
    @Test fun topSoftensWhileBodyAndBottomRemainUnchanged() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val enabled = mutableStateOf(false)
        var bounds = androidx.compose.ui.geometry.Rect.Zero
        val scroll = ScrollState(1000)
        ActivityScenario.launch<NativeActivity>(Intent(inst.targetContext, NativeActivity::class.java)
            .putExtra("preview", true)).use { scenario ->
            SystemClock.sleep(1200)
            scenario.onActivity { activity -> activity.setContent {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(300.dp, 400.dp).onGloballyPositioned { bounds = it.boundsInWindow() }
                        .then(if(enabled.value) Modifier.nativeTopSoftEdge(scroll, 48.dp) else Modifier)) {
                        drawRect(Color.White)
                        val stripe = 3.dp.toPx()
                        var x = 0f
                        while(x < size.width) {
                            drawRect(Color.Black, Offset(x,0f), Size(stripe,size.height))
                            x += stripe * 2
                        }
                    }
                }
            } }
            inst.waitForIdleSync(); SystemClock.sleep(500)
            val before = inst.uiAutomation.takeScreenshot()
            scenario.onActivity { enabled.value = true }
            inst.waitForIdleSync(); SystemClock.sleep(700)
            val after = inst.uiAutomation.takeScreenshot()
            val density = inst.targetContext.resources.displayMetrics.density
            val left = bounds.left.toInt() + (30*density).toInt()
            val right = bounds.right.toInt() - (30*density).toInt()
            fun difference(yDp: Int): Double {
                val y = bounds.top.toInt() + (yDp*density).toInt()
                return (left until right).map { x ->
                    abs(android.graphics.Color.red(before.getPixel(x,y)) - android.graphics.Color.red(after.getPixel(x,y)))
                }.average()
            }
            assertTrue("Top must actually blur on the GPU", difference(8) > 20)
            val topY = bounds.top.toInt() + (8*density).toInt()
            val topPixels = (left until right).map { android.graphics.Color.red(after.getPixel(it,topY)) }
            assertTrue("Blur must suppress stripe contrast, beyond tint alone", topPixels.max() - topPixels.min() < 60)
            assertTrue("Middle content must stay sharp", difference(160) < 1)
            assertTrue("Bottom must remain unchanged", difference(360) < 1)
            before.recycle(); after.recycle()
        }
    }
}
