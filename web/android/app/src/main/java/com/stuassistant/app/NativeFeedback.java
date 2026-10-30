package com.stuassistant.app;

import android.app.Activity;
import android.os.*;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Native owner for the existing mechanical profile; navigation commits one tick. */
public final class NativeFeedback implements AutoCloseable {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HapticTiming timing = new HapticTiming();
    private final RichTapHaptics richTap = new RichTapHaptics();
    private Vibrator vibrator;
    private boolean checkedMotor;
    private boolean[] supported = {false, false, false};
    private final int[] primitives = {8, 1, 7};
    private Runnable pendingRelease;
    public NativeFeedback(Activity activity) { this.activity = activity; }
    private View view() { return activity.getWindow().getDecorView(); }
    private boolean active() { return !activity.isFinishing() && view().isShown() && view().hasWindowFocus(); }
    private Vibrator motor() {
        if (!checkedMotor) {
            checkedMotor = true;
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = activity.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
                if (vibrator != null) try { supported = vibrator.arePrimitivesSupported(primitives); }
                catch (RuntimeException ignored) { supported = new boolean[3]; }
            } else vibrator = activity.getSystemService(Vibrator.class);
        }
        return vibrator;
    }
    public void play(String kind, int value) {
        int strength = Math.max(0, Math.min(100, value));
        if (strength == 0) { cancel(); return; }
        if (!active()) return;
        if ("selection".equals(kind) && pendingRelease != null) cancel();
        long now = SystemClock.uptimeMillis();
        if (pendingRelease != null || timing.throttled(kind, now)) return;
        long delay = timing.releaseDelay(kind, now);
        if (delay > 0) {
            pendingRelease = () -> { pendingRelease = null; execute(kind, strength); };
            handler.postDelayed(pendingRelease, delay);
        } else execute(kind, strength);
    }
    private void execute(String kind, int strength) {
        try {
            if (!active()) return;
            Vibrator motor = motor();
            if (motor == null || !motor.hasVibrator()) return;
            RichTapPattern pattern = new RichTapPattern(kind, strength);
            VibrationEffect effect = richTap.createEffect(activity, pattern);
            if (effect != null) {
                try { vibrate(motor, effect); played(kind); return; }
                catch (RuntimeException | LinkageError ignored) { richTap.rejectDirect(); }
            }
            if (richTap.play(activity, pattern)) { played(kind); return; }
            int preferred = "press".equals(kind) ? 0 : "release".equals(kind) ? 1 : 2;
            if (Build.VERSION.SDK_INT >= 31) {
                int chosen = supported[preferred] ? preferred : -1;
                if (chosen == -1) for (int i = 0; i < supported.length; i++) if (supported[i]) { chosen = i; break; }
                if (chosen != -1) try {
                    vibrate(motor, VibrationEffect.startComposition().addPrimitive(primitives[chosen], strength / 100f).compose());
                    played(kind); return;
                } catch (RuntimeException | LinkageError ignored) { supported = new boolean[3]; }
            }
            if (Build.VERSION.SDK_INT >= 26 && motor.hasAmplitudeControl()) try {
                int peak = Math.max(1, Math.round(strength * 2.55f));
                vibrate(motor, VibrationEffect.createWaveform(new long[]{0,2,5,3}, new int[]{0,Math.max(1,peak/2),peak,Math.max(1,peak/3)}, -1));
                played(kind); return;
            } catch (RuntimeException | LinkageError ignored) { }
            if (Build.VERSION.SDK_INT >= 29) try {
                vibrate(motor, VibrationEffect.createPredefined("release".equals(kind) ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                played(kind); return;
            } catch (RuntimeException | LinkageError ignored) { }
            int constant = "selection".equals(kind) ? HapticFeedbackConstants.CLOCK_TICK
                : "release".equals(kind) && Build.VERSION.SDK_INT >= 27 ? HapticFeedbackConstants.VIRTUAL_KEY_RELEASE : HapticFeedbackConstants.VIRTUAL_KEY;
            if (view().performHapticFeedback(constant)) played(kind);
        } catch (RuntimeException | LinkageError ignored) { }
    }
    private void vibrate(Vibrator motor, VibrationEffect effect) {
        if (Build.VERSION.SDK_INT >= 33) motor.vibrate(effect, new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_PHYSICAL_EMULATION).build());
        else motor.vibrate(effect);
    }
    private void played(String kind) { timing.played(kind, SystemClock.uptimeMillis()); }
    public void cancel() {
        if (pendingRelease != null) handler.removeCallbacks(pendingRelease);
        pendingRelease = null; timing.reset(); richTap.stop();
        if (vibrator != null) try { vibrator.cancel(); } catch (RuntimeException ignored) { }
    }
    @Override public void close() { cancel(); richTap.close(); }
}
