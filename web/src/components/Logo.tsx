import { useId } from "react";

/**
 * STU 事项助手 · 标志（2026-10-31 重做）
 *
 * ── 为什么换 ──────────────────────────────────────────────────────
 * 旧的是一块紫色圆角方块 + 白色 "S"。用户的原话是"紫色像夜店风格"。
 * 紫色饱和度高、明度低，压在深底上显闷；而且那个 S 只是一条曲线，
 * 不表达这个产品到底是什么。
 *
 * ── 新标志表达什么 ────────────────────────────────────────────────
 * 三件事叠在一个方形软胶块里：
 *   1. **一天的路径**：一条从左下走到右上的折线，代表"在教室之间走"
 *   2. **三节课**：路径上的三个圆点，中间那颗最大最亮 = "现在"
 *   3. **底色是蜜桃橙 → 珊瑚**：暖、有食欲、跟"可爱"兼容，不是冷紫
 *
 * ── 2.5D 质感怎么来 ──────────────────────────────────────────────
 * 不是靠滤镜，是三层叠：
 *   · 底：斜向渐变（上亮下暗），模拟受光
 *   · 顶：一层从白到透明的斜掠高光（受光面）
 *   · 边：底部一条内暗边（侧壁）
 * 图形本身（路径与圆点）也有独立高光，才像"嵌在软胶里"。
 */
export function Logo({ size = 40, className }: { size?: number; className?: string }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg
      className={className}
      viewBox="0 0 128 128"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        {/* 底：受光渐变（左上亮 → 右下暗） */}
        <linearGradient id={`${id}-bg`} x1="0.12" y1="0" x2="0.9" y2="1">
          <stop offset="0" style={{ stopColor: "var(--logo-hi)" }} />
          <stop offset="1" style={{ stopColor: "var(--logo-lo)" }} />
        </linearGradient>

        {/* 顶面斜掠高光 */}
        <linearGradient id={`${id}-glaze`} x1="0.2" y1="0" x2="0.55" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity="0.42" />
          <stop offset="0.55" stopColor="#fff" stopOpacity="0.06" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </linearGradient>

        {/* 底边内暗边（侧壁） */}
        <linearGradient id={`${id}-wall`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0.72" stopColor="#000" stopOpacity="0" />
          <stop offset="1" stopColor="#000" stopOpacity="0.2" />
        </linearGradient>

        {/* 路径高光：让线条看起来是凸起的 */}
        <linearGradient id={`${id}-path`} x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" style={{ stopColor: "var(--logo-paper)" }} stopOpacity="0.8" />
          <stop offset="1" style={{ stopColor: "var(--logo-paper)" }} />
        </linearGradient>
      </defs>

      <rect width="128" height="128" rx="34" fill={`url(#${id}-bg)`} />
      <rect width="128" height="128" rx="34" fill={`url(#${id}-glaze)`} />
      <rect width="128" height="128" rx="34" fill={`url(#${id}-wall)`} />

      {/* 一天的路径：左下 → 右上，两次折角 = 走两次教室 */}
      <path
        d="M34 92 L56 92 L56 64 L80 64 L80 40 L96 40"
        fill="none"
        stroke={`url(#${id}-path)`}
        strokeWidth="9"
        strokeLinecap="round"
        strokeLinejoin="round"
        opacity="0.95"
      />

      {/* 三节课：起点小、中间（现在）最大最亮、终点中 */}
      <circle cx="34" cy="92" r="7" style={{ fill: "var(--logo-paper)" }} opacity="0.72" />
      <circle cx="68" cy="78" r="13" style={{ fill: "var(--logo-bead)" }} />
      <circle cx="64.4" cy="73.6" r="5.4" fill="#fff" opacity="0.34" />
      <circle cx="96" cy="40" r="8" style={{ fill: "var(--logo-paper)" }} opacity="0.86" />
    </svg>
  );
}
