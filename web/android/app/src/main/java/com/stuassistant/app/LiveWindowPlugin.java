package com.stuassistant.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * 灵动岛式的「实时活动」入口。
 *
 * 三档实现，从高到低：
 *   1. 华为实况窗（HMS Live View）——系统级胶囊，必须由厂商 SDK + AGC 后台开通权限，
 *      且只能由服务端推送触发；本地调用无法凭空造出实况窗，所以这里只做能力探测。
 *   2. OPPO 流体云（ColorOS 实时活动）——同理，需 OPPO 开放平台应用与推送凭据。
 *   3. 本地进行中通知——全机型兜底，倒计时/进度都靠它，装完就能自己弹。
 *
 * 前端只管调用；能不能变成胶囊由系统与厂商凭据决定，降级永远不报错。
 */
@CapacitorPlugin(
    name = "LiveWindow",
    permissions = {
        @Permission(
            alias = "notifications",
            strings = { Manifest.permission.POST_NOTIFICATIONS }
        )
    }
)
public class LiveWindowPlugin extends Plugin {

    private static final String CHANNEL = "live_window";
    private static final int NOTIFICATION_ID = 7101;

    // ---------------------------------------------------------------- 能力探测

    private boolean hmsPresent() {
        return classPresent("com.huawei.hms.push.HmsMessageService")
            || classPresent("com.huawei.hms.aaid.HmsInstanceId");
    }

    private boolean colorOsPresent() {
        return classPresent("com.coloros.mcssdk.PushManager")
            || classPresent("com.heytap.msp.push.HeytapPushManager");
    }

    /** vivo 原子岛 / 原子通知：由 Push SDK 的原子通知能力承接。 */
    private boolean vivoPresent() {
        return classPresent("com.vivo.push.PushClient")
            || classPresent("com.vivo.push.IPushActionListener");
    }

    private boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String vendor() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
        if (m.contains("huawei") || m.contains("honor")) return "huawei";
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) return "oppo";
        if (m.contains("vivo") || m.contains("iqoo")) return "vivo";
        return "other";
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        }
        return getContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 页面告诉原生「我现在实际是深色还是浅色」，原生把系统区刷成对应纸色。
     *
     * 为什么需要这条：App 的主题可以被用户在页内强制（浅色/深色），和系统的
     * 深浅色不一定一致。原生若按系统猜，就会出现「页面浅色、系统栏被涂成近黑」
     * 这种上下两条黑边。页面才是知道真相的那一方，所以由它来喊。
     */
    @PluginMethod
    public void setChrome(PluginCall call) {
        Boolean dark = call.getBoolean("dark", Boolean.FALSE);
        final boolean value = dark != null && dark;
        // runOnUiThread 是 Activity 的方法，Plugin 自己没有，必须经 getActivity()
        final android.app.Activity activity = getActivity();
        final Runnable apply = () -> {
            if (activity instanceof MainActivity) {
                ((MainActivity) activity).setChromeFromPage(value);
            }
            call.resolve(okObject());
        };
        if (activity != null) {
            activity.runOnUiThread(apply);
        } else {
            apply.run();
        }
    }

    @PluginMethod
    public void supported(PluginCall call) {
        JSObject out = new JSObject();
        String vendor = vendor();
        String channel = "local";
        if ("huawei".equals(vendor) && hmsPresent()) channel = "hms-liveview";
        else if ("oppo".equals(vendor) && colorOsPresent()) channel = "coloros-fluid";
        else if ("vivo".equals(vendor) && vivoPresent()) channel = "vivo-atomic-island";
        out.put("vendor", vendor);
        out.put("channel", channel);
        out.put("granted", notificationsAllowed());
        call.resolve(out);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsAllowed()) {
            call.resolve(grantedObject(notificationsAllowed()));
            return;
        }
        requestPermissionForAlias("notifications", call, "afterNotificationPermission");
    }

    @PermissionCallback
    private void afterNotificationPermission(PluginCall call) {
        call.resolve(grantedObject(notificationsAllowed()));
    }

    // ---------------------------------------------------------------- 实时活动

    @PluginMethod
    public void start(PluginCall call) {
        render(call);
    }

    @PluginMethod
    public void update(PluginCall call) {
        render(call);
    }

    @PluginMethod
    public void notifyNow(PluginCall call) {
        render(call);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        NotificationManagerCompat.from(getContext()).cancel(NOTIFICATION_ID);
        call.resolve();
    }

    private void render(PluginCall call) {
        String title = call.getString("title", "");
        String detail = call.getString("detail", "");
        String phase = call.getString("phase", "before");
        long startsAt = optLong(call, "startsAt", 0L);
        long endsAt = optLong(call, "endsAt", 0L);
        if (title == null || title.isEmpty()) {
            call.reject("title_required");
            return;
        }
        if (!notificationsAllowed()) {
            call.resolve(grantedObject(false));
            return;
        }
        Context ctx = getContext();
        ensureChannel(ctx);

        boolean before = !"during".equals(phase);
        long now = System.currentTimeMillis();
        int progress = 0;
        if (endsAt > startsAt && endsAt > now) {
            long total = Math.max(1, endsAt - startsAt);
            progress = (int) Math.max(0, Math.min(100, Math.round((now - startsAt) * 100.0 / total)));
        }

        Intent open = new Intent(ctx, MainActivity.class)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(
            ctx, 1, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(ctx.getApplicationInfo().icon)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        if (before && startsAt > now) {
            // 开课前：走倒计时，锁屏上也是"还有 12 分钟"的感觉
            b.setWhen(startsAt).setShowWhen(true).setUsesChronometer(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) b.setChronometerCountDown(true);
        } else if (progress > 0) {
            b.setProgress(100, progress, false).setWhen(startsAt).setShowWhen(true);
        } else {
            b.setWhen(now).setShowWhen(true);
        }

        Notification n = b.build();
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, n);
            call.resolve(okObject());
        } catch (SecurityException exc) {
            call.reject("notifications_denied");
        }
    }

    private JSObject grantedObject(boolean granted) {
        JSObject out = new JSObject();
        out.put("granted", granted);
        return out;
    }

    private JSObject okObject() {
        JSObject out = new JSObject();
        out.put("ok", true);
        return out;
    }

    private long optLong(PluginCall call, String key, long fallback) {
        Double d = call.getDouble(key);
        if (d != null) return d.longValue();
        Long l = call.getLong(key);
        return l != null ? l : fallback;
    }

    private void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "上课实时活动", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("正在上课、下一节课的倒计时");
        ch.setShowBadge(false);
        ch.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }
}
