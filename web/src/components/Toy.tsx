import { useId } from "react";

/**
 * 卡片里的 2.5D 软胶插画（2026-10-31 · 二版）
 *
 * ── 一版错在哪 ────────────────────────────────────────────────────
 * 一版照抄了参考图的角色（云、机器人、星球、太阳花、心、星），
 * 但那些跟「课表 / 作业 / 通知」没有任何关系——卡片上写着"作业排队"，
 * 画一朵云，纯装饰。用户一句话点破：「卡通画和卡片内容没有关系」。
 *
 * ── 二版怎么做 ────────────────────────────────────────────────────
 * **玩具就是功能本身**，只是把它做成有厚度、有高光、有眼睛的软胶角色：
 *   clock     → 下一节课（闹钟：钟面 + 指针 + 两只铃耳）
 *   calendar  → 一周光谱（台历：七格 + 装订环）
 *   checklist → 作业排队（清单：三行勾选框，前两行已勾）
 *   paper     → 考试与成绩（试卷：折角 + A+）
 *   bell      → 通知（铃铛：铃身 + 铃舌 + 提示点）
 *   shield    → 账号隐私（盾牌：锁孔）
 *
 * 质感统一：底色渐变 + 顶面高光 + 底部暗边 + 地面投影 + 一双眼睛。
 * 眼睛是"可爱"的唯一来源，也是让几何图形变成角色的关键。
 */

export type ToyKind = "clock" | "calendar" | "checklist" | "paper" | "bell" | "shield";

interface Props {
  kind: ToyKind;
  size?: number;
}

export function Toy({ kind, size = 130 }: Props) {
  const id = useId().replace(/:/g, "");
  const uid = (n: string) => `${id}-${n}`;

  const defs = (
    <defs>
      {/* 顶面受光 */}
      <radialGradient id={uid("hi")} cx="0.33" cy="0.22" r="0.72">
        <stop offset="0" stopColor="#fff" stopOpacity="0.9" />
        <stop offset="0.5" stopColor="#fff" stopOpacity="0.16" />
        <stop offset="1" stopColor="#fff" stopOpacity="0" />
      </radialGradient>
      {/* 底边暗边（侧壁） */}
      <linearGradient id={uid("shade")} x1="0" y1="0.5" x2="0" y2="1">
        <stop offset="0" stopColor="#000" stopOpacity="0" />
        <stop offset="1" stopColor="#000" stopOpacity="0.26" />
      </linearGradient>
      {/* 纸张类的白面 */}
      <linearGradient id={uid("paper")} x1="0.2" y1="0" x2="0.8" y2="1">
        <stop offset="0" stopColor="#fffdf9" />
        <stop offset="1" stopColor="#f0ebe3" />
      </linearGradient>
      <filter id={uid("soft")} x="-30%" y="-30%" width="160%" height="180%">
        <feDropShadow dx="0" dy="5" stdDeviation="5" floodColor="#000" floodOpacity="0.3" />
      </filter>
    </defs>
  );

  /** 一双眼睛：黑珠 + 两点高光。所有角色共用，保证"同一套玩具"。 */
  const eyes = (cx1: number, cx2: number, cy: number, r: number) => (
    <g>
      <ellipse cx={cx1} cy={cy} rx={r} ry={r * 1.14} fill="#241f1c" />
      <ellipse cx={cx2} cy={cy} rx={r} ry={r * 1.14} fill="#241f1c" />
      <circle cx={cx1 - r * 0.3} cy={cy - r * 0.44} r={r * 0.36} fill="#fff" opacity="0.96" />
      <circle cx={cx2 - r * 0.3} cy={cy - r * 0.44} r={r * 0.36} fill="#fff" opacity="0.96" />
      <circle cx={cx1 + r * 0.28} cy={cy + r * 0.38} r={r * 0.16} fill="#fff" opacity="0.5" />
      <circle cx={cx2 + r * 0.28} cy={cy + r * 0.38} r={r * 0.16} fill="#fff" opacity="0.5" />
    </g>
  );

  const body = () => {
    switch (kind) {
      /* ── 下一节课：闹钟 ──
         钟面朝你，两只眼睛在钟面上，指针从眼睛之间穿过去。
         两只铃耳在左上右上，这是"闹钟"最容易被认出的特征。 */
      case "clock":
        return (
          <g filter={`url(#${uid("soft")})`}>
            {/* 铃耳 */}
            <circle cx="30" cy="26" r="11" fill="#fff4e8" />
            <circle cx="90" cy="26" r="11" fill="#fff4e8" />
            {/* 表身 */}
            <circle cx="60" cy="60" r="38" fill="#fffaf3" />
            <circle cx="60" cy="60" r="38" fill={`url(#${uid("hi")})`} />
            <circle cx="60" cy="60" r="38" fill={`url(#${uid("shade")})`} />
            {/* 内圈：钟面 */}
            <circle cx="60" cy="60" r="28" fill="#2f2a26" />
            {/* 指针：短针指 10 点、长针指 2 点（一节课的跨度） */}
            <path
              d="M60 60 L60 42 M60 60 L74 68"
              stroke="#ffd166"
              strokeWidth="3.4"
              strokeLinecap="round"
            />
            {eyes(50, 70, 62, 4.6)}
            {/* 顶部按钮 */}
            <rect x="55" y="12" width="10" height="8" rx="4" fill="#e8dccd" />
          </g>
        );

      /* ── 一周光谱：台历 ──
         七格代表七天，前几格有颜色（有课），装订环在顶部。 */
      case "calendar":
        return (
          <g filter={`url(#${uid("soft")})`}>
            {/* 装订环 */}
            <rect x="26" y="14" width="7" height="14" rx="3.5" fill="#e2d6c6" />
            <rect x="46" y="14" width="7" height="14" rx="3.5" fill="#e2d6c6" />
            <rect x="66" y="14" width="7" height="14" rx="3.5" fill="#e2d6c6" />
            <rect x="86" y="14" width="7" height="14" rx="3.5" fill="#e2d6c6" />
            {/* 卡纸 */}
            <rect x="18" y="24" width="84" height="72" rx="12" fill={`url(#${uid("paper")})`} />
            <rect x="18" y="24" width="84" height="72" rx="12" fill={`url(#${uid("hi")})`} />
            {/* 顶部色条 */}
            <path d="M18 40 h84" stroke="#e9e0d4" strokeWidth="3" />
            {/* 七格：前四格有色 = 有课 */}
            {[0, 1, 2, 3].map((i) => (
              <rect key={i} x={26 + i * 17} y="48" width="12" height="9" rx="3" fill="#c9b8f0" />
            ))}
            {[0, 1, 2].map((i) => (
              <rect key={`b${i}`} x={26 + i * 17} y="62" width="12" height="9" rx="3" fill="#f0c8d6" />
            ))}
            <rect x="26" y="62" width="12" height="9" rx="3" fill="#f0c8d6" />
            {eyes(52, 68, 82, 4.4)}
          </g>
        );

      /* ── 作业排队：清单 ──
         三行，前两行打了勾（已完成），第三行空着（待办）。 */
      case "checklist":
        return (
          <g filter={`url(#${uid("soft")})`}>
            <rect x="20" y="16" width="80" height="80" rx="13" fill={`url(#${uid("paper")})`} />
            <rect x="20" y="16" width="80" height="80" rx="13" fill={`url(#${uid("hi")})`} />
            {/* 三行：勾选框 + 横线 */}
            {[0, 1, 2].map((i) => {
              const y = 30 + i * 20;
              const done = i < 2;
              return (
                <g key={i}>
                  <rect
                    x="28"
                    y={y}
                    width="13"
                    height="13"
                    rx="4"
                    fill={done ? "#7ed2a0" : "#fff"}
                    stroke={done ? "none" : "#d8cfc2"}
                    strokeWidth="1.6"
                  />
                  {done && (
                    <path
                      d={`M31 ${y + 6.6} l3.2 3.2 l6-6.4`}
                      stroke="#fff"
                      strokeWidth="2.2"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      fill="none"
                    />
                  )}
                  <rect
                    x="47"
                    y={y + 5}
                    width={done ? 30 : 38}
                    height="3.4"
                    rx="1.7"
                    fill={done ? "#ddd4c8" : "#c9bfb2"}
                  />
                </g>
              );
            })}
            {eyes(60, 76, 84, 3.8)}
          </g>
        );

      /* ── 考试与成绩：试卷 ──
         右上折角（试卷的通用符号）+ 一个大 A+。 */
      case "paper":
        return (
          <g filter={`url(#${uid("soft")})`}>
            <path
              d="M24 16 h56 l16 16 v64 a10 10 0 0 1 -10 10 H34 a10 10 0 0 1 -10 -10 Z"
              fill={`url(#${uid("paper")})`}
            />
            <path
              d="M24 16 h56 l16 16 v64 a10 10 0 0 1 -10 10 H34 a10 10 0 0 1 -10 -10 Z"
              fill={`url(#${uid("hi")})`}
            />
            {/* 折角 */}
            <path d="M80 16 v16 h16 Z" fill="#e4dbcf" />
            {/* A+ */}
            <text
              x="46"
              y="62"
              textAnchor="middle"
              fontSize="26"
              fontWeight="800"
              fill="#e0552f"
              fontFamily="system-ui, sans-serif"
            >
              A+
            </text>
            {/* 两行横线（批注） */}
            <rect x="34" y="72" width="46" height="3.4" rx="1.7" fill="#d8cfc2" />
            <rect x="34" y="80" width="34" height="3.4" rx="1.7" fill="#d8cfc2" />
            {eyes(52, 68, 92, 3.6)}
          </g>
        );

      /* ── 通知：铃铛 ──
         铃身 + 铃舌 + 右上角的红点（未读）。 */
      case "bell":
        return (
          <g filter={`url(#${uid("soft")})`}>
            {/* 铃身 */}
            <path
              d="M60 18 c-17 0 -28 12 -28 29 v14 l-7 9 a4 4 0 0 0 3 6.6 h64 a4 4 0 0 0 3 -6.6 l-7 -9 V47 c0 -17 -11 -29 -28 -29 Z"
              fill="#ffd98a"
            />
            <path
              d="M60 18 c-17 0 -28 12 -28 29 v14 l-7 9 a4 4 0 0 0 3 6.6 h64 a4 4 0 0 0 3 -6.6 l-7 -9 V47 c0 -17 -11 -29 -28 -29 Z"
              fill={`url(#${uid("hi")})`}
            />
            {/* 铃舌 */}
            <circle cx="60" cy="84" r="8" fill="#f0b954" />
            {/* 未读红点 */}
            <circle cx="86" cy="26" r="9" fill="#ef5b5b" />
            <circle cx="83" cy="23" r="3" fill="#fff" opacity="0.6" />
            {eyes(50, 70, 50, 5)}
          </g>
        );

      /* ── 账号隐私：盾牌 ──
         盾形 + 锁孔（钥匙孔 = 只有你能开）。 */
      default:
        return (
          <g filter={`url(#${uid("soft")})`}>
            <path
              d="M60 14 L92 26 v28 c0 22 -14 34 -32 42 c-18 -8 -32 -20 -32 -42 V26 Z"
              fill="#a9c6f5"
            />
            <path
              d="M60 14 L92 26 v28 c0 22 -14 34 -32 42 c-18 -8 -32 -20 -32 -42 V26 Z"
              fill={`url(#${uid("hi")})`}
            />
            <path
              d="M60 14 L92 26 v28 c0 22 -14 34 -32 42 c-18 -8 -32 -20 -32 -42 V26 Z"
              fill={`url(#${uid("shade")})`}
            />
            {/* 锁孔 */}
            <circle cx="60" cy="52" r="9" fill="#2c3f5e" />
            <path d="M57 58 h6 l2 14 h-10 Z" fill="#2c3f5e" />
            {eyes(50, 70, 82, 3.6)}
          </g>
        );
    }
  };

  return (
    <svg viewBox="0 0 120 110" width={size} height={(size * 110) / 120} aria-hidden="true" focusable="false">
      {defs}
      {body()}
    </svg>
  );
}
