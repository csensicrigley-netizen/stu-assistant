package com.stuassistant.app.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SundialLaunchCurveTest {

    @Test
    fun scurveRevealCurve_hasSlowStartFastMiddleAndLongSlowTail() {
        assertEquals(0f, scurveRevealCurve(0f), 1e-6f)
        assertEquals(1f, scurveRevealCurve(1f), 1e-6f)

        var prev = 0f
        for (i in 1..1000) {
            val u = i / 1000f
            val y = scurveRevealCurve(u)
            assertTrue("Expected monotonic S-curve at u=$u ($y >= $prev)", y >= prev)
            prev = y
        }

        // Slow start (Ease-In): at u=0.15, still gently gathering momentum (< 9%)
        val early = scurveRevealCurve(0.15f)
        assertTrue("Expected gentle slow start at u=0.15, got $early", early in 0.01f..0.09f)

        // Peak velocity in middle (u=0.25..0.45) > early slope and > tail slope
        val dyEarly = (scurveRevealCurve(0.15f) - scurveRevealCurve(0.05f)) / 0.10f
        val dyMid = (scurveRevealCurve(0.40f) - scurveRevealCurve(0.30f)) / 0.10f
        val dyTail = (scurveRevealCurve(0.85f) - scurveRevealCurve(0.75f)) / 0.10f
        assertTrue("Expected mid slope ($dyMid) > early slope ($dyEarly)", dyMid > dyEarly * 2.2f)
        assertTrue("Expected mid slope ($dyMid) > tail slope ($dyTail)", dyMid > dyTail * 3.0f)
    }

    @Test
    fun stepFrame_clampsFirstFrameSpikeAndCompletesHarmonicBreathBeforeReveal() {
        val state = SundialLaunchState(activeInitially = true)

        // Simulate a 250ms Frame-1 layout spike when ready is already true:
        // Clamped to 24ms so animation NEVER jumps straight to 60% open!
        assertFalse(state.stepFrame(rawFrameDeltaMs = 250f, isReady = true, reducedMotion = false))
        assertEquals(24f, state.virtualElapsedMs, 1e-4f)
        assertFalse("Must complete harmonic breath beat before opening iris", state.irisActive)

        // Advance smoothly at 16ms frames until harmonic release (350ms)
        repeat(20) {
            state.stepFrame(rawFrameDeltaMs = 16f, isReady = true, reducedMotion = false)
        }
        assertFalse(state.irisActive) // 24 + 320 = 344ms < 350ms

        // Next 16ms frame crosses 350ms -> triggers seamless handoff at irisElapsedMs = 0f!
        assertFalse(state.stepFrame(rawFrameDeltaMs = 16f, isReady = true, reducedMotion = false))
        assertTrue(state.irisActive)
        assertEquals(0f, state.irisElapsedMs, 1e-4f)
        assertTrue(state.scaleAtTrigger in 0.960f..0.970f)
    }

    @Test
    fun launcherThemeSync_mapsAllThemeModesToExpectedAliases() {
        assertEquals(LauncherThemeSync.ALIAS_LIGHT, LauncherThemeSync.targetAliasForTheme("light"))
        assertEquals(LauncherThemeSync.ALIAS_DARK, LauncherThemeSync.targetAliasForTheme("dark"))
        assertEquals(LauncherThemeSync.ALIAS_SYSTEM, LauncherThemeSync.targetAliasForTheme("system"))
        assertEquals(LauncherThemeSync.ALIAS_SYSTEM, LauncherThemeSync.targetAliasForTheme("unexpected"))
    }
}
