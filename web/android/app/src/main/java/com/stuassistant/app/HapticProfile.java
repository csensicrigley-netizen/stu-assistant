package com.stuassistant.app;

/** Device-independent rhythm. Only the bottom navigation has a gain below the global setting. */
final class HapticProfile {
    static final int LOW_TICK = 8;
    static final int CLICK = 1;
    static final int TICK = 7;
    static final int REJECT_SECOND_ONSET_MS = 60;

    final String kind;
    final int strength;
    private final float relativeGain;

    private HapticProfile(String kind, int strength, float relativeGain) {
        this.kind = kind;
        this.strength = Math.max(0, Math.min(100, strength));
        this.relativeGain = relativeGain;
    }

    static HapticProfile forKind(String kind, int strength) {
        if ("navigation".equals(kind)) return new HapticProfile(kind, strength, .8f);
        if (isSemantic(kind) || isMechanical(kind)) return new HapticProfile(kind, strength, 1f);
        return null;
    }

    static boolean isSemantic(String kind) {
        return "selection".equals(kind) || "toggle".equals(kind) || "confirm".equals(kind)
            || "reject".equals(kind) || "preview".equals(kind) || "navigation".equals(kind);
    }

    static boolean isLegacy(String kind) { return "press".equals(kind) || "release".equals(kind); }

    private static String mechanicalFamily(String kind) {
        if (isLegacy(kind)) return "submit";
        if ("navigate_press".equals(kind) || "navigate_release".equals(kind)) return "navigate";
        if ("utility_press".equals(kind) || "utility_release".equals(kind)) return "utility";
        if ("destructive_press".equals(kind) || "destructive_release".equals(kind)) return "destructive";
        return null;
    }

    static boolean isMechanical(String kind) { return mechanicalFamily(kind) != null; }
    static boolean isPress(String kind) { return isMechanical(kind) && ("press".equals(kind) || kind.endsWith("_press")); }
    static boolean isRelease(String kind) { return isMechanical(kind) && ("release".equals(kind) || kind.endsWith("_release")); }
    static boolean isMatchingPair(String press, String release) {
        return isPress(press) && isRelease(release) && mechanicalFamily(press).equals(mechanicalFamily(release));
    }
    static int releaseIntervalMs(String kind) {
        String family = mechanicalFamily(kind);
        if ("navigate".equals(family)) return 80;
        if ("destructive".equals(family)) return 150;
        return family == null ? 0 : 110;
    }

    static int minimumIntervalMs(String kind) {
        if ("preview".equals(kind) || "toggle".equals(kind)) return 60;
        if ("confirm".equals(kind)) return 90;
        if ("reject".equals(kind)) return 160;
        return 50;
    }

    boolean silent() { return strength == 0; }
    boolean legacy() { return isLegacy(kind); }
    boolean mechanical() { return isMechanical(kind); }
    int pulseCount() { return "reject".equals(kind) ? 2 : 1; }
    int onsetMs(int pulse) { checkPulse(pulse); return pulse == 0 ? 0 : REJECT_SECOND_ONSET_MS; }

    float primitiveScale(int pulse) {
        checkPulse(pulse);
        return relativeGain * (strength / 100f);
    }

    int richTapIntensity(int pulse) {
        checkPulse(pulse);
        if (!"navigation".equals(kind)) return strength;
        return silent() ? 0 : Math.max(1, Math.round(primitiveScale(pulse) * 100f));
    }

    /** Vendor sharpness 0..100, never a requested motor frequency in Hz. */
    int richTapSharpness() {
        if ("press".equals(kind)) return 52;
        if ("release".equals(kind)) return 100;
        if ("navigate_press".equals(kind)) return 40;
        if ("navigate_release".equals(kind)) return 75;
        if ("utility_press".equals(kind)) return 70;
        if ("utility_release".equals(kind)) return 90;
        if ("destructive_press".equals(kind)) return 100;
        if ("destructive_release".equals(kind)) return 35;
        if ("toggle".equals(kind)) return 75;
        if ("confirm".equals(kind)) return 55;
        if ("reject".equals(kind)) return 90;
        // The slider reference and plain selection keep the original full-gain transient.
        return 100;
    }

    int[] primitiveCandidates() {
        if ("press".equals(kind)) return new int[] { LOW_TICK, CLICK, TICK };
        if ("release".equals(kind)) return new int[] { CLICK, LOW_TICK, TICK };
        if ("navigate_press".equals(kind)) return new int[] { LOW_TICK, TICK, CLICK };
        if ("navigate_release".equals(kind)) return new int[] { TICK, LOW_TICK, CLICK };
        if ("utility_press".equals(kind)) return new int[] { TICK, LOW_TICK, CLICK };
        if ("utility_release".equals(kind)) return new int[] { CLICK, TICK, LOW_TICK };
        if ("destructive_press".equals(kind)) return new int[] { CLICK, TICK, LOW_TICK };
        if ("destructive_release".equals(kind)) return new int[] { LOW_TICK, CLICK, TICK };
        if ("navigation".equals(kind) || "selection".equals(kind) || "preview".equals(kind))
            return new int[] { TICK, LOW_TICK, CLICK };
        if ("reject".equals(kind))
            return new int[] { LOW_TICK, TICK, CLICK };
        if ("toggle".equals(kind)) return new int[] { TICK, CLICK, LOW_TICK };
        return new int[] { CLICK, TICK, LOW_TICK };
    }

    /** API 30's three-argument primitive delay is measured after the previous primitive ends. */
    int primitiveGapMs(int pulse, int previousDurationMs) {
        checkPulse(pulse);
        if (pulse == 0) return 0;
        return Math.max(0, onsetMs(pulse) - onsetMs(pulse - 1) - Math.max(0, previousDurationMs));
    }

    long[] waveformTimings() {
        // The original submit envelope is an explicit compatibility exception, not a gain.
        if (legacy()) return new long[] { 0, 2, 5, 3 };
        if ("navigate_press".equals(kind)) return new long[] { 0, 5 };
        if ("navigate_release".equals(kind)) return new long[] { 0, 7 };
        if ("utility_press".equals(kind)) return new long[] { 0, 7 };
        if ("utility_release".equals(kind)) return new long[] { 0, 11 };
        if ("destructive_press".equals(kind)) return new long[] { 0, 14 };
        if ("destructive_release".equals(kind)) return new long[] { 0, 8 };
        if ("navigation".equals(kind) || "selection".equals(kind)) return new long[] { 0, 6 };
        if ("confirm".equals(kind)) return new long[] { 0, 18 };
        if ("preview".equals(kind)) return new long[] { 0, 11 };
        if ("reject".equals(kind)) return new long[] { 0, 8, 52, 8 };
        return new long[] { 0, 10 };
    }

    int[] waveformAmplitudes() {
        int peak = amplitude(0);
        // The mechanical preset's 0 / half / full / third contour stays unchanged.
        if (legacy()) return new int[] { 0, silent() ? 0 : Math.max(1, peak / 2), peak,
            silent() ? 0 : Math.max(1, peak / 3) };
        long[] timings = waveformTimings();
        int[] amplitudes = new int[timings.length];
        for (int i = 1; i < amplitudes.length; i += 2) amplitudes[i] = peak;
        return amplitudes;
    }

    /** No-amplitude-control fallback preserves rhythm; hardware cannot provide continuous gain. */
    long[] unscaledTimings() {
        return legacy() ? new long[] { 0, 10 } : waveformTimings();
    }

    private int amplitude(int pulse) {
        // Keep the original floating-point calculation too, including rounding at half steps.
        if (legacy()) { checkPulse(pulse); return silent() ? 0 : Math.max(1, Math.round(strength * 2.55f)); }
        return silent() ? 0 : Math.max(1, Math.round(primitiveScale(pulse) * 255f));
    }
    private void checkPulse(int pulse) { if (pulse < 0 || pulse >= pulseCount()) throw new IllegalArgumentException("Invalid pulse"); }
}
