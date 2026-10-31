import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties, PointerEvent as ReactPointerEvent } from "react";
import { motion, useMotionValue, useReducedMotion, useSpring, useTransform } from "motion/react";
import { Icon, type IconName } from "./Icon";

/**
 * 卡片阵列（2026-10-31 四版：照参考图重做）
 *
 * 参考图（"一张浮起来，两边往外让"）真正的关键有四点，之前几版都只做了部分：
 *
 *   1. **卡片是竖向的长方形**，不是正方块。参考图里卡是 3:4 的竖卡，
 *      所以六张并排会自然形成"一叠牌摊开"的横带，而不是"六块砖"。
 *   2. **深底**。卡片颜色再亮，也需要一个暗背景才立得起来。
 *   3. **每张卡是一块完整的彩色软胶**，不是"浅色卡 + 深色字"。
 *      参考图里卡就是那个颜色本身（粉、青、绿、蓝），文字直接压在色块上。
 *   4. **悬停那张抬起放大，邻卡左右让开**，幅度要够（约 18px / 7%）。
 *
 * 手机端：横向滑动，一张立正、两侧探出并轻微倾斜。
 * 桌面端：六张沿浅弧叠开，悬停立正。
 */

export interface FeatureCard {
  id: string;
  /** 卡片色相（OKLCH hue）。参考图是饱和色块，这里取 L 0.74 / C 0.13 的柔亮档 */
  hue: number;
  icon: IconName;
  title: string;
  /** 一句话说明 */
  note: string;
  /** 右上角小标签 */
  tag?: string;
}

interface Props {
  cards: FeatureCard[];
  active: number | null;
  onActive: (i: number | null) => void;
  className?: string;
  /** 自动巡游间隔（ms），0 关闭 */
  tourMs?: number;
}

const SPRING = { stiffness: 260, damping: 26, mass: 0.9 };

function useDesktop(query = "(min-width: 960px)") {
  const [on, setOn] = useState(() => (typeof window === "undefined" ? false : window.matchMedia(query).matches));
  useEffect(() => {
    const mq = window.matchMedia(query);
    const h = () => setOn(mq.matches);
    mq.addEventListener("change", h);
    return () => mq.removeEventListener("change", h);
  }, [query]);
  return on;
}

export function CardFan({ cards, active, onActive, className, tourMs = 3600 }: Props) {
  const wrap = useRef<HTMLDivElement>(null);
  const isDesktop = useDesktop();
  const reduced = useReducedMotion();

  /* 视觉上的"当前卡"：active 为 null 时默认第一张立正 */
  const shown = active ?? 0;

  const px = useMotionValue(0);
  const py = useMotionValue(0);
  const sx = useSpring(px, SPRING);
  const sy = useSpring(py, SPRING);
  const rotateY = useTransform(sx, [-1, 1], [5, -5]);
  const rotateX = useTransform(sy, [-1, 1], [-4, 4]);

  const activeRef = useRef<number | null>(active);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  const takenOver = useRef(false);
  const tourRef = useRef<number | null>(null);
  const [tourOn, setTourOn] = useState(false);

  const stopTour = useCallback(() => {
    if (tourRef.current != null) {
      window.clearInterval(tourRef.current);
      tourRef.current = null;
    }
    setTourOn(false);
  }, []);

  useEffect(() => {
    if (reduced || !tourMs || cards.length < 2 || takenOver.current) return;
    const start = () => {
      if (tourRef.current != null) return;
      tourRef.current = window.setInterval(() => {
        if (takenOver.current) return;
        const next = ((activeRef.current ?? -1) + 1) % cards.length;
        onActive(next);
        if (!isDesktop) {
          const el = wrap.current?.querySelectorAll(".fan__card")[next] as HTMLElement | undefined;
          el?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
        }
      }, tourMs);
      setTourOn(true);
    };
    start();
    const onVis = () => (document.hidden ? stopTour() : start());
    document.addEventListener("visibilitychange", onVis);
    return () => {
      document.removeEventListener("visibilitychange", onVis);
      stopTour();
    };
  }, [reduced, tourMs, cards.length, onActive, stopTour, isDesktop]);

  const takeOver = useCallback(() => {
    takenOver.current = true;
    stopTour();
  }, [stopTour]);

  /* 横滑同步：滚到哪张，哪张立正 */
  useEffect(() => {
    const el = wrap.current;
    if (!el || isDesktop) return;
    let raf = 0;
    const settle = () => {
      const box = el.getBoundingClientRect();
      const center = box.left + box.width / 2;
      let best = 0;
      let bestD = Infinity;
      el.querySelectorAll(".fan__card").forEach((c, i) => {
        const r = c.getBoundingClientRect();
        const d = Math.abs(r.left + r.width / 2 - center);
        if (d < bestD) { bestD = d; best = i; }
      });
      if (best !== activeRef.current) onActive(best);
    };
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(settle);
    };
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      el.removeEventListener("scroll", onScroll);
      cancelAnimationFrame(raf);
    };
  }, [isDesktop, onActive]);

  const onMove = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      const el = wrap.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      px.set(((e.clientX - r.left) / r.width) * 2 - 1);
      py.set(((e.clientY - r.top) / r.height) * 2 - 1);
    },
    [px, py],
  );

  const reset = useCallback(() => {
    px.set(0);
    py.set(0);
    if (!takenOver.current) onActive(activeRef.current);
  }, [px, py, onActive]);

  return (
    <motion.div
      ref={wrap}
      className={`fan${tourOn ? " is-touring" : ""} ${className ?? ""}`}
      onPointerMove={onMove}
      onPointerEnter={takeOver}
      onPointerLeave={reset}
      onTouchStart={takeOver}
      style={{ rotateX, rotateY, transformPerspective: 1100 }}
    >
      {cards.map((c, i) => {
        const isActive = shown === i;
        const n = cards.length;
        const mid = (n - 1) / 2;
        const d = i - mid;

        /* 桌面：摊开不重叠。卡宽 178 + 间距 10 = 每张右错 188 */
        const stepX = 188;
        const arcY = Math.abs(d) ** 2 * 1.4;
        const arcRot = d * 1.4;
        const desktop = isDesktop
          ? { left: (d + mid) * stepX, top: arcY, rotate: arcRot, zIndex: 10 - Math.abs(Math.round(d)) }
          : { left: 0, top: 0, rotate: 0, zIndex: 1 };

        /* 让位：激活卡左边往左推、右边往右推，隔一张衰减 */
        const yieldPx = isDesktop ? 20 : 0;
        const offset = i < shown ? -1 : i > shown ? 1 : 0;
        const steps = Math.abs(i - shown);
        const shift = offset * Math.max(0, 1 - (steps - 1) * 0.35) * yieldPx;

        return (
          <motion.button
            key={c.id}
            type="button"
            className={`fan__card${isActive ? " is-active" : ""}`}
            style={{ "--h": c.hue, "--i": i } as CSSProperties}
            onPointerEnter={() => onActive(i)}
            onFocus={() => {
              takeOver();
              onActive(i);
            }}
            onBlur={() => onActive(null)}
            animate={{
              left: desktop.left,
              top: desktop.top,
              y: isActive ? -18 : 0,
              x: shift,
              scale: isActive ? 1.07 : 1,
              rotate: isActive ? 0 : desktop.rotate,
              zIndex: isActive ? 30 : desktop.zIndex,
            }}
            transition={{ type: "spring", stiffness: 300, damping: 24, mass: 0.85 }}
            whileTap={{ scale: isActive ? 1.03 : 0.985 }}
          >
            <span className="fan__float" aria-hidden="true">
              <span className="fan__glaze" />
              <span className="fan__sheen" />
              <span className="fan__shine" />
            </span>
            {/* 参考图的排版：顶部一行小字标签 → 中间一大块图形 → 底部标题与说明。
                之前是"图标 + 标题 + 说明"挤在上半部，下半张全空，所以看着像空白卡片。 */}
            <span className="fan__top">
              {c.tag && <span className="fan__tag">{c.tag}</span>}
              <span className="fan__badge" aria-hidden="true">
                <Icon name={c.icon} size={22} />
              </span>
            </span>
            <span className="fan__art" aria-hidden="true">
              <span className="fan__art-ring" />
              <Icon name={c.icon} size={62} />
            </span>
            <span className="fan__foot">
              <span className="fan__title">{c.title}</span>
              <span className="fan__note">{c.note}</span>
            </span>
          </motion.button>
        );
      })}
    </motion.div>
  );
}

/**
 * 背景光晕：**只在深底上放很淡的一层**。
 * 之前几版把光晕做得太大太紫，整页像夜店；参考图的背景其实接近纯黑，
 * 光晕只是让边缘不"死"。这里刻意压到几乎看不见。
 */
export function ParallaxAura() {
  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const sx = useSpring(mx, { stiffness: 60, damping: 20, mass: 1.2 });
  const sy = useSpring(my, { stiffness: 60, damping: 20, mass: 1.2 });
  const aX = useTransform(sx, (v) => v * 18);
  const aY = useTransform(sy, (v) => v * 12);
  const bX = useTransform(sx, (v) => v * -26);
  const bY = useTransform(sy, (v) => v * -18);

  useEffect(() => {
    if (!window.matchMedia("(hover: hover) and (pointer: fine)").matches) return;
    const on = (e: PointerEvent) => {
      mx.set((e.clientX / window.innerWidth) * 2 - 1);
      my.set((e.clientY / window.innerHeight) * 2 - 1);
    };
    window.addEventListener("pointermove", on, { passive: true });
    return () => window.removeEventListener("pointermove", on);
  }, [mx, my]);

  return (
    <div className="aura" aria-hidden="true">
      <span className="aura__wrap aura__wrap--a">
        <motion.span className="aura__blob aura__blob--a" style={{ x: aX, y: aY }} />
      </span>
      <span className="aura__wrap aura__wrap--b">
        <motion.span className="aura__blob aura__blob--b" style={{ x: bX, y: bY }} />
      </span>
    </div>
  );
}

/** 「现在几点」一行，欢迎页顶部 */
export function useClockLine() {
  const [line, setLine] = useState("");
  useEffect(() => {
    const tick = () => {
      const d = new Date();
      const h = d.getHours();
      const m = d.getMinutes();
      const part = h < 6 ? "凌晨" : h < 11 ? "上午" : h < 13 ? "中午" : h < 18 ? "下午" : "晚上";
      setLine(`${part} ${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`);
    };
    tick();
    const t = window.setInterval(tick, 20_000);
    return () => window.clearInterval(t);
  }, []);
  return line;
}
