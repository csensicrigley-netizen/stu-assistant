package com.stuassistant.app.nativeapp

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.withFrameNanos
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.round

/** Simulated display clocks verify interpolation, not physical-device rendering speed. */
@RunWith(AndroidJUnit4::class)
class NativeAnnouncementMotionInstrumentedTest {
    private data class Run(val milliseconds: Double, val values: List<Float>)
    private var virtualNanos = 0L

    private fun clocked(hz: Int, value: () -> Float, action: suspend CoroutineScope.() -> Unit): Run = runBlocking {
        val clock = BroadcastFrameClock()
        val step = 1_000_000_000L / hz
        val started = virtualNanos
        val samples = mutableListOf<Float>()
        val job = launch(clock, start = CoroutineStart.UNDISPATCHED, block = action)
        while (job.isActive && samples.size < hz * 8) {
            val delivered = clock.hasAwaiters
            if (delivered) {
                virtualNanos += step
                clock.sendFrame(virtualNanos)
            }
            yield()
            if (delivered) samples += value()
        }
        assertTrue("Animation did not finish on the supplied display clock", job.isCompleted)
        job.join()
        Run((virtualNanos - started) / 1_000_000.0, samples)
    }

    @Test fun cardTransitionsInterpolateAt60_120_144HzWithoutChangingDuration() {
        val counts = mutableListOf<Int>()
        val report = StringBuilder("Simulated display clock; not physical FPS measurements.\n")
        for (hz in listOf(60, 120, 144)) {
            val motion = NativeDialogMotion()
            val entering = clocked(hz, { motion.arrival.value }) { motion.enter(false) }
            assertTrue("$hz Hz entrance duration", entering.milliseconds in 550.0..650.0)
            assertEquals(1f, motion.opacity.value, .0001f)
            counts += entering.values.filter { it > 0f && it < 1f }.distinct().size
            val leaving = clocked(hz, { motion.opacity.value }) { motion.exit(false) }
            assertTrue("$hz Hz exit duration: ${leaving.milliseconds}ms", leaving.milliseconds in 240.0..310.0)
            assertTrue("Exit needs visible intermediate opacity", leaving.values.count { it in .01f.. .99f } > hz / 12)
            assertEquals(0f, motion.scrim.value, .0001f)
            report.appendLine("$hz Hz: enter=${entering.milliseconds}ms, exit=${leaving.milliseconds}ms, distinct entrance samples=${counts.last()}")
        }
        assertTrue("120 Hz must receive substantially more steps than 60 Hz", counts[1] > counts[0] * 1.7)
        assertTrue("144 Hz must receive more steps than 120 Hz", counts[2] > counts[1])
        save("motion-clock-report.txt", report.toString())
    }

    @Test fun allLottieEffectsUseSubframesAt120And144Hz() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StringBuilder("Lottie interpolation with simulated VSYNC; not physical FPS.\n")
        for (effect in NativeAnnouncementRules.effectIds) {
            val composition = requireNotNull(LottieCompositionFactory.fromAssetSync(context, "announcement-effects/$effect.json").value)
            for (hz in listOf(120, 144)) {
                val playback = NativeAnnouncementPlayback()
                val result = clocked(hz, { playback.progress.value }) {
                    playback.play(composition.duration)
                }
                val frames = result.values.filter { it > 0f && it < 1f }
                assertTrue("$effect / $hz Hz must not quantize to source FPS", frames.distinct().size > composition.duration / 1000 * hz * .9)
                assertTrue("$effect needs fractional source frames", frames.any {
                    val frame = it * (composition.endFrame - composition.startFrame)
                    abs(frame - round(frame)) > .1f
                })
                assertTrue("$effect / $hz Hz duration=${result.milliseconds} vs ${composition.duration}", abs(result.milliseconds - composition.duration) < 60)
                report.appendLine("$effect at $hz Hz: ${result.milliseconds}ms, ${frames.distinct().size} distinct progress samples")
            }
        }
        save("lottie-clock-report.txt", report.toString())
    }

    @Test fun everyEffectAdvancesDuringCardEntranceAt60_120_144Hz() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (effect in NativeAnnouncementRules.effectIds) {
            val composition = requireNotNull(LottieCompositionFactory.fromAssetSync(context, "announcement-effects/$effect.json").value)
            for (hz in listOf(60, 120, 144)) {
                val motion = NativeDialogMotion()
                val playback = NativeAnnouncementPlayback()
                val overlapping = mutableListOf<Float>()
                clocked(hz, {
                    if (motion.arrival.value > 0f && motion.arrival.value < 1f && playback.progress.floatValue > 0f) {
                        overlapping += playback.progress.floatValue
                    }
                    playback.progress.floatValue
                }) {
                    launch { motion.enter(false) }
                    while (motion.startedAtNanos.value == null) withFrameNanos { }
                    playback.play(composition.duration, motion.startedAtNanos.value)
                }
                assertTrue("$effect / $hz Hz must play while the card is entering", overlapping.distinct().size > hz / 3)
                assertTrue("$effect / $hz Hz must advance through the entrance", overlapping.last() * composition.duration > 500)
            }
        }
    }

    @Test fun lateCompositionJoinsThePresentationTimelineInsteadOfRestartingFrameZero() {
        for (hz in listOf(60, 120, 144)) {
            val playback = NativeAnnouncementPlayback()
            var firstProgress = 0f
            val result = clocked(hz, {
                if (firstProgress == 0f && playback.progress.floatValue > 0f) firstProgress = playback.progress.floatValue
                playback.progress.floatValue
            }) {
                val started = withFrameNanos { it }
                // Simulate parsing/loading after the card already started entering.
                repeat(hz / 5) { withFrameNanos { } }
                playback.play(1000f, started)
            }
            assertTrue("$hz Hz must catch up on its first rendered frame", firstProgress in .19f.. .25f)
            assertTrue("$hz Hz must finish with the original timeline", result.milliseconds in 1000.0..1060.0)
        }
    }

    private fun save(name: String, text: String) {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name).writeText(text)
    }
}
