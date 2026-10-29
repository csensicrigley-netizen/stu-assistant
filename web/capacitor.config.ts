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
  android: {
    allowMixedContent: false,
    webContentsDebuggingEnabled: false,
  },
  plugins: {
    SystemBars: {
      // the page lays itself out with env(safe-area-inset-*) and viewport-fit=cover
      insetsHandling: "css",
      initialViewportFitValueHint: "cover",
    },
  },
};

export default config;
