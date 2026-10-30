package com.stuassistant.app;

/** Fixed HE 1.0 transient. Frequency is the vendor's relative sharpness, not Hz. */
final class RichTapPattern {
    static final int TRANSIENT = 0x1001;
    final int intensity;
    final int frequency;

    RichTapPattern(String kind, int strength) {
        intensity = Math.max(0, Math.min(100, strength));
        frequency = "press".equals(kind) ? 52 : 100;
    }

    int[] packet() {
        // HE 1.0 protocol 3: one event occupies 55 integers. Transients have no
        // duration or envelope points; zero-filled tail is required by the SDK.
        int[] data = new int[56];
        data[0] = 3;
        data[1] = TRANSIENT;
        data[3] = intensity;
        data[4] = frequency;
        return data;
    }

    String json() {
        return "{\"Metadata\":{\"Version\":1},\"Pattern\":[{\"Event\":{\"Type\":\"transient\","
            + "\"RelativeTime\":0,\"Parameters\":{\"Intensity\":" + intensity
            + ",\"Frequency\":" + frequency + "}}}]}";
    }
}
