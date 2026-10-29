/**
 * 统一的动效手感：果冻流体 + 引力吸附。
 *
 * 果冻 = 低阻尼 + 轻微过冲，但**收得快**。过冲太多就成了"弹簧球"，看着廉价；
 * 过冲太少又硬。下面这套值是照 120Hz 手感调的，改之前先在真机上过一遍。
 *
 * 分层：
 *   GLIDE —— 无过冲，长距离位移（页面切换、大块内容进出）
 *   SPRING —— 收得干脆（按钮、开关、卡片进出）
 *   JELLY  —— 阻尼低、有回弹（按下、吸附、指示器滑动）
 *   SETTLE —— 慢一点、几乎不过冲（列表、大块内容）
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

/** 页面切换：进得利落，退得极快（退场没人看，快才不卡） */
export const PAGE_IN: Transition = { type: "spring", stiffness: 300, damping: 24, mass: 0.9 };
export const PAGE_OUT: Transition = { duration: 0.12, ease: [0.4, 0, 1, 1] };

/** 指示器（tab 药丸 / 分段控件）滑动 */
export const PILL: Transition = { type: "spring", stiffness: 380, damping: 17, mass: 0.8 };

/** 底部弹层：整块面板从下方吸上来，收得快、只留一点点过冲 */
export const SHEET: Transition = { type: "spring", stiffness: 360, damping: 30, mass: 0.9 };

/* ------------------------------------------------------------ 引力吸附参数 */

/** 引力吸附的最大位移（px）：指针离得越近吸得越多 */
export const MAGNET_RANGE = 90;
export const MAGNET_PULL = 7;

/* ------------------------------------------------------------ 常用进/出场 */

/** 卡片 / 列表项落位：轻微下坠 + 极小的缩放，像被重力轻轻放下 */
export const rise = (i = 0) => ({
  initial: { opacity: 0, y: 10, scale: 0.985 },
  animate: { opacity: 1, y: 0, scale: 1 },
  transition: { ...JELLY_SOFT, delay: Math.min(i * 0.025, 0.12) },
});

/** 仅用于「进」，不参与布局：避免 layout 动画把列表整块重排 */
export const pageMotion = {
  initial: { opacity: 0, y: 8, scale: 0.99, transition: PAGE_IN },
  animate: { opacity: 1, y: 0, scale: 1, transition: PAGE_IN },
  exit: { opacity: 0, y: -4, scale: 0.995, transition: PAGE_OUT },
};
