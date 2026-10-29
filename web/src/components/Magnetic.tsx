/**
 * 引力吸附：指针靠近时卡片/按钮朝指针轻轻偏一点，离开时弹回原位。
 *
 * 只在有精确指针的设备（鼠标/触控板）上吸附；手机上用手指按住时给一点"跟着走"的
 * 位移，抬手用弹簧回中，手感偏果冻。全部走 transform，不触发重排。
 */
import { useCallback, useRef } from "react";
import { motion, useMotionValue, useSpring } from "motion/react";
import type { ReactNode } from "react";
import { JELLY, MAGNET_PULL, MAGNET_RANGE } from "../lib/motion";

interface Props {
  children: ReactNode;
  className?: string;
  /** 吸附强度倍数，1 = 默认 7px */
  pull?: number;
  as?: "div" | "section" | "li";
}

export function Magnetic({ children, className, pull = 1, as = "div" }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const rawX = useMotionValue(0);
  const rawY = useMotionValue(0);
  const x = useSpring(rawX, JELLY);
  const y = useSpring(rawY, JELLY);

  const onMove = useCallback(
    (event: React.PointerEvent) => {
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
    [pull, rawX, rawY],
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
      style={{ x, y }}
      onPointerMove={onMove}
      onPointerLeave={reset}
      onPointerCancel={reset}
    >
      {children}
    </Component>
  );
}
