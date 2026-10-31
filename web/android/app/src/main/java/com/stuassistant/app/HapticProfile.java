package com.stuassistant.app;

/** Device-independent semantic gain and rhythm. Primitive IDs are not effect/feedback IDs. */
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
        if ("selection".equals(kind)) return new HapticProfile(kind, strength, .16f);
        if ("toggle".equals(kind)) return new HapticProfile(kind, strength, .30f);
        if ("confirm".equals(kind)) return new HapticProfile(kind, strength, .46f);
        if ("reject".equals(kind)) return new HapticProfile(kind, strength, .22f);
        if ("preview".equals(kind)) return new HapticProfile(kind, strength, .32f);
        // Preserve the tuned mechanical preset for explicitly opted-in legacy controls.
        if ("press".equals(kind) || "release".equals(kind)) return new HapticProfile(kind, strength, 1f);
        return null;
    }

    static boolean isSemantic(String kind) {
        return "selection".equals(kind) || "toggle".equals(kind) || "confirm".equals(kind)
            || "reject".equals(kind) || "preview".equals(kind);
    }

    static boolean isLegacy(String kind) { return "press".equals(kind) || "release".equals(kind); }

    static int minimumIntervalMs(String kind) {
        if ("preview".equals(kind) || "toggle".equals(kind)) return 60;
        if ("confirm".equals(kind)) return 90;
        if ("reject".equals(kind)) return 160;
        return 50;
    }

    boolean silent() { return strength == 0; }
    boolean legacy() { return isLegacy(kind); }
    int pulseCount() { return "reject".equals(kind) ? 2 : 1; }
    int onsetMs(int pulse) { checkPulse(pulse); return pulse == 0 ? 0 : REJECT_SECOND_ONSET_MS; }

    float primitiveScale(int pulse) {
        checkPulse(pulse);
        return relativeGain * (strength / 100f) * (pulse == 0 ? 1f : .85f);
    }

    int richTapIntensity(int pulse) {
        if (legacy()) { checkPulse(pulse); return strength; }
        return silent() ? 0 : Math.max(1, Math.round(primitiveScale(pulse) * 100f));
    }

    int[] primitiveCandidates() {
        if ("press".equals(kind)) return new int[] { LOW_TICK, CLICK, TICK };
        if ("release".equals(kind)) return new int[] { CLICK, LOW_TICK, TICK };
        if ("selection".equals(kind) || "reject".equals(kind))
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
        if ("selection".equals(kind)) return new long[] { 0, 1, 3, 2 };
        if ("confirm".equals(kind)) return new long[] { 0, 4, 9, 5 };
        if ("preview".equals(kind)) return new long[] { 0, 2, 6, 3 };
        if ("reject".equals(kind)) return new long[] { 0, 2, 4, 2, 52, 2, 4, 2 };
        return new long[] { 0, 2, 5, 3 };
    }

    int[] waveformAmplitudes() {
        int peak = amplitude(0);
        if ("reject".equals(kind)) {
            int second = amplitude(1);
            return new int[] { 0, fraction(peak, .4f), peak, fraction(peak, .25f), 0,
                fraction(second, .4f), second, fraction(second, .25f) };
        }
        // The mechanical preset's 0 / half / full / third contour stays unchanged.
        if (legacy()) return new int[] { 0, silent() ? 0 : Math.max(1, peak / 2), peak,
            silent() ? 0 : Math.max(1, peak / 3) };
        return new int[] { 0, fraction(peak, .35f), peak, fraction(peak, .28f) };
    }

    /** No-amplitude-control fallback preserves rhythm; hardware cannot provide continuous gain. */
    long[] unscaledTimings() {
        if ("selection".equals(kind)) return new long[] { 0, 6 };
        if ("confirm".equals(kind)) return new long[] { 0, 18 };
        if ("preview".equals(kind)) return new long[] { 0, 11 };
        if ("reject".equals(kind)) return new long[] { 0, 8, 52, 8 };
        return new long[] { 0, 10 };
    }

    private int amplitude(int pulse) {
        // Keep the original floating-point calculation too, including rounding at half steps.
        if (legacy()) { checkPulse(pulse); return silent() ? 0 : Math.max(1, Math.round(strength * 2.55f)); }
        return silent() ? 0 : Math.max(1, Math.round(primitiveScale(pulse) * 255f));
    }
    private int fraction(int peak, float amount) { return silent() ? 0 : Math.max(1, Math.round(peak * amount)); }
    private void checkPulse(int pulse) { if (pulse < 0 || pulse >= pulseCount()) throw new IllegalArgumentException("Invalid pulse"); }
}
