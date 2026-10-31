package com.stuassistant.app;

/** HE 1.0 transients. Frequency is a fixed vendor sharpness parameter, never a requested Hz. */
final class RichTapPattern {
    static final int TRANSIENT = 0x1001;
    final int intensity;
    final int frequency;
    private final HapticProfile profile;

    RichTapPattern(String kind, int strength) {
        profile = HapticProfile.forKind(kind, strength);
        if (profile == null) throw new IllegalArgumentException("Unknown haptic kind");
        intensity = profile.richTapIntensity(0);
        frequency = profile.richTapSharpness();
    }

    int[] packet() {
        // HE 1.0 protocol 3: one event occupies 55 integers. Transients have no
        // duration or envelope points; zero-filled tail is required by the SDK.
        int[] data = new int[1 + 55 * profile.pulseCount()];
        data[0] = 3;
        for (int i = 0; i < profile.pulseCount(); i++) {
            int offset = 1 + 55 * i;
            data[offset] = TRANSIENT;
            data[offset + 1] = profile.onsetMs(i);
            data[offset + 2] = profile.richTapIntensity(i);
            data[offset + 3] = frequency;
        }
        return data;
    }

    String json() {
        StringBuilder result = new StringBuilder("{\"Metadata\":{\"Version\":1},\"Pattern\":[");
        for (int i = 0; i < profile.pulseCount(); i++) {
            if (i > 0) result.append(',');
            result.append("{\"Event\":{\"Type\":\"transient\",\"RelativeTime\":")
                .append(profile.onsetMs(i)).append(",\"Parameters\":{\"Intensity\":")
                .append(profile.richTapIntensity(i)).append(",\"Frequency\":").append(frequency).append("}}}");
        }
        return result.append("]}").toString();
    }
}
