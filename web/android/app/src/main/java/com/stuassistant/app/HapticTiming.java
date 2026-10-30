package com.stuassistant.app;

/** Monotonic native scheduling. At most one release may be pending. */
final class HapticTiming {
    static final int MIN_SPACING_MS = 110;
    private long lastAt = -1000;
    private String lastKind = "";

    boolean throttled(String kind, long now) {
        return !("press".equals(lastKind) && "release".equals(kind)) && now - lastAt < 50;
    }

    long releaseDelay(String kind, long now) {
        if (!"press".equals(lastKind) || !"release".equals(kind)) return 0;
        return Math.max(0, lastAt + MIN_SPACING_MS - now);
    }

    void played(String kind, long now) { lastKind = kind; lastAt = now; }
    void reset() { lastKind = ""; lastAt = -1000; }
}
