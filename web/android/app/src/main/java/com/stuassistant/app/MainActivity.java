package com.stuassistant.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import androidx.activity.EdgeToEdge;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    /** 页面底色，必须和 res/values/colors.xml 的 paper、tokens.css 的 bg 一致 */
    private static final String PAPER_LIGHT = "#FFF0F1";
    private static final String PAPER_DARK = "#1F161E";
    /** 网页上次报上来的「实际是不是深色」。App 自己的主题可以由用户在页内强制，
     *  和系统深浅色不一定一致，所以以页面为准，别拿系统去猜。 */
    private static final String PREFS = "stu_chrome";
    private static final String KEY_DARK = "dark";

    private boolean chromeDark;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LiveWindowPlugin.class);
        registerPlugin(InteractionHapticsPlugin.class);
        registerPlugin(LiquidGlassNavigationPlugin.class);
        super.onCreate(savedInstanceState);
        // Device-only rendering QA uses CDP in debuggable builds. Test/release
        // APKs keep capacitor.config's WebView debugging disabled.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }
        // Draw behind the status and navigation bars on every Android version;
        // the page keeps clear of them with the safe-area insets.
        EdgeToEdge.enable(this);
        // 顺滑：硬件层 + 系统自适应刷新率（不锁档）
        tuneSurface();

        boolean systemNight =
            (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // 首帧先用上次页面报的状态；没有记录（第一次装）才退回系统设置
        chromeDark = prefs().getBoolean(KEY_DARK, systemNight);

        applyChrome(chromeDark);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 网页告诉原生「我现在是深色还是浅色」时调这里（LiveWindow.setChrome）。
     *
     * 为什么不能自己读系统：App 的主题可以在页内被用户强制成浅色，而系统是深色——
     * 那时候原生若按系统刷，就会把系统栏刷成深色，看着就是上下两条黑边。
     * 页面知道自己实际在什么主题，所以以它为准。
     */
    public void setChromeFromPage(final boolean dark) {
        if (chromeDark == dark) return;
        runOnUiThread(() -> applyChrome(dark));
    }

    /** 把系统区、系统栏图标、WebView 底色一起刷成当前主题的纸色。 */
    private void applyChrome(boolean dark) {
        chromeDark = dark;
        prefs().edit().putBoolean(KEY_DARK, dark).apply();
        paintSystemArea(dark);
        tuneSystemBars(dark);
        try {
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().setBackgroundColor(paper(dark));
            }
        } catch (Throwable ignored) {
            // 还没建好 WebView 就算了，onCreate 后面会再刷一次
        }
    }

    private int paper(boolean dark) {
        return Color.parseColor(dark ? PAPER_DARK : PAPER_LIGHT);
    }

    /**
     * 系统区涂成纸色。
     *
     * 之前两次都栽在这：先设 transparent（透明=露出后面那一层，而后面什么都没画 → 黑），
     * 再按系统深浅色涂（系统深色 + 页面浅色 → 涂成近黑）。现在按**页面**的深浅色涂，
     * 并且三层一起上，任意一层没生效都不会露黑：
     *   1. 窗口背景（decor）——最底下那层；
     *   2. decor 与 android.R.id.content —— Capacitor 模板把 android:background
     *      设成 @null，等于让 decor 透明，透明就会露出系统黑；
     *   3. 系统栏本身 —— Android 14 及以下真的会听；15+ 系统强制 edge-to-edge
     *      会忽略它，但前两层已经把底铺成纸色了。
     */
    private void paintSystemArea(boolean dark) {
        int color = paper(dark);
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(color));
            View decor = getWindow().getDecorView();
            if (decor != null) decor.setBackgroundColor(color);
            View content = findViewById(android.R.id.content);
            if (content != null) content.setBackgroundColor(color);
            getWindow().setStatusBarColor(color);
            getWindow().setNavigationBarColor(color);
        } catch (Throwable ignored) {
            // 个别 ROM 不认就保持默认，不影响功能
        }
    }

    /** 系统栏图标的明暗：浅纸配深图标，深纸配浅图标。 */
    private void tuneSystemBars(boolean dark) {
        try {
            androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(!dark);
            controller.setAppearanceLightNavigationBars(!dark);
        } catch (Throwable ignored) {
            // 老 ROM 上用 XML 里的默认值
        }
    }

    /** 系统深浅色变了：只有在用户选「跟随系统」时页面才会跟着变，所以这里只是
     *  把「可能变了」这件事交给页面——页面算完实际主题会回调 setChromeFromPage。 */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 不在这里直接刷：按系统猜正是上一版的错误来源。页面的 useTheme 会在
        // 系统变化时重算并调 setChromeFromPage。
    }

    /**
     * 顺滑三件事，全走系统自适应，不锁死刷新率：
     *  1. 显示模式交给系统的可变刷新率（VRR）——强行钉住某一档反而会掉帧、更费电；
     *  2. WebView 用硬件层，滚动/动画走 GPU 合成；
     *  3. 关掉 overscroll 的光晕，边缘回弹不再触发一次绘制。
     */
    private void tuneSurface() {
        try {
            if (getBridge() == null) return;
            android.webkit.WebView web = getBridge().getWebView();
            if (web == null) return;
            web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            web.setOverScrollMode(View.OVER_SCROLL_NEVER);
            web.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        } catch (Throwable ignored) {
            // 个别 ROM 上这些调用可能不认，保持默认即可。
        }
    }
}
