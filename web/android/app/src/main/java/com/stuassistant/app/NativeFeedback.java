package com.stuassistant.app;

import android.app.Activity;
import android.os.*;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Activity-owned semantic feedback, with the opt-in mechanical preset kept intact. */
public final class NativeFeedback implements NativeFeedbackSink, AutoCloseable {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HapticTiming timing = new HapticTiming();
    private final RichTapHaptics richTap = new RichTapHaptics();
    private Vibrator vibrator;
    private boolean checkedMotor;
    private boolean closed;
    private boolean vendorPlaying;
    private boolean[] supported = { false, false, false };
    private int[] durations = { 0, 0, 0 };
    private int[] primitives = new int[0];
    private Runnable pendingRelease;

    public NativeFeedback(Activity activity) { this.activity = activity; }
    private View view() { return activity.getWindow().getDecorView(); }
    private boolean active() {
        return !closed && !activity.isFinishing() && !activity.isDestroyed()
            && view().isShown() && view().hasWindowFocus();
    }

    private Vibrator motor() {
        if (!checkedMotor) {
            checkedMotor = true;
            if (Build.VERSION.SDK_INT >= 31) {
                int[] supportedPrimitives = {
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_TICK
                }; // Keep the original LOW_TICK, CLICK, TICK fallback order.
                primitives = supportedPrimitives;
                VibratorManager manager = activity.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
                if (vibrator != null) {
                    try { supported = vibrator.arePrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK); }
                    catch (RuntimeException ignored) { supported = new boolean[3]; }
                    try { durations = vibrator.getPrimitiveDurations(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK); }
                    catch (RuntimeException ignored) { durations = new int[3]; }
                }
            } else vibrator = activity.getSystemService(Vibrator.class);
        }
        return vibrator;
    }

    /** Called on the UI thread. Semantic events are submitted now or dropped, never queued. */
    public void play(String kind, int value) {
        int strength = Math.max(0, Math.min(100, value));
        if (strength == 0) { cancel(); return; }
        HapticProfile profile = HapticProfile.forKind(kind, strength);
        if (profile == null || !active()) return;
        // A semantic gesture owns feedback, including an explicit rejection of a
        // mechanical action. Remove its tail before admission; reject bypasses other kinds.
        if (!profile.mechanical()) cancelPendingRelease();
        long now = SystemClock.uptimeMillis();
        if (pendingRelease != null || timing.throttled(kind, now)) return;
        long delay = profile.mechanical() ? timing.releaseDelay(kind, now) : 0;
        if (delay > 0) {
            pendingRelease = () -> { pendingRelease = null; execute(profile); };
            handler.postDelayed(pendingRelease, delay);
        } else execute(profile);
    }

    private void execute(HapticProfile profile) {
        try {
            if (!active()) return;
            Vibrator motor = motor();
            if (motor == null || !motor.hasVibrator()) return;
            if (profile.mechanical()) {
                if (profile.legacy()) executeLegacy(profile, motor);
                else executeTypedMechanical(profile, motor);
                return;
            }
            // A prior vendor SDK request must not outlive a new Android composition.
            if (vendorPlaying) { richTap.stop(); vendorPlaying = false; }
            // Android submissions replace an existing effect. Errors also stop an old
            // mechanical effect if a later fallback is view feedback.
            if ("reject".equals(profile.kind)) motor.cancel();
            if (playRichTap(profile, motor)) { played(profile.kind); return; }
            if (playPrimitive(profile, motor)) { played(profile.kind); return; }
            if (Build.VERSION.SDK_INT >= 26 && motor.hasAmplitudeControl()) try {
                vibrate(motor, VibrationEffect.createWaveform(profile.waveformTimings(), profile.waveformAmplitudes(), -1));
                played(profile.kind); return;
            } catch (RuntimeException | LinkageError ignored) { }
            if (Build.VERSION.SDK_INT >= 29) try {
                int preset = "reject".equals(profile.kind) ? VibrationEffect.EFFECT_DOUBLE_CLICK
                    : "selection".equals(profile.kind) || "navigation".equals(profile.kind) || "toggle".equals(profile.kind)
                        ? VibrationEffect.EFFECT_TICK : VibrationEffect.EFFECT_CLICK;
                vibrate(motor, VibrationEffect.createPredefined(preset));
                played(profile.kind); return;
            } catch (RuntimeException | LinkageError ignored) { }
            // API 24–28 cannot express a reject preset. One waveform preserves both
            // pulses without scheduling a second UI event. Gain is unavailable here.
            if ("reject".equals(profile.kind)) try {
                if (Build.VERSION.SDK_INT >= 26)
                    vibrate(motor, VibrationEffect.createWaveform(profile.unscaledTimings(), -1));
                else motor.vibrate(profile.unscaledTimings(), -1);
                played(profile.kind); return;
            } catch (RuntimeException | LinkageError ignored) { }
            if (view().performHapticFeedback(systemFeedback(profile.kind))) played(profile.kind);
        } catch (RuntimeException | LinkageError ignored) { }
    }

    private boolean playPrimitive(HapticProfile profile, Vibrator motor) {
        // Individual primitive support is reliably queryable from API 31.
        if (Build.VERSION.SDK_INT < 31) return false;
        int chosen = -1;
        for (int candidate : profile.primitiveCandidates()) {
            for (int i = 0; i < primitives.length; i++)
                if (primitives[i] == candidate && supported[i]) { chosen = i; break; }
            if (chosen != -1) break;
        }
        if (chosen == -1) return false;
        try {
            VibrationEffect.Composition composition = VibrationEffect.startComposition();
            int duration = chosen < durations.length ? durations[chosen] : 0;
            for (int i = 0; i < profile.pulseCount(); i++)
                composition.addPrimitive(primitives[chosen], profile.primitiveScale(i), profile.primitiveGapMs(i, duration));
            vibrate(motor, composition.compose());
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            supported = new boolean[3];
            return false;
        }
    }

    private boolean playRichTap(HapticProfile profile, Vibrator motor) {
        RichTapPattern pattern = new RichTapPattern(profile.kind, profile.strength);
        VibrationEffect effect = richTap.createEffect(activity, pattern);
        if (effect != null) {
            try { vibrate(motor, effect); return true; }
            catch (RuntimeException | LinkageError ignored) { richTap.rejectDirect(); }
        }
        if (richTap.play(activity, pattern)) { vendorPlaying = true; return true; }
        return false;
    }

    private void executeTypedMechanical(HapticProfile profile, Vibrator motor) {
        if (playRichTap(profile, motor)) { played(profile.kind); return; }
        if (playPrimitive(profile, motor)) { played(profile.kind); return; }
        if (Build.VERSION.SDK_INT >= 26 && motor.hasAmplitudeControl()) try {
            vibrate(motor, VibrationEffect.createWaveform(profile.waveformTimings(), profile.waveformAmplitudes(), -1));
            played(profile.kind); return;
        } catch (RuntimeException | LinkageError ignored) { }
        // Keep each typed stage's duration even on older/no-amplitude-control motors.
        // This is one stage, not a newly scheduled second vibration.
        try {
            if (Build.VERSION.SDK_INT >= 26)
                vibrate(motor, VibrationEffect.createWaveform(profile.unscaledTimings(), -1));
            else motor.vibrate(profile.unscaledTimings(), -1);
            played(profile.kind); return;
        } catch (RuntimeException | LinkageError ignored) { }
        int constant = HapticProfile.isRelease(profile.kind) && Build.VERSION.SDK_INT >= 27
            ? HapticFeedbackConstants.VIRTUAL_KEY_RELEASE
            : profile.kind.startsWith("navigate_") ? HapticFeedbackConstants.CLOCK_TICK
                : profile.kind.startsWith("destructive_") ? HapticFeedbackConstants.LONG_PRESS
                    : HapticFeedbackConstants.VIRTUAL_KEY;
        if (view().performHapticFeedback(constant)) played(profile.kind);
    }

    /** Preserve RichTap priority, primitive choice, gain, waveform and fallback of the tuned preset. */
    private void executeLegacy(HapticProfile profile, Vibrator motor) {
        String kind = profile.kind;
        int strength = profile.strength;
        if (playRichTap(profile, motor)) { played(kind); return; }
        int preferred = "press".equals(kind) ? 0 : 1;
        if (Build.VERSION.SDK_INT >= 31) {
            int chosen = supported[preferred] ? preferred : -1;
            if (chosen == -1) for (int i = 0; i < supported.length; i++) if (supported[i]) { chosen = i; break; }
            if (chosen != -1) try {
                vibrate(motor, VibrationEffect.startComposition().addPrimitive(primitives[chosen], strength / 100f).compose());
                played(kind); return;
            } catch (RuntimeException | LinkageError ignored) { supported = new boolean[3]; }
        }
        if (Build.VERSION.SDK_INT >= 26 && motor.hasAmplitudeControl()) try {
            vibrate(motor, VibrationEffect.createWaveform(profile.waveformTimings(), profile.waveformAmplitudes(), -1));
            played(kind); return;
        } catch (RuntimeException | LinkageError ignored) { }
        if (Build.VERSION.SDK_INT >= 29) try {
            vibrate(motor, VibrationEffect.createPredefined("release".equals(kind) ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
            played(kind); return;
        } catch (RuntimeException | LinkageError ignored) { }
        int constant = "release".equals(kind) && Build.VERSION.SDK_INT >= 27
            ? HapticFeedbackConstants.VIRTUAL_KEY_RELEASE : HapticFeedbackConstants.VIRTUAL_KEY;
        if (view().performHapticFeedback(constant)) played(kind);
    }

    private int systemFeedback(String kind) {
        if ("selection".equals(kind) || "navigation".equals(kind)) return Build.VERSION.SDK_INT >= 34
            ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
        if (Build.VERSION.SDK_INT >= 30) {
            if ("confirm".equals(kind)) return HapticFeedbackConstants.CONFIRM;
            if ("reject".equals(kind)) return HapticFeedbackConstants.REJECT;
        }
        return HapticFeedbackConstants.CONTEXT_CLICK;
    }

    private void vibrate(Vibrator motor, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) motor.vibrate(effect, new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION).build());
        else if (Build.VERSION.SDK_INT >= 26) motor.vibrate(effect);
        else throw new IllegalStateException("VibrationEffect requires API 26");
    }
    private void played(String kind) { timing.played(kind, SystemClock.uptimeMillis()); }
    private void cancelPendingRelease() {
        if (pendingRelease != null) handler.removeCallbacks(pendingRelease);
        pendingRelease = null;
    }
    public void cancel() {
        cancelPendingRelease();
        timing.reset(); richTap.stop(); vendorPlaying = false;
        if (vibrator != null) try { vibrator.cancel(); } catch (RuntimeException ignored) { }
    }
    @Override public void close() { closed = true; cancel(); richTap.close(); }
}
