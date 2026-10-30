import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MotionConfig } from "motion/react";
import App from "./App";
import { installInteractionFeedback } from "./lib/haptics";

import "./styles/tokens.css";
import "./styles/app.css";

/**
 * ?frame=1 means "running inside the phone shell on the demo page". A real
 * device reports safe-area insets through env(); an iframe reports 0, so the
 * shell passes them in and the App reserves the same space it would on a phone.
 */
const params = new URLSearchParams(location.search);
if (params.has("frame")) {
  const root = document.documentElement;
  root.style.setProperty("--safe-t", params.get("safe-t") ?? "54px");
  root.style.setProperty("--safe-b", params.get("safe-b") ?? "26px");
  root.dataset.frame = "1";
}

const el = document.getElementById("root");
if (!el) throw new Error("no #root");
const stopFeedback = installInteractionFeedback();
if (import.meta.hot) import.meta.hot.dispose(stopFeedback);

/**
 * 一次性清理：把旧的 service worker 和它的缓存全注销掉。
 *
 * 为什么：SW 会把 index.html 一起预缓存，页面于是永远加载旧的那份 js；
 * 而旧 js 的「有新版本」判断看到服务器版本变高就弹窗——刷一次弹一次，
 * 刷新拿到的还是旧 js，成了死循环（2026-10-29 把爸爸弹烦的就是这个）。
 * 现在不用离线缓存了，清干净之后页面每次直连服务器，天然是最新的。
 */
void (async () => {
  try {
    if ("serviceWorker" in navigator) {
      const regs = await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map((reg) => reg.unregister()));
    }
    if ("caches" in window) {
      const keys = await caches.keys();
      await Promise.all(keys.map((key) => caches.delete(key)));
    }
  } catch {
    /* 清不掉也不影响使用 */
  }
})();

createRoot(el).render(
  <StrictMode>
    <MotionConfig reducedMotion="user">
      <App />
    </MotionConfig>
  </StrictMode>,
);
