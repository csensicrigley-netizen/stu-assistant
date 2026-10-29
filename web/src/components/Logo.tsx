import { useId } from "react";

/**
 * The mark, identical to the launcher icon (scripts/icons.mjs): an S drawn as
 * one route through the day, with a bead for "now". The four colours are
 * tokens, so the mark follows whichever colour scheme 我的 → 外观 picks.
 */
const PATH = "M662 264H406A124 124 0 0 0 406 512H618A124 124 0 0 1 618 760H362";

export function Logo({ size = 40, className }: { size?: number; className?: string }) {
  const id = useId().replace(/:/g, "");
  return (
    <svg
      className={className}
      viewBox="0 0 1024 1024"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id={`${id}-bg`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" style={{ stopColor: "var(--logo-hi)" }} />
          <stop offset="1" style={{ stopColor: "var(--logo-lo)" }} />
        </linearGradient>
        <radialGradient id={`${id}-hl`} cx="0.2" cy="0.1" r="0.8">
          <stop offset="0" stopColor="#fff" stopOpacity="0.18" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </radialGradient>
        <mask id={`${id}-cut`} maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
          <rect width="1024" height="1024" fill="#fff" />
          <circle cx="512" cy="512" r="79" fill="#000" />
        </mask>
      </defs>
      <rect width="1024" height="1024" rx="232" fill={`url(#${id}-bg)`} />
      <rect width="1024" height="1024" rx="232" fill={`url(#${id}-hl)`} />
      <path
        d={PATH}
        fill="none"
        style={{ stroke: "var(--logo-paper)" }}
        strokeWidth="100"
        strokeLinecap="round"
        strokeLinejoin="round"
        mask={`url(#${id}-cut)`}
      />
      <circle cx="512" cy="512" r="62" style={{ fill: "var(--logo-bead)" }} />
    </svg>
  );
}
