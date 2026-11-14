import { useEffect, useState } from "react";

export type LogoTone = "auto" | "light" | "dark";

function readIsDarkTheme(): boolean {
  if (typeof document === "undefined") return false;
  const attr = document.documentElement.dataset.theme;
  if (attr === "dark") return true;
  if (attr === "light") return false;
  return typeof window !== "undefined" && window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/**
 * STU 事项助手 · 真理钟 L1 标志（2026-11-14 焕新）
 *
 * 采用 Liquid Composer 渲染的「霜瓷浅灰」与「黑曜霜晶」两套定稿 Badge：
 * - `tone="auto"`（默认）：跟随页面 `html[data-theme]`（及系统深浅色）实时切换浅/深版
 * - `tone="light"` / `tone="dark"`：在固定底色画布（如纯白画廊落地页）锁定对应版本
 *
 * 外层维持 `<svg>` 几何包装并按需匹配 128px / 256px 原生渲染图（≥2x Retina 锐度），
 * 保留原始圆角轮廓透明通道且无需额外 CSS 裁切。
 */
export function Logo({
  size = 40,
  className,
  tone = "auto",
}: {
  size?: number;
  className?: string;
  tone?: LogoTone;
}) {
  const [autoDark, setAutoDark] = useState<boolean>(readIsDarkTheme);

  useEffect(() => {
    if (tone !== "auto" || typeof document === "undefined") return;
    const sync = () => setAutoDark(readIsDarkTheme());
    sync();
    const obs = new MutationObserver(sync);
    obs.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    mq.addEventListener("change", sync);
    return () => {
      obs.disconnect();
      mq.removeEventListener("change", sync);
    };
  }, [tone]);

  const dark = tone === "dark" ? true : tone === "light" ? false : autoDark;
  const bucket = size > 64 ? 256 : 128;
  const href = `./truth-bell-${dark ? "dark" : "light"}-${bucket}.png`;

  return (
    <svg
      className={className}
      viewBox="0 0 128 128"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
    >
      <image href={href} width="128" height="128" />
    </svg>
  );
}
