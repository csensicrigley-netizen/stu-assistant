package com.stuassistant.app;

/** Drop-rate policy; only the explicitly opted-in legacy press/release pair may be delayed. */
final class HapticTiming {
    static final int MIN_SPACING_MS = 110;
    private long lastAt = -1000;
    private String lastKind = "";

    boolean throttled(String kind, long now) {
        if (!HapticProfile.isSemantic(kind) && !HapticProfile.isLegacy(kind)) return true;
        // Protect the second reject onset from a new vibrator submission. These events
        // are dropped, never delayed; explicit cancel/zero/lifecycle stops bypass this policy.
        if ("reject".equals(lastKind) && now - lastAt < HapticProfile.minimumIntervalMs("reject")) return true;
        if ("press".equals(lastKind) && "release".equals(kind)) return false;
        // An explicit error owns feedback now; it must cancel a mechanical tail, not wait for it.
        if ("reject".equals(kind) && !"reject".equals(lastKind)) return false;
        return now - lastAt < HapticProfile.minimumIntervalMs(kind);
    }

    long releaseDelay(String kind, long now) {
        if (!"press".equals(lastKind) || !"release".equals(kind)) return 0;
        return Math.max(0, lastAt + MIN_SPACING_MS - now);
    }

    void played(String kind, long now) { lastKind = kind; lastAt = now; }
    void reset() { lastKind = ""; lastAt = -1000; }
}
