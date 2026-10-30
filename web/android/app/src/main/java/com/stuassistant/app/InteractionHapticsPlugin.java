package com.stuassistant.app;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Fixed mechanical press/release profile, controlled by a single 0..100 gain. */
@CapacitorPlugin(name = "InteractionHaptics")
public class InteractionHapticsPlugin extends Plugin {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HapticTiming timing = new HapticTiming();
    private final RichTapHaptics richTap = new RichTapHaptics();
    private Vibrator vibrator;
    private boolean checkedMotor;
    private boolean[] supported = { false, false, false };
    private final int[] primitives = { 8, 1, 7 }; // LOW_TICK, CLICK, TICK
    private Runnable pendingRelease;
    private PluginCall pendingCall;

    private Vibrator motor() {
        if (!checkedMotor) {
            checkedMotor = true;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = getContext().getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
                if (vibrator != null) {
                    try { supported = vibrator.arePrimitivesSupported(primitives); }
                    catch (RuntimeException ignored) { supported = new boolean[3]; }
                }
            } else vibrator = getContext().getSystemService(Vibrator.class);
        }
        return vibrator;
    }

    private boolean foreground() {
        View view = getBridge().getWebView();
        return view != null && view.isShown() && view.hasWindowFocus();
    }

    private void reply(PluginCall call, boolean accepted, String route) {
        JSObject result = new JSObject();
        result.put("accepted", accepted);
        result.put("route", route);
        call.resolve(result);
    }

    @PluginMethod
    public void play(PluginCall call) {
        final long queuedAt = SystemClock.uptimeMillis();
        getActivity().runOnUiThread(() -> {
            try {
                String kind = call.getString("kind", "release");
                int strength = Math.max(0, Math.min(100, call.getInt("strength", 100)));
                if (!("press".equals(kind) || "release".equals(kind) || "selection".equals(kind))) {
                    reply(call, false, "unknown"); return;
                }
                if (strength == 0) { stopFeedback(); reply(call, false, "off"); return; }
                long now = SystemClock.uptimeMillis();
                if (!foreground() || now - queuedAt > 400) { reply(call, false, "inactive"); return; }
                // Never let a rapid follow-up cancel an already scheduled release.
                if (pendingRelease != null || timing.throttled(kind, now)) { reply(call, false, "throttled"); return; }
                long delay = timing.releaseDelay(kind, now);
                if (delay > 0) {
                    pendingCall = call;
                    pendingRelease = () -> {
                        pendingCall = null;
                        pendingRelease = null;
                        execute(call, kind, strength);
                    };
                    handler.postDelayed(pendingRelease, delay);
                } else execute(call, kind, strength);
            } catch (RuntimeException | LinkageError ignored) { reply(call, false, "unavailable"); }
        });
    }

    private void execute(PluginCall call, String kind, int strength) {
        try {
            if (!foreground()) { reply(call, false, "inactive"); return; }
            Vibrator v = motor();
            if (v == null || !v.hasVibrator()) { reply(call, false, "no_motor"); return; }
            RichTapPattern pattern = new RichTapPattern(kind, strength);
            VibrationEffect effect = richTap.createEffect(getContext(), pattern);
            if (effect != null) {
                try {
                    vibrate(v, effect);
                    played(call, kind, "richtap");
                    return;
                } catch (RuntimeException | LinkageError ignored) { richTap.rejectDirect(); }
            }
            if (richTap.play(getContext(), pattern)) { played(call, kind, "richtap_sdk"); return; }
            playAndroid(call, v, kind, strength);
        } catch (RuntimeException | LinkageError ignored) { reply(call, false, "unavailable"); }
    }

    private void played(PluginCall call, String kind, String route) {
        timing.played(kind, SystemClock.uptimeMillis());
        reply(call, true, route); // Submission acknowledgement, not motor telemetry.
    }

    private void playAndroid(PluginCall call, Vibrator v, String kind, int strength) {
        int preferred = "press".equals(kind) ? 0 : "release".equals(kind) ? 1 : 2;
        if (Build.VERSION.SDK_INT >= 31) {
            int chosen = supported[preferred] ? preferred : -1;
            if (chosen == -1) for (int i = 0; i < supported.length; i++) if (supported[i]) { chosen = i; break; }
            if (chosen != -1) {
                try {
                    vibrate(v, VibrationEffect.startComposition().addPrimitive(primitives[chosen], strength / 100f).compose());
                    played(call, kind, "primitive"); return;
                } catch (RuntimeException | LinkageError ignored) { supported = new boolean[3]; }
            }
        }
        if (Build.VERSION.SDK_INT >= 26 && v.hasAmplitudeControl()) {
            try {
                int peak = Math.max(1, Math.round(strength * 2.55f));
                vibrate(v, VibrationEffect.createWaveform(new long[] { 0, 2, 5, 3 },
                    new int[] { 0, Math.max(1, peak / 2), peak, Math.max(1, peak / 3) }, -1));
                played(call, kind, "amplitude"); return;
            } catch (RuntimeException | LinkageError ignored) { /* Try platform feedback. */ }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // Some motors cannot scale intensity. Preserve short feedback and off.
                vibrate(v, VibrationEffect.createPredefined("release".equals(kind) ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                played(call, kind, "preset"); return;
            } catch (RuntimeException | LinkageError ignored) { /* Try view feedback. */ }
        }
        int constant = "press".equals(kind) ? HapticFeedbackConstants.VIRTUAL_KEY
            : "release".equals(kind) ? HapticFeedbackConstants.VIRTUAL_KEY_RELEASE : HapticFeedbackConstants.CLOCK_TICK;
        if (getBridge().getWebView().performHapticFeedback(constant)) played(call, kind, "system");
        else reply(call, false, "system_off");
    }

    private void vibrate(Vibrator v, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) {
            // App-controlled mechanical simulation; no privileged bypass flags.
            v.vibrate(effect, new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION).build());
        } else v.vibrate(effect);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        getActivity().runOnUiThread(() -> { stopFeedback(); call.resolve(); });
    }

    private void stopFeedback() {
        if (pendingRelease != null) handler.removeCallbacks(pendingRelease);
        if (pendingCall != null) reply(pendingCall, false, "cancelled");
        pendingRelease = null;
        pendingCall = null;
        timing.reset();
        richTap.stop();
        if (vibrator != null) try { vibrator.cancel(); } catch (RuntimeException ignored) { }
    }

    @Override protected void handleOnPause() { stopFeedback(); }
    @Override protected void handleOnDestroy() { stopFeedback(); richTap.close(); }
}
