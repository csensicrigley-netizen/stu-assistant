/**
 * 「有新版本」提示条 —— **只在安卓 App 里出现**。
 *
 * 2026-10-29 按爸爸要求改：网页端一条都不弹。
 * 网页版刷新就是最新，弹它纯属添堵（而且缓存一打架就变成无限弹窗）。
 * 手机上的 App 不会自己重装，所以那边保留一次提示：
 *   - 服务器 `/v1/meta/version` 说装着的包旧了 → 「立即更新」下 APK；
 *   - 同一个版本只提示一次，之后永不再提。
 *
 * 三条纪律：
 *   1. 版本检查失败当没发生——不许因此影响任何界面；
 *   2. 同一版本只提示一次；
 *   3. 不自动跳转、不自动下载，点了才动。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { IS_NATIVE } from "../lib/api";
import { apkHref, checkAnyUpdate, markKnown, skipFor, skippedCode, type UpdateInfo } from "../lib/version";

const CHECK_INTERVAL = 5 * 60 * 1000; // 5 分钟一次：发了版要能很快看见

export function UpdateBanner() {
  const [info, setInfo] = useState<UpdateInfo | null>(null);
  const [gone, setGone] = useState(false);
  const dismissed = useRef<number | null>(null);

  const run = useCallback(async () => {
    // 网页端：一条都不弹，连请求都不发
    if (!IS_NATIVE) return;
    const found = await checkAnyUpdate({ ignoreSkip: true });
    if (!found) {
      setInfo(null);
      return;
    }
    // 这一版已经提示过（或用户点过「稍后」）：什么都不做，保持现状，别再弹
    if (found.versionCode <= (skippedCode() ?? 0)) return;
    if (dismissed.current === found.versionCode) return;
    // 先记账再显示：同一个版本这辈子只提示这一次
    markKnown(found.versionCode);
    setInfo(found);
    setGone(false);
  }, []);

  useEffect(() => {
    void run();
    if (!IS_NATIVE) return;
    const timer = window.setInterval(() => void run(), CHECK_INTERVAL);
    // 回到前台就查一次：App 挂在后台几小时后回来，这一下最有用
    const onVisible = () => {
      if (document.visibilityState === "visible") void run();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [run]);

  if (!IS_NATIVE) return null;
  if (!info || gone) return null;

  const later = () => {
    dismissed.current = info.versionCode;
    skipFor(info.versionCode);
    setGone(true);
  };

  const isPage = false; // 网页版走自动刷新，不会走到这里
  const notes = info.notes.slice(0, 3);

  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: "fixed",
        left: "max(12px, env(safe-area-inset-left))",
        right: "max(12px, env(safe-area-inset-right))",
        bottom: "calc(84px + env(safe-area-inset-bottom))",
        zIndex: 60,
        display: "flex",
        gap: 12,
        alignItems: "center",
        padding: "12px 14px",
        borderRadius: 16,
        background: "var(--bar-bg, rgba(28,28,30,.92))",
        color: "var(--ink-inverse, #fff)",
        boxShadow: "0 10px 30px rgba(0,0,0,.28)",
        backdropFilter: "blur(12px)",
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: "var(--fs-14, 14px)", fontWeight: 600 }}>
          {isPage ? `网页有新版本 ${info.versionName}` : `新版本 ${info.versionName} 可以更新`}
          {info.mandatory ? "（必须更新）" : ""}
        </div>
        {notes.length > 0 && (
          <ul
            style={{
              margin: "4px 0 0",
              padding: 0,
              listStyle: "none",
              fontSize: "var(--fs-12, 12px)",
              opacity: 0.8,
              lineHeight: 1.5,
            }}
          >
            {notes.map((n) => (
              <li key={n} style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                · {n}
              </li>
            ))}
          </ul>
        )}
      </div>
      {!info.mandatory && (
        <button
          type="button"
          onClick={later}
          style={{
            border: "none",
            background: "transparent",
            color: "inherit",
            opacity: 0.7,
            fontSize: "var(--fs-13, 13px)",
            padding: "8px 6px",
            cursor: "pointer",
          }}
        >
          稍后
        </button>
      )}
      {isPage ? (
        <button
          type="button"
          onClick={() => {
            // 只刷页面，不动登录态；硬刷一次就能把旧 js 换掉
            window.location.reload();
          }}
          style={{
            flex: "0 0 auto",
            padding: "9px 14px",
            border: "none",
            borderRadius: 999,
            background: "var(--brand-bg, #0a84ff)",
            color: "var(--brand-ink, #fff)",
            fontSize: "var(--fs-13, 13px)",
            fontWeight: 600,
            cursor: "pointer",
          }}
        >
          刷新页面
        </button>
      ) : (
        <a
          href={apkHref(info.apk)}
          style={{
            flex: "0 0 auto",
            padding: "9px 14px",
            borderRadius: 999,
            background: "var(--brand-bg, #0a84ff)",
            color: "var(--brand-ink, #fff)",
            fontSize: "var(--fs-13, 13px)",
            fontWeight: 600,
            textDecoration: "none",
          }}
        >
          立即更新
        </a>
      )}
    </div>
  );
}
