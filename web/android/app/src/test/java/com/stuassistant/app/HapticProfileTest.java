package com.stuassistant.app;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class HapticProfileTest {
    private static final String[] SEMANTIC = { "selection", "toggle", "confirm", "reject", "preview" };

    @Test public void semanticRolesHaveDistinctBoundedGains() {
        float[] gains = { .16f, .30f, .46f, .22f, .32f };
        for (int i = 0; i < SEMANTIC.length; i++) {
            HapticProfile full = HapticProfile.forKind(SEMANTIC[i], 100);
            HapticProfile clipped = HapticProfile.forKind(SEMANTIC[i], 500);
            assertNotNull(full);
            assertTrue(HapticProfile.isSemantic(SEMANTIC[i]));
            assertFalse(full.legacy());
            assertEquals(gains[i], full.primitiveScale(0), .000001f);
            assertEquals(full.primitiveScale(0), clipped.primitiveScale(0), .000001f);
            assertEquals(Math.round(gains[i] * 100), full.richTapIntensity(0));
        }
        assertTrue(HapticProfile.forKind("selection", 100).primitiveScale(0)
            < HapticProfile.forKind("confirm", 100).primitiveScale(0));
    }

    @Test public void gainIsMonotonicAcrossEveryStrengthAndZeroIsSilentInEveryBackend() {
        for (String kind : new String[] { "selection", "toggle", "confirm", "reject", "preview", "press", "release" }) {
            HapticProfile zero = HapticProfile.forKind(kind, -1);
            assertTrue(zero.silent());
            for (int amplitude : zero.waveformAmplitudes()) assertEquals(0, amplitude);
            float[] previousScale = new float[zero.pulseCount()];
            int[] previousIntensity = new int[zero.pulseCount()];
            int[] previousAmplitude = zero.waveformAmplitudes();
            for (int strength = 0; strength <= 100; strength++) {
                HapticProfile profile = HapticProfile.forKind(kind, strength);
                assertEquals(strength == 0, profile.silent());
                for (int pulse = 0; pulse < profile.pulseCount(); pulse++) {
                    float scale = profile.primitiveScale(pulse);
                    int intensity = profile.richTapIntensity(pulse);
                    assertTrue(kind + " scale must not decrease", scale >= previousScale[pulse]);
                    assertTrue(kind + " vendor intensity must not decrease", intensity >= previousIntensity[pulse]);
                    assertTrue(scale >= 0f && scale <= 1f);
                    assertTrue(intensity >= 0 && intensity <= 100);
                    if (strength == 0) {
                        assertEquals(0f, scale, 0f);
                        assertEquals(0, intensity);
                    } else {
                        assertTrue(scale > 0f);
                        assertTrue(intensity > 0);
                    }
                    previousScale[pulse] = scale;
                    previousIntensity[pulse] = intensity;
                }
                int[] amplitudes = profile.waveformAmplitudes();
                assertEquals(profile.waveformTimings().length, amplitudes.length);
                for (int i = 0; i < amplitudes.length; i++) {
                    assertTrue(kind + " fallback gain must not decrease", amplitudes[i] >= previousAmplitude[i]);
                    assertTrue(amplitudes[i] >= 0 && amplitudes[i] <= 255);
                }
                previousAmplitude = amplitudes;
            }
        }
    }

    @Test public void confirmIsOnePulseAndRejectKeepsItsTwoOnsetsAcrossFallbacks() {
        HapticProfile confirm = HapticProfile.forKind("confirm", 100);
        HapticProfile reject = HapticProfile.forKind("reject", 100);
        assertEquals(1, confirm.pulseCount());
        assertEquals(0, confirm.onsetMs(0));
        assertEquals(2, reject.pulseCount());
        assertEquals(0, reject.onsetMs(0));
        assertEquals(60, reject.onsetMs(1));
        assertEquals(.85f, reject.primitiveScale(1) / reject.primitiveScale(0), .000001f);
        assertArrayEquals(new long[] { 0 }, amplitudePulseOnsets(confirm));
        assertArrayEquals(new long[] { 0 }, unscaledPulseOnsets(confirm.unscaledTimings()));
        assertArrayEquals(new long[] { 0, 60 }, amplitudePulseOnsets(reject));
        assertArrayEquals(new long[] { 0, 60 }, unscaledPulseOnsets(reject.unscaledTimings()));
        // API 30 delays are after the previous primitive's duration, not after its onset.
        for (int duration : new int[] { 0, 1, 8, 25, 59, 60 }) {
            assertEquals(0, reject.primitiveGapMs(0, duration));
            assertEquals(60, duration + reject.primitiveGapMs(1, duration));
        }
        assertEquals(0, reject.primitiveGapMs(1, 80));
        assertTrue(reject.waveformAmplitudes()[6] < reject.waveformAmplitudes()[2]);
    }

    @Test public void ordinarySemanticFallbacksDoNotInventAnExtraPulse() {
        for (String kind : new String[] { "selection", "toggle", "confirm", "preview" }) {
            HapticProfile profile = HapticProfile.forKind(kind, 100);
            assertEquals(1, profile.pulseCount());
            assertArrayEquals(new long[] { 0 }, amplitudePulseOnsets(profile));
            assertArrayEquals(new long[] { 0 }, unscaledPulseOnsets(profile.unscaledTimings()));
            assertEquals(0, profile.primitiveGapMs(0, 20));
        }
    }

    @Test public void optedInLegacyMechanicalPresetRemainsUnchanged() {
        for (String kind : new String[] { "press", "release" }) {
            HapticProfile full = HapticProfile.forKind(kind, 100);
            assertTrue(full.legacy());
            assertFalse(HapticProfile.isSemantic(kind));
            assertEquals(1f, full.primitiveScale(0), 0f);
            assertEquals(100, full.richTapIntensity(0));
            assertEquals(1, full.pulseCount());
            assertArrayEquals(new long[] { 0, 2, 5, 3 }, full.waveformTimings());
            assertArrayEquals(new int[] { 0, 127, 255, 85 }, full.waveformAmplitudes());
            assertArrayEquals(new long[] { 0, 10 }, full.unscaledTimings());
            assertArrayEquals(new int[] { 0, 64, 128, 42 }, HapticProfile.forKind(kind, 50).waveformAmplitudes());
        }
        assertArrayEquals(new int[] { 8, 1, 7 }, HapticProfile.forKind("press", 100).primitiveCandidates());
        assertArrayEquals(new int[] { 1, 8, 7 }, HapticProfile.forKind("release", 100).primitiveCandidates());
    }

    @Test public void unknownKindsDoNotResolveToAnyPlayableProfile() {
        for (String kind : new String[] { null, "", "unknown", "CONFIRM", "selection " }) {
            assertNull(HapticProfile.forKind(kind, 100));
            assertFalse(HapticProfile.isSemantic(kind));
            assertFalse(HapticProfile.isLegacy(kind));
        }
    }

    private static long[] amplitudePulseOnsets(HapticProfile profile) {
        long[] timings = profile.waveformTimings();
        int[] amplitudes = profile.waveformAmplitudes();
        List<Long> starts = new ArrayList<>();
        long elapsed = 0;
        boolean active = false;
        for (int i = 0; i < timings.length; i++) {
            boolean nextActive = amplitudes[i] > 0;
            if (nextActive && !active) starts.add(elapsed);
            elapsed += timings[i];
            active = nextActive;
        }
        return toArray(starts);
    }

    private static long[] unscaledPulseOnsets(long[] timings) {
        List<Long> starts = new ArrayList<>();
        long elapsed = 0;
        for (int i = 0; i < timings.length; i++) {
            if (i % 2 == 1 && timings[i] > 0) starts.add(elapsed);
            elapsed += timings[i];
        }
        return toArray(starts);
    }

    private static long[] toArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
}
