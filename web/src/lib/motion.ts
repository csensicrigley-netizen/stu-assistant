/**
 * 统一的动效手感：果冻流体 + 引力吸附 + 编排。
 *
 * 果冻 = 低阻尼 + 轻微过冲，但**收得快**。过冲太多就成了"弹簧球"，看着廉价；
 * 过冲太少又硬。下面这套值是照 120Hz 手感调的，改之前先在真机上过一遍。
 *
 * 分层：
 *   GLIDE —— 无过冲，长距离位移（页面切换、大块内容进出）
 *   SPRING —— 收得干脆（按钮、开关、卡片进出）
 *   JELLY  —— 阻尼低、有回弹（按下、吸附、指示器滑动）
 *   SETTLE —— 慢一点、几乎不过冲（列表、大块内容落位）
 *
 * 编排（这一版新增的核心）：
 *   进场不是"每块各自弹"，而是**有先后**：先容器、再标题、再主体、最后边栏；
 *   同一层的列表项按 32ms 递增。退场永远比进场快（120ms），因为退场没人看。
 *   所有延迟都从 `stagger()` 出，页面上不许再手写 `delay: i * 0.05`。
 *
 * 性能纪律：只动 transform / opacity。**不要**在这里加 width/height/box-shadow
 * 这类会触发 layout 或 paint 的动画 —— 那正是"一卡一卡"的来源。
 */
import type { Transition } from "motion/react";

/* ---------------------------------------------------------------- 弹簧档位 */

/** 收得干脆：交互反馈的主力档 */
export const SPRING: Transition = { type: "spring", stiffness: 420, damping: 26, mass: 0.9 };

/** 经典果冻：低阻尼、有可见的回弹 */
export const JELLY: Transition = { type: "spring", stiffness: 300, damping: 16, mass: 0.85 };

/** 更软一档：大标题、整页进出，回弹只留一点点 */
export const JELLY_SOFT: Transition = { type: "spring", stiffness: 240, damping: 20, mass: 0.9 };

/** 最软：长列表整体落位，肉眼几乎看不出过冲 */
export const SETTLE: Transition = { type: "spring", stiffness: 180, damping: 24, mass: 1 };

/** 位移专用：不过冲，像被磁力慢慢吸住 */
export const GLIDE: Transition = { type: "spring", stiffness: 260, damping: 30, mass: 1 };

/** 按下 / 松开：按下去快，弹回来慢一点，就是果冻的手感来源 */
export const PRESS = { scale: 0.965 };
export const TAP: Transition = { type: "spring", stiffness: 320, damping: 18, mass: 0.7 };

/** 页面切换：进得利落（带一点方向惯性），退得极快（退场没人看，快才不卡） */
export const PAGE_IN: Transition = { type: "spring", stiffness: 380, damping: 32, mass: 0.85 };
export const PAGE_OUT: Transition = { duration: 0.1, ease: [0.4, 0, 1, 1] };

/** 指示器（tab 药丸 / 分段控件 / 步骤条）滑动的唯一档位 */
export const PILL: Transition = { type: "spring", stiffness: 380, damping: 17, mass: 0.8 };

/** 底部弹层：整块面板从下方吸上来，收得快、只留一点点过冲 */
export const SHEET: Transition = { type: "spring", stiffness: 360, damping: 30, mass: 0.9 };

/** 步骤切换（导入四步、分段内容切换）：比页面轻，比列表重 */
export const STEP_IN: Transition = { type: "spring", stiffness: 320, damping: 30, mass: 0.9 };

/** 退场：列表项、临时提示统一用这一条 */
export const OUT: Transition = { duration: 0.14, ease: [0.4, 0, 1, 1] };

/* ------------------------------------------------------------ 引力吸附参数 */

/** 引力吸附的最大位移（px）：指针离得越近吸得越多 */
export const MAGNET_RANGE = 90;
export const MAGNET_PULL = 7;

/* -------------------------------------------------------------- 编排节奏 */

/**
 * 同层递增的步长。20–30ms 是"看得出先后、又不像排队"的区间。
 * 原来 32ms、最多排到第 10 项 = 最后一项要等 320ms，手机上一眼就觉得"在等我"，
 * 所以压到 26ms / 8 项：最长 208ms，最后一格落下时第一格还在余韵里，是连续的。
 */
export const STEP = 0.026;

/** 同一层最多排到第几项（再往后的项共用最后一个延迟，别让长列表爬） */
export const STAGGER_MAX = 8;

/** 页面容器自己先动，子块稍后跟：子块的延迟从这条基线起算 */
export const PAGE_LEAD = 0.045;

/** 第 i 项该等多久。列表里超过 STAGGER_MAX 的项不再继续延后。 */
export function stagger(i = 0): number {
  return Math.min(Math.max(i, 0), STAGGER_MAX) * STEP;
}

/** 编排档位名，给"这一层是第几层"用；数值就是层级本身。 */
export const LAYER = { head: 0, main: 1, side: 2, tail: 3 } as const;

/* ------------------------------------------------------------ 常用进/出场 */

export interface RiseOptions {
  /** 起始位移，默认 10px；标题层可以给 6，整块可以给 14 */
  y?: number;
  /** 起始缩放，默认 0.99；列表项给 0.985 更"被放下" */
  scale?: number;
  /** 是否从页面基线之后起算（默认 true）。页容器自身用 false */
  lead?: boolean;
  /** 覆盖延迟（极少用：用户直接操作的那一项要优先响应，直接给 0） */
  delay?: number;
}

/**
 * 卡片 / 列表项 / 板块落位：轻微下坠 + 极小的缩放，像被重力轻轻放下。
 * 旧的 `rise(i)` 只有 `i * 0.025` 且各自散落；现在统一走 stagger()。
 */
export function rise(i = 0, opts: RiseOptions = {}) {
  const delay = opts.delay ?? (opts.lead === false ? 0 : PAGE_LEAD + stagger(i));
  return {
    initial: { opacity: 0, y: opts.y ?? 10, scale: opts.scale ?? 0.99 },
    animate: { opacity: 1, y: 0, scale: 1 },
    exit: { opacity: 0, y: -6, transition: OUT },
    transition: { ...JELLY_SOFT, delay },
  };
}

/**
 * 用户直接操作的那一项（按下的按钮、拖动的行）优先响应：
 * 不排队、不延迟，但保留果冻回弹。
 */
export function immediate() {
  return {
    initial: { opacity: 0, y: 6, scale: 0.99 },
    animate: { opacity: 1, y: 0, scale: 1 },
    transition: { ...SPRING, delay: 0 },
  };
}

/* -------------------------------------------------------------- 页面切换 */

/**
 * 有方向感的切页：从哪边来回哪边去。
 *   dir = 1  往右走（下标变大）→ 新页从右进、旧页往左退
 *   dir = -1 往左走           → 新页从左进、旧页往右退
 * 位移只有 20/14px：手机上"方向"是暗示，不是整屏推。
 */
export const pageVariants = {
  enter: (dir: number) => ({ opacity: 0, x: dir * 20, scale: 0.994 }),
  center: { opacity: 1, x: 0, scale: 1, transition: PAGE_IN },
  exit: (dir: number) => ({ opacity: 0, x: dir * -14, scale: 0.996, transition: PAGE_OUT }),
};

/** 兼容旧调用点（App.tsx 里的 120ms 淡出 + 弹簧落位） */
export const pageMotion = {
  initial: { opacity: 0, y: 8, scale: 0.99, transition: PAGE_IN },
  animate: { opacity: 1, y: 0, scale: 1, transition: PAGE_IN },
  exit: { opacity: 0, y: -4, scale: 0.995, transition: PAGE_OUT },
};
