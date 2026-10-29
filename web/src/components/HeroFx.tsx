/**
 * 主页大板块开场动画（迪士尼 2D 手绘质感）+ 常驻彩虹光。
 *
 * 三种，随机一种，一次会话只放一次：
 *   vine   两角藤蔓卷着长上来，叶片一片片撑开，末梢开出渐变花，收尾整幅呼一口气
 *   orbit  时间卡从原位"啵"地窜出，真 3D 绕卡片飞两圈，被立体书吸进去，
 *          书啪地合上再弹开，把卡吐回原位
 *   water  顶上漏水，三层浪从底部灌满，小鱼跃出水面炸水花，再带水痕退潮
 *
 * 画法（这版不是线稿）：
 *   * 主体一律是**闭合填充路径**，藤条由中心线 + 渐变宽度生成上下两条轮廓线
 *     （根部 14、梢部 1.2，靠轮廓收尖，不靠 stroke-width）
 *   * 每片叶：底色渐变 + 30% 白亮面 + 20% 暗面 + 反光边 + 叶脉
 *   * 每朵花：6 外瓣（外缘透光渐变）+ 6 内瓣（更深）+ 花心暗环 + 金色花心 + 高光点
 *   * 每条鱼：背深 / 肚白 / 鳃弧 / 背鳍 / 胸鳍 / 独立尾鳍 / 眼 + 眼神光
 *   * 书：投影 / 后封 / 内页堆（三层纸厚）/ 书脊 / 前封（可翻开）/ 紫色内光
 *   * 生长靠 mask 里那条中心线的 stroke-dashoffset 揭幕，所以藤是"顺曲线长"的
 *
 * 性能纪律：只动 transform / opacity / stroke-dashoffset；
 * 整层 pointer-events:none；prefers-reduced-motion 全关；一次会话只放一次。
 */
import { useEffect, useMemo, useState } from "react";
import type { CSSProperties } from "react";

/** 允许带自定义属性的 style（生长长度要在 keyframes 里用） */
type DrawStyle = CSSProperties & { "--len": number };

export type HeroEffect = "vine" | "orbit" | "water";

const KINDS: HeroEffect[] = ["vine", "orbit", "water"];
const SEEN_KEY = "stu.hero.fx";
/** 总时长：三种都在这之前收完，卸载时才不会硬切 */
const PLAY_MS = 5000;

function reducedMotion(): boolean {
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

export function useHeroEffect(): { effect: HeroEffect | null; done: boolean } {
  const [effect, setEffect] = useState<HeroEffect | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (reducedMotion()) {
      setDone(true);
      return;
    }
    let forced: HeroEffect | null = null;
    try {
      const q = new URLSearchParams(location.search).get("fx") as HeroEffect | null;
      if (q && KINDS.includes(q)) forced = q;
    } catch {
      forced = null;
    }
    if (forced) {
      setEffect(forced);
      const t = window.setTimeout(() => {
        setEffect(null);
        setDone(true);
      }, PLAY_MS);
      return () => window.clearTimeout(t);
    }
    let seen: string | null = null;
    try {
      seen = sessionStorage.getItem(SEEN_KEY);
    } catch {
      seen = null;
    }
    if (seen) {
      setDone(true);
      return;
    }
    const pick = KINDS[Math.floor(Math.random() * KINDS.length)];
    try {
      sessionStorage.setItem(SEEN_KEY, pick);
    } catch {
      /* 隐私模式：不记也不重播 */
    }
    setEffect(pick);
    const timer = window.setTimeout(() => {
      setEffect(null);
      setDone(true);
    }, PLAY_MS);
    return () => window.clearTimeout(timer);
  }, []);

  return { effect, done };
}

/* ==================================================================== 几何
   尾巴：一串中心点 + 每一处的宽度 → 一上一下两条轮廓，闭合成填充形状。
   这就是"手绘藤条"的来源：两头不一样粗、拐弯处外侧比内侧宽。 */

type P = readonly [number, number];

const nr = (v: number) => Math.round(v * 10) / 10;
const xy = (p: P) => `${nr(p[0])} ${nr(p[1])}`;

function crAt(p0: P, p1: P, p2: P, p3: P, t: number): P {
  const t2 = t * t;
  const t3 = t2 * t;
  const f = (a: number, b: number, c: number, d: number) =>
    0.5 *
    (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
  return [f(p0[0], p1[0], p2[0], p3[0]), f(p0[1], p1[1], p2[1], p3[1])];
}

/** Catmull-Rom 加密：控制点之间插 per 个采样点，得到一条顺滑的中心线 */
function densify(pts: readonly P[], per: number): P[] {
  const out: P[] = [];
  const n = pts.length;
  for (let i = 0; i < n - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)];
    const p1 = pts[i];
    const p2 = pts[i + 1];
    const p3 = pts[Math.min(n - 1, i + 2)];
    for (let s = 0; s < per; s++) out.push(crAt(p0, p1, p2, p3, s / per));
  }
  out.push(pts[n - 1]);
  return out;
}

function crSeg(p0: P, p1: P, p2: P, p3: P): string {
  const c1: P = [p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6];
  const c2: P = [p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6];
  return `C${xy(c1)} ${xy(c2)} ${xy(p2)}`;
}

/** 开口曲线（给 mask 里那条"生长线"用） */
function openSpline(pts: readonly P[]): string {
  let d = `M${xy(pts[0])}`;
  const n = pts.length;
  for (let i = 0; i < n - 1; i++) {
    d += crSeg(pts[Math.max(0, i - 1)], pts[i], pts[i + 1], pts[Math.min(n - 1, i + 2)]);
  }
  return d;
}

/** 闭合曲线（给藤条的轮廓用，首尾自动接顺） */
function closedSpline(pts: readonly P[]): string {
  const n = pts.length;
  let d = `M${xy(pts[0])}`;
  for (let i = 1; i <= n; i++) {
    d += crSeg(pts[(i - 1) % n], pts[i % n], pts[(i + 1) % n], pts[(i + 2) % n]);
  }
  return `${d}Z`;
}

/** 宽度按 t 平滑插值（smoothstep，收尖才不会突然断） */
function widthAt(ws: readonly number[], t: number): number {
  const x = t * (ws.length - 1);
  const i = Math.min(ws.length - 2, Math.max(0, Math.floor(x)));
  const f = x - i;
  const s = f * f * (3 - 2 * f);
  return ws[i] + (ws[i + 1] - ws[i]) * s;
}

/** 中心线 + 宽度 → { 闭合轮廓, 中心线, 中心线长, 高光带, 暗面带 } */
function taper(center: readonly P[], ws: readonly number[], per: number) {
  const dense = densify(center, per);
  const n = dense.length;
  const left: P[] = [];
  const right: P[] = [];
  const hiL: P[] = [];
  const hiR: P[] = [];
  const loL: P[] = [];
  const loR: P[] = [];
  let len = 0;
  for (let i = 0; i < n; i++) {
    if (i > 0) len += Math.hypot(dense[i][0] - dense[i - 1][0], dense[i][1] - dense[i - 1][1]);
    const a = dense[Math.max(0, i - 1)];
    const b = dense[Math.min(n - 1, i + 1)];
    const dx = b[0] - a[0];
    const dy = b[1] - a[1];
    const m = Math.hypot(dx, dy) || 1;
    const nx = -dy / m;
    const ny = dx / m;
    const w = widthAt(ws, i / (n - 1)) / 2;
    left.push([dense[i][0] + nx * w, dense[i][1] + ny * w]);
    right.push([dense[i][0] - nx * w, dense[i][1] - ny * w]);
    // 管状体积：一条偏左上（受光）、一条偏右下（背光）
    const hiOff = w * 0.44;
    const hiR2 = w * 0.17;
    hiL.push([dense[i][0] + nx * (hiOff + hiR2), dense[i][1] + ny * (hiOff + hiR2)]);
    hiR.push([dense[i][0] + nx * (hiOff - hiR2), dense[i][1] + ny * (hiOff - hiR2)]);
    const loOff = w * 0.38;
    const loR2 = w * 0.3;
    loL.push([dense[i][0] - nx * (loOff + loR2), dense[i][1] - ny * (loOff + loR2)]);
    loR.push([dense[i][0] - nx * (loOff - loR2), dense[i][1] - ny * (loOff - loR2)]);
  }
  return {
    body: closedSpline(left.concat(right.reverse())),
    spine: openSpline(dense),
    len: len * 1.18,
    hi: closedSpline(hiL.concat(hiR.reverse())),
    lo: closedSpline(loL.concat(loR.reverse())),
  };
}

let seq = 0;
/** 每次挂载给一组唯一 id，免得 <defs> 撞车 */
function useFxTag(): string {
  const [tag] = useState(() => {
    seq += 1;
    return `stufx${seq}`;
  });
  return tag;
}

/* ============================================================ 1 藤蔓零件 */

/* 叶片：叶柄在原点、朝 +x。轮廓带下垂的叶尖和轻微鼓起，不是对称橄榄形。
   体积靠"底色渐变 + 基部暗影 + 受光带 + 高光条 + 叶脉 + 叶柄"六层叠出来。 */
const LEAF_BLADE =
  "M0 0 C6 -9 16 -13.4 25.2 -11.8 C30 -11 34 -8 34.6 -4.2 C30.4 -2 26 -0.2 21.6 2.2 C14.4 6 6.6 6.4 2.4 3.4 C0.8 2.2 0.2 1 0 0 Z";
const LEAF_LIT = "M4 -2.2 C9.4 -8.6 17 -11.8 23.6 -10.6 C18.2 -8.2 10.6 -5.4 4.6 -1.6 Z";
const LEAF_SPEC = "M10 -6.8 C12.6 -9 16 -10 18.4 -9.8 C15.6 -8 12.8 -6.8 10 -6.8 Z";
const LEAF_BASE_SHADE = "M1.4 0.4 C4.6 2.8 8.4 3.6 11.2 3 C8.4 4.6 4.4 4.2 1.4 0.4 Z";
const LEAF_MIDRIB = "M2 -0.6 C10 -3.4 19 -4.6 27.6 -4.2";
const LEAF_VEIN_UP = "M9.4 -3.2 C10.6 -6.4 11.4 -8.6 12 -9.8";
const LEAF_VEIN_UP2 = "M17.6 -4.2 C18.8 -7 19.8 -8.6 20.4 -9.6";
const LEAF_VEIN_DN = "M9.6 -3.2 C9.8 -1.2 9.4 0.8 8.6 2.2";
const LEAF_STALK = "M1.2 0.4 C-2 0.6 -4.6 -0.4 -6.6 -2.2 C-4.2 -1.4 -1.8 -0.8 1.4 -0.8 Z";

/** 花瓣：底部在原点、朝上，瓣尖带一点缺口（花不是荷叶边） */
const PETAL =
  "M0 0 C4.8 -3 6.4 -8.6 4.8 -13.6 C4 -15.8 2.4 -16.6 1.4 -15.2 C0.4 -16.6 -1.8 -16.4 -2.9 -14.6 C-5.4 -10.6 -4.6 -4.6 0 0 Z";
/** 花苞：还没开时鼓起来的那一瓣 */
const BUD = "M0 0 C4.6 -3.4 5.6 -9.6 0.8 -14 C-0.8 -15.4 -3.2 -14.2 -4 -10.6 C-5.2 -5.6 -2.6 -1.6 0 0 Z";
/** 花萼：花瓣后面那圈绿 */
const CALYX = "M0 0 C2.6 -1.8 3.6 -5 2.4 -7.4 C0.6 -5.2 -1.2 -2.6 0 0 Z";

type StemSpec = {
  key: string;
  pts: P[];
  ws: number[];
  delay: number;
  dur: number;
  back: boolean;
  tendril: boolean;
};

const STEMS: StemSpec[] = [
  {
    key: "a",
    pts: [
      [-10, 262],
      [14, 232],
      [34, 206],
      [44, 178],
      [40, 150],
      [44, 120],
      [58, 92],
      [78, 64],
    ],
    ws: [14, 12.5, 11, 9, 7.2, 5.4, 3.2, 1.3],
    delay: 0.18,
    dur: 1.2,
    back: false,
    tendril: false,
  },
  {
    key: "b",
    pts: [
      [330, 266],
      [304, 234],
      [282, 208],
      [274, 180],
      [284, 152],
      [276, 122],
      [258, 94],
      [240, 70],
    ],
    ws: [12.5, 11.4, 9.8, 8, 6.4, 4.6, 2.8, 1.2],
    delay: 0.3,
    dur: 1.2,
    back: false,
    tendril: false,
  },
  {
    key: "c",
    pts: [
      [92, 264],
      [112, 236],
      [132, 210],
      [144, 182],
      [148, 156],
      [160, 132],
      [176, 112],
    ],
    ws: [8.4, 7.8, 6.6, 5.4, 4.2, 2.8, 1.1],
    delay: 0.48,
    dur: 1,
    back: true,
    tendril: false,
  },
  {
    key: "d",
    pts: [
      [276, 176],
      [254, 166],
      [232, 150],
      [214, 132],
      [202, 114],
    ],
    ws: [5.4, 4.6, 3.4, 2.2, 0.9],
    delay: 0.72,
    dur: 0.75,
    back: false,
    tendril: true,
  },
  {
    key: "e",
    pts: [
      [44, 140],
      [34, 128],
      [25, 133],
      [25, 144],
      [33, 149],
      [38, 141],
    ],
    ws: [3.2, 2.5, 1.8, 0.9, 0.5, 0.35],
    delay: 0.88,
    dur: 0.6,
    back: false,
    tendril: true,
  },
  {
    key: "f",
    pts: [
      [262, 124],
      [276, 112],
      [288, 118],
      [288, 129],
      [278, 133],
      [274, 125],
    ],
    ws: [3, 2.3, 1.6, 0.8, 0.5, 0.35],
    delay: 0.96,
    dur: 0.6,
    back: false,
    tendril: true,
  },
];

const STEM_ART = STEMS.map((s) => {
  // 略加粗：根 14 → 15.4，梢 1.3 → 1.43，体量更足但仍强收尖
  const ws = s.ws.map((w) => w * 1.1);
  return { ...s, ws, art: taper(s.pts, ws, s.tendril ? 2 : 3) };
});

/** 叶片摆放：x, y, 转角, 缩放, 延迟, 是否后景 */
const LEAVES: [number, number, number, number, number, boolean][] = [
  [34, 210, -150, 1.08, 0.4, false],
  [46, 182, -26, 1.0, 0.5, false],
  [38, 156, -172, 0.88, 0.6, false],
  [40, 124, -18, 0.94, 0.7, false],
  [52, 96, -60, 0.82, 0.8, false],
  [284, 212, -166, 1.06, 0.46, false],
  [274, 186, -20, 0.98, 0.56, false],
  [288, 158, 12, 0.9, 0.66, false],
  [274, 126, -30, 0.94, 0.76, false],
  [258, 98, -62, 0.8, 0.86, false],
  [226, 154, -98, 0.72, 0.94, false],
  [210, 128, -74, 0.64, 1.0, false],
  [128, 214, -136, 0.78, 0.88, true],
  [146, 178, -44, 0.7, 0.98, true],
  [158, 142, -28, 0.64, 1.06, true],
  [72, 226, -34, 0.96, 1.1, false],
  [296, 232, -148, 0.92, 1.16, false],
];

/** 花朵摆放：x, y, 缩放, 延迟, 转角, 色调(0 粉 / 1 珊瑚), 瓣数 */
type BloomSpec = [number, number, number, number, number, 0 | 1, 5 | 6];
const BLOOMS: BloomSpec[] = [
  [78, 52, 1.55, 0.98, -8, 0, 6],
  [114, 82, 0.98, 1.06, 16, 1, 5],
  [46, 84, 0.96, 1.14, -22, 0, 6],
  [248, 56, 1.45, 1.1, 12, 1, 6],
  [214, 86, 1.06, 1.18, -14, 0, 5],
  [286, 92, 0.86, 1.26, 24, 1, 6],
  [196, 118, 0.98, 1.32, -6, 0, 6],
  [150, 132, 0.8, 1.4, 20, 1, 5],
  [64, 196, 0.72, 1.6, -14, 0, 6],
];

/** 花苞：还没开的那几支，负责"这丛还有得开" */
const BUDS: [number, number, number, number, number][] = [
  [62, 116, 0.9, 1.36, -12],
  [248, 140, 0.86, 1.44, 18],
  [190, 92, 0.8, 1.5, -6],
  [104, 160, 0.76, 1.56, 10],
  [262, 118, 0.7, 1.62, -18],
];

const OUT_ANGLES = [0, 62, 118, 180, 238, 302];
const IN_ANGLES = [28, 92, 148, 212, 268, 332];
const CALYX_ANGLES = [0, 72, 144, 216, 288];
const STAMEN_ANGLES = [0, 72, 144, 216, 288];

function Leaf({
  x,
  y,
  rot,
  s,
  delay,
  tag,
}: {
  x: number;
  y: number;
  rot: number;
  s: number;
  delay: number;
  tag: string;
}) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <g className="fx-leaf" style={{ animationDelay: `${delay}s` }}>
        <path d={LEAF_STALK} className="fx-leaf__stalk" />
        <path d={LEAF_BLADE} className="fx-leaf__blade" style={{ fill: `url(#${tag}-leaf)` }} />
        <path d={LEAF_BASE_SHADE} className="fx-leaf__shadow" />
        <path d={LEAF_LIT} className="fx-leaf__lit" />
        <path d={LEAF_SPEC} className="fx-leaf__spec" />
        <path d={LEAF_MIDRIB} className="fx-leaf__rib" />
        <path d={LEAF_VEIN_UP} className="fx-leaf__vein" />
        <path d={LEAF_VEIN_UP2} className="fx-leaf__vein" />
        <path d={LEAF_VEIN_DN} className="fx-leaf__vein" />
      </g>
    </g>
  );
}

function Blossom({
  x,
  y,
  s,
  delay,
  rot,
  tone,
  petals,
  tag,
}: {
  x: number;
  y: number;
  s: number;
  delay: number;
  rot: number;
  tone: 0 | 1;
  petals: 5 | 6;
  tag: string;
}) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <g className="fx-bloom-nod" style={{ animationDelay: `${delay + 0.66}s` }}>
        <g
          className={`fx-bloom${tone === 1 ? " fx-bloom--coral" : ""}${petals === 5 ? " fx-bloom--p5" : ""}`}
          style={{ animationDelay: `${delay}s` }}
        >
          <circle
            className="fx-bloom__halo"
            r="15"
            style={{ fill: `url(#${tag}-halo)`, animationDelay: `${delay + 0.05}s` }}
          />
          <g className="fx-bloom__calyx">
            {CALYX_ANGLES.map((a) => (
              <path
                key={`c${a}`}
                d={CALYX}
                transform={`rotate(${a + 36}) scale(1.35)`}
                style={{ fill: `url(#${tag}-sep)` }}
              />
            ))}
          </g>
          <path
            className="fx-bloom__bud"
            d={BUD}
            style={{
              fill: `url(#${tag}-petalIn${tone === 1 ? 2 : ""})`,
              animationDelay: `${delay + 0.02}s`,
            }}
          />
          <g className="fx-bloom__outer">
            {OUT_ANGLES.map((a, i) => (
              <path
                key={`o${a}`}
                className="fx-bloom__petal"
                d={PETAL}
                style={{
                  fill: `url(#${tag}-petalOut${tone === 1 ? 2 : ""})`,
                  animationDelay: `${delay + 0.12 + i * 0.026}s`,
                }}
              />
            ))}
          </g>
          <g className="fx-bloom__inner">
            {IN_ANGLES.map((a, i) => (
              <path
                key={`i${a}`}
                className="fx-bloom__petal fx-bloom__petal--in"
                d={PETAL}
                style={{
                  fill: `url(#${tag}-petalIn${tone === 1 ? 2 : ""})`,
                  animationDelay: `${delay + 0.2 + i * 0.026}s`,
                }}
              />
            ))}
          </g>
          <g className="fx-bloom__stamens" style={{ animationDelay: `${delay + 0.42}s` }}>
            {STAMEN_ANGLES.map((a) => (
              <g key={`s${a}`} transform={`rotate(${a})`}>
                <path d="M0 -4 C0.3 -5.4 0.5 -6.6 0.7 -7.4" className="fx-bloom__fil" />
                <circle cy="-7.8" r="0.82" className="fx-bloom__anther" />
              </g>
            ))}
          </g>
          <circle className="fx-bloom__ring" r="5.2" style={{ animationDelay: `${delay + 0.44}s` }} />
          <circle
            className="fx-bloom__core"
            r="3.6"
            style={{ fill: `url(#${tag}-core)`, animationDelay: `${delay + 0.46}s` }}
          />
          <circle
            className="fx-bloom__spark"
            r="1.4"
            cx="-1.1"
            cy="-1.2"
            style={{ animationDelay: `${delay + 0.58}s` }}
          />
        </g>
      </g>
    </g>
  );
}

function Bud({
  x,
  y,
  s,
  delay,
  rot,
  tag,
}: {
  x: number;
  y: number;
  s: number;
  delay: number;
  rot: number;
  tag: string;
}) {
  return (
    <g transform={`translate(${x} ${y}) rotate(${rot}) scale(${s})`}>
      <g className="fx-bud" style={{ animationDelay: `${delay}s` }}>
        <path d={BUD} style={{ fill: `url(#${tag}-petalIn)` }} />
        <path d={CALYX} transform="rotate(196) scale(1.5)" style={{ fill: `url(#${tag}-sep)` }} />
        <path d={CALYX} transform="rotate(250) scale(1.2)" style={{ fill: `url(#${tag}-sep)` }} />
        <path d="M-1.4 -6.4 C-0.6 -10.4 1 -13.4 3 -14.6" className="fx-bud__lit" />
      </g>
    </g>
  );
}

function Vine() {
  const tag = useFxTag();
  const front = STEM_ART.filter((s) => !s.back);
  const back = STEM_ART.filter((s) => s.back);

  const stem = (s: (typeof STEM_ART)[number]) => (
    <g key={s.key} mask={`url(#${tag}-m-${s.key})`}>
      <path d={s.art.body} style={{ fill: `url(#${tag}-stem)` }} />
      <path d={s.art.lo} className="fx-stem__lo" />
      <path d={s.art.hi} className="fx-stem__hi" />
    </g>
  );

  return (
    <svg
      className="fx-vine"
      viewBox="0 0 320 256"
      preserveAspectRatio="xMidYMid slice"
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={`${tag}-stem`} x1="0" y1="1" x2="0.55" y2="0">
          <stop offset="0%" stopColor="#235f38" />
          <stop offset="42%" stopColor="#3f8f4a" />
          <stop offset="76%" stopColor="#6ec172" />
          <stop offset="100%" stopColor="#9ee084" />
        </linearGradient>
        <linearGradient id={`${tag}-stemShade`} x1="0" y1="0" x2="1" y2="0.2">
          <stop offset="0%" stopColor="#0f3a22" stopOpacity="0" />
          <stop offset="58%" stopColor="#0f3a22" stopOpacity="0" />
          <stop offset="100%" stopColor="#0f3a22" stopOpacity="0.34" />
        </linearGradient>
        <linearGradient id={`${tag}-leaf`} x1="0" y1="0" x2="0.42" y2="1">
          <stop offset="0%" stopColor="#cdf28d" />
          <stop offset="26%" stopColor="#9adf80" />
          <stop offset="62%" stopColor="#5cb264" />
          <stop offset="88%" stopColor="#358c4d" />
          <stop offset="100%" stopColor="#27663c" />
        </linearGradient>
        <linearGradient id={`${tag}-sep`} x1="0" y1="1" x2="0.3" y2="0">
          <stop offset="0%" stopColor="#2b6f3e" />
          <stop offset="60%" stopColor="#4f9e57" />
          <stop offset="100%" stopColor="#86d47f" />
        </linearGradient>
        <radialGradient id={`${tag}-petalOut`} cx="0.5" cy="0.88" r="0.9">
          <stop offset="0%" stopColor="#c8477f" />
          <stop offset="34%" stopColor="#ee7cab" />
          <stop offset="74%" stopColor="#ffa9c8" />
          <stop offset="100%" stopColor="#ffd9e9" />
        </radialGradient>
        <radialGradient id={`${tag}-petalIn`} cx="0.5" cy="0.9" r="0.92">
          <stop offset="0%" stopColor="#a3325f" />
          <stop offset="52%" stopColor="#d95f92" />
          <stop offset="100%" stopColor="#ff9dc0" />
        </radialGradient>
        <radialGradient id={`${tag}-petalOut2`} cx="0.5" cy="0.88" r="0.9">
          <stop offset="0%" stopColor="#b8483c" />
          <stop offset="34%" stopColor="#ef7f62" />
          <stop offset="74%" stopColor="#ffab86" />
          <stop offset="100%" stopColor="#ffdcc2" />
        </radialGradient>
        <radialGradient id={`${tag}-petalIn2`} cx="0.5" cy="0.9" r="0.92">
          <stop offset="0%" stopColor="#9d3527" />
          <stop offset="52%" stopColor="#d9603f" />
          <stop offset="100%" stopColor="#ff9a72" />
        </radialGradient>
        <radialGradient id={`${tag}-core`} cx="0.36" cy="0.32" r="0.74">
          <stop offset="0%" stopColor="#fffdf0" />
          <stop offset="40%" stopColor="#ffe08d" />
          <stop offset="100%" stopColor="#f2a53d" />
        </radialGradient>
        <radialGradient id={`${tag}-halo`} cx="0.5" cy="0.5" r="0.5">
          <stop offset="0%" stopColor="#ffc2da" stopOpacity="0.42" />
          <stop offset="52%" stopColor="#ff9dc0" stopOpacity="0.16" />
          <stop offset="100%" stopColor="#ff9dc0" stopOpacity="0" />
        </radialGradient>
        {STEM_ART.map((s) => (
          <mask
            key={s.key}
            id={`${tag}-m-${s.key}`}
            maskUnits="userSpaceOnUse"
            x="-40"
            y="-40"
            width="400"
            height="340"
          >
            <path
              className="fx-stem__draw"
              d={s.art.spine}
              fill="none"
              stroke="#fff"
              strokeWidth={s.ws[0] + 12}
              strokeLinecap="round"
              strokeDasharray={s.art.len}
              strokeDashoffset={s.art.len}
              style={
                {
                  animationDelay: `${s.delay}s`,
                  animationDuration: `${s.dur}s`,
                  "--len": s.art.len,
                } as DrawStyle
              }
            />
          </mask>
        ))}
      </defs>

      {/* 后景：压在后面的藤、叶、花，低透明、无投影，只为景深 */}
      <g className="fx-cluster fx-cluster--back">
        {back.map(stem)}
        {LEAVES.filter((l) => l[5]).map(([x, y, r, s, d]) => (
          <Leaf key={`b${x}-${y}`} x={x} y={y} rot={r} s={s} delay={d} tag={tag} />
        ))}
      </g>

      {/* 前景：主体那几条藤 + 叶 + 花，带一层整幅 drop-shadow 出体积 */}
      <g className="fx-cluster fx-cluster--front">
        {front.map(stem)}
        {LEAVES.filter((l) => !l[5]).map(([x, y, r, s, d]) => (
          <Leaf key={`f${x}-${y}`} x={x} y={y} rot={r} s={s} delay={d} tag={tag} />
        ))}
        {BLOOMS.map(([x, y, s, d, r, tone, petals]) => (
          <Blossom
            key={`bl${x}-${y}`}
            x={x}
            y={y}
            s={s}
            delay={d}
            rot={r}
            tone={tone}
            petals={petals}
            tag={tag}
          />
        ))}
        {BUDS.map(([x, y, s, d, r]) => (
          <Bud key={`bd${x}-${y}`} x={x} y={y} s={s} delay={d} rot={r} tag={tag} />
        ))}
      </g>
    </svg>
  );
}

/* ======================================================= 2 绕飞 + 立体书 */

function Book() {
  return (
    <span className="fx-book">
      <span className="fx-book__shadow" />
      <span className="fx-book__glow" />
      <span className="fx-book__inner">
        <span className="fx-book__back" />
        <span className="fx-book__pages">
          <i className="fx-book__sheet" />
          <i className="fx-book__sheet" />
          <i className="fx-book__sheet" />
        </span>
        <span className="fx-book__spine" />
        <span className="fx-book__spine-hi" />
        <span className="fx-book__cover">
          <i className="fx-book__cover-shine" />
          <i className="fx-book__cover-mark" />
        </span>
      </span>
    </span>
  );
}

function Orbit({ time, title }: { time: string; title: string }) {
  return (
    <span className="fx-orbit">
      <span className="fx-orbit__trail fx-orbit__trail--a" />
      <span className="fx-orbit__trail fx-orbit__trail--b" />
      <span className="fx-orbit__stage">
        <span className="fx-orbit__flight">
          <span className="fx-orbit__arm">
            <span className="fx-chip">
              <span className="fx-chip__face fx-chip__face--front">
                <b className="fx-chip__num">{time}</b>
                <i className="fx-chip__ttl">{title}</i>
              </span>
              <span className="fx-chip__face fx-chip__face--back">
                <i className="fx-chip__bar" />
                <i className="fx-chip__bar fx-chip__bar--short" />
              </span>
            </span>
          </span>
        </span>
      </span>
      <span className="fx-orbit__book">
        <Book />
      </span>
      <span className="fx-star fx-star--a">
        <svg viewBox="0 0 16 16">
          <path d="M8 0.6 C9 5 11 7 15.4 8 C11 9 9 11 8 15.4 C7 11 5 9 0.6 8 C5 7 7 5 8 0.6 Z" />
        </svg>
      </span>
      <span className="fx-star fx-star--b">
        <svg viewBox="0 0 16 16">
          <path d="M8 0.6 C9 5 11 7 15.4 8 C11 9 9 11 8 15.4 C7 11 5 9 0.6 8 C5 7 7 5 8 0.6 Z" />
        </svg>
      </span>
    </span>
  );
}

/* ============================================== 3 漏水 / 灌满 / 鱼 / 退潮 */

const WAVE_BACK =
  "M0 22 C18 6 62 4 80 22 C98 40 142 42 160 22 C178 6 222 4 240 22 C258 40 302 42 320 22 " +
  "C338 6 382 4 400 22 C418 40 462 42 480 22 C498 6 542 4 560 22 C578 40 622 42 640 22 " +
  "L640 46 L0 46 Z";
const WAVE_MID =
  "M0 24 C20 12 58 10 80 24 C102 38 138 40 160 24 C182 12 218 10 240 24 C262 38 298 40 320 24 " +
  "C342 12 378 10 400 24 C422 38 458 40 480 24 C502 12 538 10 560 24 C582 38 618 40 640 24 " +
  "L640 46 L0 46 Z";
const WAVE_FRONT =
  "M0 26 C24 16 56 8 84 26 C112 44 130 40 152 26 C174 12 206 8 236 26 C266 44 296 40 320 26 " +
  "C344 16 376 8 404 26 C432 44 462 40 484 26 C506 12 538 8 568 26 C592 40 620 42 640 26 " +
  "L640 46 L0 46 Z";
/** 峰顶白沫：几团小泡沫沿着浪峰，不是一条大白带 */
const FOAM =
  "M74 16 C78 10 88 9 92 14 C88 19 78 20 74 16 Z " +
  "M97 19 C101 15 109 15 112 19 C108 22 100 23 97 19 Z " +
  "M122 16 C125 12 132 12 134 16 C131 18 125 19 122 16 Z " +
  "M226 15 C230 9 241 8 245 14 C241 19 230 20 226 15 Z " +
  "M250 19 C254 15 262 14 265 18 C261 21 253 22 250 19 Z " +
  "M276 17 C279 13 285 13 287 17 C284 19 279 20 276 17 Z " +
  "M394 14 C398 8 409 7 413 13 C409 19 398 20 394 14 Z " +
  "M418 19 C422 15 430 14 433 18 C429 21 421 22 418 19 Z " +
  "M444 16 C447 12 454 12 456 16 C453 18 447 19 444 16 Z " +
  "M558 15 C562 9 573 8 577 14 C573 19 562 20 558 15 Z " +
  "M582 19 C586 15 594 14 597 18 C593 21 585 22 582 19 Z " +
  "M608 17 C611 13 617 13 619 17 C616 19 611 20 608 17 Z";

const FISH = [
  { left: 14, bottom: 66, delay: 2.02, w: 50 },
  { left: 37, bottom: 70, delay: 2.2, w: 46 },
  { left: 60, bottom: 64, delay: 2.38, w: 44 },
  { left: 80, bottom: 68, delay: 2.56, w: 40 },
];

const BUBBLES = [
  { left: 20, bottom: 30, delay: 1.9, size: 7, dur: 1.5 },
  { left: 34, bottom: 44, delay: 2.3, size: 5, dur: 1.7 },
  { left: 52, bottom: 34, delay: 2.05, size: 6, dur: 1.4 },
  { left: 68, bottom: 42, delay: 2.5, size: 4.5, dur: 1.8 },
  { left: 79, bottom: 32, delay: 2.2, size: 6.5, dur: 1.6 },
  { left: 88, bottom: 48, delay: 2.7, size: 4, dur: 1.5 },
];

/** 每条鱼落水炸 3 颗水花：左右两根 + 中间一簇，位置贴水面 */
const SPLASH = FISH.flatMap((f) =>
  [-1, 0, 1].map((k, i) => ({
    left: f.left + k * 4 + 2,
    bottom: 88,
    delay: f.delay + 0.66 + i * 0.05,
    size: i === 1 ? 6 : 5,
  })),
);

function Fish({ left, bottom, delay, w, tag }: { left: number; bottom: number; delay: number; w: number; tag: string }) {
  return (
    <span
      className="fx-fish"
      style={{
        left: `${left}%`,
        bottom: `${bottom}%`,
        width: `${w}px`,
        animationDelay: `${delay}s`,
      }}
    >
      <svg viewBox="0 0 48 26" aria-hidden="true">
        <path
          className="fx-fish__tail"
          d="M8.4 13 C5.6 9.2 2.4 6.4 0.4 4.6 C2.8 8.2 3 11 2.6 13 C3 15.2 2.8 17.8 0.4 21.4 C2.4 19.6 5.6 16.8 8.4 13 Z"
          style={{ fill: `url(#${tag}-fin)` }}
        />
        <path
          className="fx-fish__dorsal"
          d="M14.6 4.2 C18 -0.4 24 -0.6 27.8 2.2 C23.6 3 18.6 3.8 14.6 4.2 Z"
          style={{ fill: `url(#${tag}-fin)` }}
        />
        <path
          className="fx-fish__body"
          d="M6.6 13 C9 5.4 17.4 2.2 26 4 C32.6 5.4 36.8 8.6 39.6 12 C37 15.6 32.6 18.8 26 20 C17.4 21.8 9 19.6 6.6 13 Z"
          style={{ fill: `url(#${tag}-body)` }}
        />
        <path
          className="fx-fish__belly"
          d="M9.6 14.2 C14 19.4 22.6 21.2 31 18.8 C33 17.8 34.6 16.6 36 15.2 C32.4 18.6 26.6 20.6 21 20.4 C16 20.2 12 17.8 9.6 14.2 Z"
          style={{ fill: `url(#${tag}-belly)` }}
        />
        <path
          className="fx-fish__gill"
          d="M31.8 6.4 C29.8 9.6 29.8 15 32 18.2 C33.8 13.8 33.8 10.6 31.8 6.4 Z"
          fill="#b4522a"
          fillOpacity="0.22"
        />
        <path
          className="fx-fish__pec"
          d="M22 13.4 C24.6 16.4 25.4 18.6 24.8 20.2 C21.6 19 19.4 16.6 18.6 14 Z"
          style={{ fill: `url(#${tag}-fin)` }}
          opacity="0.9"
        />
        <path
          className="fx-fish__rim"
          d="M10.4 10.4 C14.6 4.6 22.6 2.8 29.4 4.8"
          fill="none"
          stroke="#fff6d8"
          strokeOpacity="0.55"
          strokeWidth="1.1"
          strokeLinecap="round"
        />
        <path
          className="fx-fish__mouth"
          d="M38.6 12.4 C37.4 12.9 36.6 13 35.8 12.8"
          fill="none"
          stroke="#8d3f1e"
          strokeOpacity="0.5"
          strokeWidth="0.9"
          strokeLinecap="round"
        />
        <circle className="fx-fish__eye" cx="33.4" cy="9.8" r="2.1" fill="#3a1f14" />
        <circle className="fx-fish__glint" cx="34.1" cy="9" r="0.8" fill="#fffdf6" />
      </svg>
    </span>
  );
}

function Water() {
  const tag = useFxTag();
  return (
    <span className="fx-water">
      <svg className="fx-defs" aria-hidden="true">
        <defs>
          <linearGradient id={`${tag}-waveBack`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#e8f9ff" />
            <stop offset="55%" stopColor="#b8e6fa" />
            <stop offset="100%" stopColor="#8fd2f0" />
          </linearGradient>
          <linearGradient id={`${tag}-waveMid`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#c9edfd" />
            <stop offset="48%" stopColor="#7fc9ec" />
            <stop offset="100%" stopColor="#4ba3d8" />
          </linearGradient>
          <linearGradient id={`${tag}-waveFront`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#a5ddf6" />
            <stop offset="42%" stopColor="#5aa9dd" />
            <stop offset="100%" stopColor="#3f93cf" />
          </linearGradient>
          <linearGradient id={`${tag}-body`} x1="0" y1="0" x2="0.2" y2="1">
            <stop offset="0%" stopColor="#ffd27a" />
            <stop offset="42%" stopColor="#ffa552" />
            <stop offset="100%" stopColor="#ef6f3c" />
          </linearGradient>
          <linearGradient id={`${tag}-belly`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#ffeec4" />
            <stop offset="100%" stopColor="#ffd98c" />
          </linearGradient>
          <linearGradient id={`${tag}-fin`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#ffc078" />
            <stop offset="100%" stopColor="#ec7f45" />
          </linearGradient>
          <radialGradient id={`${tag}-bubble`} cx="0.36" cy="0.32" r="0.72">
            <stop offset="0%" stopColor="#ffffff" stopOpacity="0.95" />
            <stop offset="46%" stopColor="#e6f7ff" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#9fd8f4" stopOpacity="0.15" />
          </radialGradient>
          <linearGradient id={`${tag}-leak`} x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#d8f2ff" stopOpacity="0.55" />
            <stop offset="42%" stopColor="#ffffff" stopOpacity="0.9" />
            <stop offset="100%" stopColor="#8fd2f0" stopOpacity="0.6" />
          </linearGradient>
        </defs>
      </svg>

      {/* ① 顶部漏水 */}
      <span className="fx-leak">
        <span className="fx-leak__jet" />
        <span className="fx-leak__drop" />
        <span className="fx-leak__splash fx-leak__splash--l" />
        <span className="fx-leak__splash fx-leak__splash--c" />
        <span className="fx-leak__splash fx-leak__splash--r" />
        <span className="fx-leak__ring" />
      </span>

      {/* ②③ 水位 + 水体 */}
      <span className="fx-tide">
        <span className="fx-water__body">
          <span className="fx-wave fx-wave--back">
            <svg viewBox="0 0 640 46" preserveAspectRatio="none" aria-hidden="true">
              <path d={WAVE_BACK} style={{ fill: `url(#${tag}-waveBack)` }} />
            </svg>
            <i className="fx-wave__fill" />
          </span>
          <span className="fx-wave fx-wave--mid">
            <svg viewBox="0 0 640 46" preserveAspectRatio="none" aria-hidden="true">
              <path d={WAVE_MID} style={{ fill: `url(#${tag}-waveMid)` }} />
            </svg>
            <i className="fx-wave__fill" />
          </span>
          <span className="fx-wave fx-wave--front">
            <svg viewBox="0 0 640 46" preserveAspectRatio="none" aria-hidden="true">
              <path d={WAVE_FRONT} style={{ fill: `url(#${tag}-waveFront)` }} />
            </svg>
            <i className="fx-wave__fill" />
            <svg className="fx-wave__foam" viewBox="0 0 640 46" preserveAspectRatio="none" aria-hidden="true">
              <path d={FOAM} fill="#ffffff" fillOpacity="0.85" />
            </svg>
          </span>

          {/* 越深越饱和 + 水面亮带 + 水下焦散光 */}
          <span className="fx-water__deep" />
          <span className="fx-water__surface" />
          <span className="fx-water__shaft fx-water__shaft--a" />
          <span className="fx-water__shaft fx-water__shaft--b" />
          <span className="fx-water__caustic fx-water__caustic--a" />
          <span className="fx-water__caustic fx-water__caustic--b" />
          <span className="fx-water__caustic fx-water__caustic--c" />
          <span className="fx-water__shimmer" />

          {FISH.map((f) => (
            <Fish key={f.left} {...f} tag={tag} />
          ))}
          {BUBBLES.map((b) => (
            <span
              key={`bub${b.left}`}
              className="fx-bubble"
              style={{
                left: `${b.left}%`,
                bottom: `${b.bottom}%`,
                width: `${b.size}px`,
                height: `${b.size}px`,
                animationDelay: `${b.delay}s`,
                animationDuration: `${b.dur}s`,
                background: `radial-gradient(circle at 36% 32%, #fff 0%, rgba(230,247,255,.45) 46%, rgba(159,216,244,.15) 100%)`,
              }}
            />
          ))}
          {SPLASH.map((s, i) => (
            <span
              key={`sp${i}`}
              className={`fx-splash fx-splash--${i % 3 === 0 ? "l" : i % 3 === 1 ? "c" : "r"}`}
              style={{
                left: `${s.left}%`,
                bottom: `${s.bottom}%`,
                width: `${s.size}px`,
                height: `${s.size}px`,
                animationDelay: `${s.delay}s`,
              }}
            />
          ))}
          <span className="fx-foamline" />
        </span>
      </span>

      {/* ⑤ 退潮留下的湿痕 */}
      <span className="fx-wet" />
    </span>
  );
}

/* --------------------------------------------------------------- 出口 */

/** 卡片内层：绝对定位，不挡点击 */
export function HeroOverlay({
  effect,
  time,
  title,
}: {
  effect: HeroEffect | null;
  time: string;
  title: string;
}) {
  const body = useMemo(() => {
    if (effect === "vine") return <Vine />;
    if (effect === "orbit") return <Orbit time={time} title={title} />;
    if (effect === "water") return <Water />;
    return null;
  }, [effect, time, title]);

  if (!body) return null;
  return (
    <span className={`fx-layer fx-layer--${effect}`} aria-hidden="true">
      {body}
    </span>
  );
}
