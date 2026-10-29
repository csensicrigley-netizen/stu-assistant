import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

// The Android build (Capacitor) loads files from the device; the service worker
// is only for the browser version.
const NATIVE = process.env.STU_TARGET === "android";

export default defineConfig({
  // relative base: the same dist works at a domain root, a sub-path, and on device
  base: "./",
  plugins: [
    react(),
    VitePWA({
      // 2026-10-29：离线缓存整体停用。
      // 它会预缓存 index.html，页面就永远是旧 js；旧 js 里的「有新版本」
      // 判断一看服务器版本变高就弹窗，刷新又拿回旧 js —— 变成永远弹不完的死循环。
      // 关掉之后不再产出 sw.js，浏览器发现 /sw.js 404 会把旧 SW 注销掉。
      disable: true,
      registerType: "autoUpdate",
      includeAssets: ["icon.svg", "favicon-32.png", "apple-touch-icon.png"],
      manifest: {
        name: "STU 事项助手",
        short_name: "STU 助手",
        description: "下一节课在哪，作业几点截止。",
        theme_color: "#f9f6f1",
        background_color: "#f9f6f1",
        display: "standalone",
        orientation: "portrait",
        start_url: "./",
        scope: "./",
        lang: "zh-CN",
        icons: [
          { src: "icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
          { src: "icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
          { src: "icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
          { src: "icon.svg", sizes: "any", type: "image/svg+xml", purpose: "any" },
        ],
      },
      workbox: {
        globPatterns: ["**/*.{js,css,html,svg,woff2,png}"],
        globIgnores: ["**/download/**", "**/shots/**"],
        // never let the SPA fallback swallow API calls or the APK download
        navigateFallbackDenylist: [/^\/v1\//, /^\/download\//],
        // API answers are per user and are NOT cached here (a URL-keyed cache
        // would hand one user's data to the next person on a shared browser);
        // the App keeps its own per-user copy for offline use.
      },
    }),
  ],
  build: {
    target: "es2020",
    /*
     * 手动分包的理由是缓存，不是体积：把 react 和 motion 从业务代码里拆出来，
     * 改一个页面只让业务包换哈希，vendor 包还留在 WebView 缓存里 —— 手机上
     * 一次更新的下载量能少一大半。手写 manualChunks 而不是自动分包，是因为
     * 自动分包的边界会随依赖顺序变，缓存收益反而不稳。
     */
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          if (id.includes("motion") || id.includes("framer-motion")) return "vendor-motion";
          if (id.includes("react") || id.includes("scheduler")) return "vendor-react";
          return "vendor";
        },
      },
    },
  },
  server: {
    port: 5180,
    watch: {
      // never watch build artefacts, screenshots, the Android project, or browser profiles
      ignored: [
        "**/dist/**",
        "**/dist-android/**",
        "**/shots/**",
        "**/.tmp/**",
        "**/node_modules/**",
        "**/android/**",
        "**/_legacy/**",
        // 编辑器保存时会在目标文件旁建一个 .xxx.tmpdir/，那一瞬间目录是锁的，
        // 监视器去 watch 它会 EBUSY 把 dev server 直接打挂。整条路径不监视。
        "**/*.tmpdir/**",
      ],
    },
    proxy: {
      "/v1": { target: "http://127.0.0.1:8100", changeOrigin: true },
    },
  },
});
