import type { CapacitorConfig } from "@capacitor/cli";

/**
 * Android App = the same web App, bundled on the device, talking to the
 * user's own HTTPS server (address baked in with VITE_API_BASE at build time,
 * or typed once on the sign-in screen).
 */
const config: CapacitorConfig = {
  // permanent: changing it later makes the App a different app to Android
  appId: "com.stuassistant.app",
  appName: "STU 事项助手",
  // a separate build: no service worker, and the website's dist/ stays untouched
  webDir: "dist-android",
  /*
   * WebView 自己的底色 = 樱粉纸色。
   * 没有这一条时，WebView 在首帧 / 内容不足一屏的位置会露出窗口底色
   * （深色主题下尤其明显，就是眼睛看到的"上下两条黑边"之一）。
   * 主题是浅/深两套，这里给浅色纸；深色由 CSS 与 index.html 的内联底补上。
   */
  backgroundColor: "#fff0f1",
  android: {
    allowMixedContent: false,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SystemBars: {
      // the page lays itself out with --safe-area-inset-* (see styles/tokens.css)
      insetsHandling: "css",
      initialViewportFitValueHint: "cover",
    },
  },
};

export default config;
