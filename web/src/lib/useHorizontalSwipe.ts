import { useRef } from "react";
import { haptic } from "./haptics";

interface HorizontalSwipeOptions {
  canSwipePrevious?: boolean;
  canSwipeNext?: boolean;
  onSwipePrevious: () => void;
  onSwipeNext: () => void;
}

/**
 * Ergonomic touch/pointer horizontal swipe handler for mobile browsers & PWAs.
 * Ignores gestures originating inside horizontal scrollers, inputs, or range sliders,
 * and angle-locks strictly (`|dx| > |dy| * 1.4`) so vertical page scroll stays effortless.
 */
export function useHorizontalSwipe({
  canSwipePrevious = true,
  canSwipeNext = true,
  onSwipePrevious,
  onSwipeNext,
}: HorizontalSwipeOptions) {
  const track = useRef<{
    id: number;
    startX: number;
    startY: number;
    startTime: number;
    locked: boolean;
    rejected: boolean;
  } | null>(null);

  return {
    onPointerDown: (e: React.PointerEvent<HTMLElement>) => {
      if (e.pointerType === "mouse" && e.button !== 0) return;
      const target = e.target as HTMLElement | null;
      if (
        target?.closest(
          'input, textarea, select, [role="slider"], .chips--scroll, [data-no-swipe="true"]',
        )
      ) {
        return;
      }
      track.current = {
        id: e.pointerId,
        startX: e.clientX,
        startY: e.clientY,
        startTime: performance.now(),
        locked: false,
        rejected: false,
      };
    },
    onPointerMove: (e: React.PointerEvent<HTMLElement>) => {
      const state = track.current;
      if (!state || state.id !== e.pointerId || state.rejected) return;
      const dx = e.clientX - state.startX;
      const dy = e.clientY - state.startY;
      if (!state.locked) {
        if (Math.abs(dy) > 10 && Math.abs(dy) >= Math.abs(dx) * 0.85) {
          state.rejected = true;
          return;
        }
        if (Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy) * 1.4) {
          state.locked = true;
        }
      }
    },
    onPointerUp: (e: React.PointerEvent<HTMLElement>) => {
      const state = track.current;
      track.current = null;
      if (!state || state.id !== e.pointerId || state.rejected || !state.locked) return;
      const dx = e.clientX - state.startX;
      const dt = Math.max(1, performance.now() - state.startTime);
      const vx = (dx / dt) * 1000; // px/s
      if (canSwipePrevious && (dx > 54 || (dx > 22 && vx > 460))) {
        haptic("selection");
        onSwipePrevious();
      } else if (canSwipeNext && (dx < -54 || (dx < -22 && vx < -460))) {
        haptic("selection");
        onSwipeNext();
      }
    },
    onPointerCancel: () => {
      track.current = null;
    },
  };
}
