package com.stuassistant.app;

/** Drop-rate policy. Only an accepted matching mechanical pair may schedule its release. */
final class HapticTiming {
    static final int MIN_SPACING_MS = 110;
    private long lastAt = -1000;
    private String lastKind = "";

    boolean throttled(String kind, long now) {
        if (!HapticProfile.isSemantic(kind) && !HapticProfile.isMechanical(kind)) return true;
        // Protect the second reject onset from a new vibrator submission. These events
        // are dropped, never delayed; explicit cancel/zero/lifecycle stops bypass this policy.
        if ("reject".equals(lastKind) && now - lastAt < HapticProfile.minimumIntervalMs("reject")) return true;
        if (HapticProfile.isMatchingPair(lastKind, kind)) return false;
        // A dropped press must not be followed by an orphan or differently typed tail.
        // The original untyped release remains compatible with the old native plugin.
        if (HapticProfile.isRelease(kind) && !HapticProfile.isLegacy(kind)) return true;
        // An explicit error owns feedback now; it must cancel a mechanical tail, not wait for it.
        if ("reject".equals(kind) && !"reject".equals(lastKind)) return false;
        return now - lastAt < HapticProfile.minimumIntervalMs(kind);
    }

    long releaseDelay(String kind, long now) {
        if (!HapticProfile.isMatchingPair(lastKind, kind)) return 0;
        return Math.max(0, lastAt + HapticProfile.releaseIntervalMs(kind) - now);
    }

    void played(String kind, long now) { lastKind = kind; lastAt = now; }
    void reset() { lastKind = ""; lastAt = -1000; }
}
