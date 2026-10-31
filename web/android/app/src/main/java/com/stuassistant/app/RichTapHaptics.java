package com.stuassistant.app;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import java.lang.reflect.Method;
import com.apprichtap.haptic.RichTapUtils;

/** Optional vendor engine. Unsupported phones never use the SDK's buzzy emulation. */
final class RichTapHaptics {
    private RichTapUtils sdk;
    private boolean checked;
    private boolean supported;
    private Method patternFactory;
    private int coreVersion;
    private String directStatus = "unchecked";

    boolean supported(Context context) {
        if (Build.VERSION.SDK_INT < 26) return false;
        if (!checked) {
            checked = true;
            try {
                sdk = RichTapUtils.getInstance().init(context.getApplicationContext());
                supported = sdk.isSupportedRichTap() && !sdk.isNonRichTapMode();
                if (supported) findFactory();
            } catch (RuntimeException | LinkageError ignored) { supported = false; }
        }
        return supported;
    }

    private void findFactory() {
        directStatus = "factory_unavailable";
        // The same optional public OEM extensions used by the vendor SDK.
        // No setAccessible, hidden-API exemptions or privileged flags.
        for (String name : new String[] { "richtap.os.PhonyVibrationEffect", "android.os.RichTapVibrationEffect", "android.os.VibrationEffect" }) {
            try {
                Class<?> type = Class.forName(name);
                coreVersion = (Integer) type.getMethod("checkIfRichTapSupport").invoke(null);
                if (((coreVersion >> 8) & 0xff) < 0x18) continue;
                patternFactory = type.getMethod("createPatternHeWithParam", int[].class, int.class, int.class, int.class, int.class);
                directStatus = "available";
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        }
    }

    boolean directSupported() { return patternFactory != null; }
    int coreVersion() { return coreVersion; }
    String directStatus() { return directStatus; }
    void rejectDirect() { directStatus = "submission_rejected"; patternFactory = null; }

    VibrationEffect createEffect(Context context, RichTapPattern pattern) {
        if (Build.VERSION.SDK_INT < 26 || !supported(context) || patternFactory == null) return null;
        try {
            Object effect = patternFactory.invoke(null, pattern.packet(), 1, 0, 255, 0);
            if (effect instanceof VibrationEffect) return (VibrationEffect) effect;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
        directStatus = "factory_rejected";
        patternFactory = null;
        return null;
    }

    boolean play(Context context, RichTapPattern pattern) {
        if (!supported(context)) return false;
        try {
            sdk.playHaptic(pattern.json(), 1, 0, 255, 0);
            return true; // Request only; SDK has no motor-delivery acknowledgement.
        } catch (RuntimeException | LinkageError ignored) { supported = false; return false; }
    }

    void stop() {
        if (sdk != null) try { sdk.stop(); } catch (RuntimeException | LinkageError ignored) { }
    }

    void close() {
        if (sdk != null) try { sdk.quit(); } catch (RuntimeException | LinkageError ignored) { }
        sdk = null;
        checked = false;
        supported = false;
    }
}
