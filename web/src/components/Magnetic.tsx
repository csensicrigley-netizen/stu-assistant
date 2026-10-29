/**
 * 引力吸附：指针靠近时卡片/按钮朝指针轻轻偏一点，离开时弹回原位。
 *
 * 只给**精确指针**（鼠标 / 触控板）用：手指按住时那种"跟着走"会和滚动手势
 * 打架，触屏上直接关掉，按下的反馈交给 CSS 的 :active 缩放。
 *
 * 为什么不用 JS 每帧算位置：这里的输入是 pointermove（只在真的有指针在动时发生），
 * 位移写进 motion value，由 spring 逐帧插值 —— React 一次都不重渲染。
 * 也**没有** scroll 监听：滚动位置不参与任何计算，滚动时这一层只是被浏览器整体挪走。
 */
import { useCallback, useEffect, useRef, useState } from "react";
import { motion, useMotionValue, useReducedMotion, useSpring } from "motion/react";
import type { ReactNode } from "react";
import { JELLY, MAGNET_PULL, MAGNET_RANGE } from "../lib/motion";

interface Props {
  children: ReactNode;
  className?: string;
  /** 吸附强度倍数，1 = 默认 7px */
  pull?: number;
  as?: "div" | "section" | "li" | "span";
}

function finePointer(): boolean {
  try {
    return window.matchMedia("(hover: hover) and (pointer: fine)").matches;
  } catch {
    return false;
  }
}

export function Magnetic({ children, className, pull = 1, as = "div" }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const reduce = useReducedMotion();
  const [fine, setFine] = useState(false);
  const rawX = useMotionValue(0);
  const rawY = useMotionValue(0);
  const x = useSpring(rawX, JELLY);
  const y = useSpring(rawY, JELLY);

  // 能力探测放在 effect 里：服务端/首帧不该去读 matchMedia
  useEffect(() => {
    const sync = () => setFine(finePointer());
    sync();
    let mq: MediaQueryList | null = null;
    try {
      mq = window.matchMedia("(hover: hover) and (pointer: fine)");
      mq.addEventListener("change", sync);
    } catch {
      /* 老 WebView 没有 MediaQueryList 事件 */
    }
    return () => mq?.removeEventListener("change", sync);
  }, []);

  const active = fine && !reduce;

  const onMove = useCallback(
    (event: React.PointerEvent) => {
      if (!active || event.pointerType === "touch") return;
      const node = ref.current;
      if (!node) return;
      const rect = node.getBoundingClientRect();
      const cx = rect.left + rect.width / 2;
      const cy = rect.top + rect.height / 2;
      const dx = event.clientX - cx;
      const dy = event.clientY - cy;
      const dist = Math.hypot(dx, dy);
      if (dist > rect.width / 2 + MAGNET_RANGE) {
        rawX.set(0);
        rawY.set(0);
        return;
      }
      const strength = (1 - Math.min(1, dist / (rect.width / 2 + MAGNET_RANGE))) * MAGNET_PULL * pull;
      const angle = Math.atan2(dy, dx);
      rawX.set(Math.cos(angle) * strength);
      rawY.set(Math.sin(angle) * strength);
    },
    [active, pull, rawX, rawY],
  );

  const reset = useCallback(() => {
    rawX.set(0);
    rawY.set(0);
  }, [rawX, rawY]);

  const Component = motion[as] as typeof motion.div;

  return (
    <Component
      ref={ref}
      className={className}
      style={active ? { x, y, willChange: "transform" } : undefined}
      onPointerMove={onMove}
      onPointerLeave={reset}
      onPointerCancel={reset}
    >
      {children}
    </Component>
  );
}
