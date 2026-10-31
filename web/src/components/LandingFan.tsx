import { useCallback, useEffect, useRef, useState } from "react";
import type { CSSProperties, PointerEvent as ReactPointerEvent } from "react";
import { motion, useMotionValue, useReducedMotion, useSpring, useTransform } from "motion/react";
import { Toy, type ToyKind } from "./Toy";

/**
 * 落地页卡组（2026-10-31 · 七版，照参考图真实结构）
 *
 * ── 参考图到底是什么 ──────────────────────────────────────────────
 * 之前几版都理解错了。看清原图后是这四条：
 *
 *   1. **大幅叠压**：六张竖卡横向排开，每张压住前一张约 40%，
 *      像一叠扑克摊开，不是并排也不是整齐网格。
 *   2. **中间两张更大、更靠前、抬得更高**：它们是视觉中心，
 *      比两侧的卡高出一截、宽出一圈，还各自带一个白色药丸按钮。
 *   3. **卡里是一大坨立体软胶玩具**（云/机器人/星球/太阳花/心），
 *      有眼睛、有高光、有地面投影——这是主角，不是图标。
 *   4. **卡名在左上角**，小字说明在下面一行；卡本身是整块饱和色。
 *
 * ── 实现要点 ──────────────────────────────────────────────────────
 *   · 桌面：绝对定位 + 每张给 left/rotate/scale，中间的 z 最高
 *   · 手机：横向滑动，一张立正、右侧探出下一张
 *   · 悬停那张抬起放大，邻卡让开；没人操作时自动巡游
 *   · 全部位移走 transform；阴影与高光是静态的，不参与动画
 */

export interface LdCard {
  id: string;
  /** 卡面颜色（取自参考图） */
  color: string;
  /** 卡里那坨软胶玩具 */
  toy: ToyKind;
  title: string;
  subtitle: string;
  /** 是否带"开始使用"药丸（参考图里中间两张有） */
  cta?: boolean;
}

interface Props {
  cards: LdCard[];
  active: number | null;
  onActive: (i: number | null) => void;
  tourMs?: number;
}

const SPRING = { stiffness: 280, damping: 28, mass: 0.85 };

/* 打散"整齐感"的固定错落（不是随机：刷新不会跳，读屏与视觉都稳定）。
   竖直 ±9px、水平 ±6px、角度 ±1.6°——够看出"散落"，又不至于乱。 */
const JITTER_Y = [4, -7, 9, -5, 6, -8];
const JITTER_X = [-5, 4, -3, 6, -6, 3];
const JITTER_R = [-1.4, 1.1, -0.7, 1.5, -1.2, 0.9];

/* 每张卡的浮动周期都不同：同周期一起动会像"整体在抖"，错开才像各自漂着 */
const FLOAT_DUR = ["7.3s", "8.6s", "6.9s", "9.2s", "7.8s", "8.1s"];

function useWide(query = "(min-width: 900px)") {
  const [on, setOn] = useState(() => (typeof window === "undefined" ? false : window.matchMedia(query).matches));
  useEffect(() => {
    const mq = window.matchMedia(query);
    const h = () => setOn(mq.matches);
    mq.addEventListener("change", h);
    return () => mq.removeEventListener("change", h);
  }, [query]);
  return on;
}

export function LdFan({ cards, active, onActive, tourMs = 4000 }: Props) {
  const wrap = useRef<HTMLDivElement>(null);
  const wide = useWide();
  const reduced = useReducedMotion();
  const shown = active ?? 0;

  const mx = useMotionValue(0);
  const my = useMotionValue(0);
  const sx = useSpring(mx, SPRING);
  const sy = useSpring(my, SPRING);
  const tiltY = useTransform(sx, [-1, 1], [3, -3]);
  const tiltX = useTransform(sy, [-1, 1], [-2.4, 2.4]);

  const activeRef = useRef<number | null>(active);
  useEffect(() => {
    activeRef.current = active;
  }, [active]);

  const takenOver = useRef(false);
  const timer = useRef<number | null>(null);
  const [touring, setTouring] = useState(false);
  /* 被点中、抬到最上层的那张。再点一次放回去。 */
  const [pinned, setPinned] = useState<number | null>(null);

  const stop = useCallback(() => {
    if (timer.current != null) {
      window.clearInterval(timer.current);
      timer.current = null;
    }
    setTouring(false);
  }, []);

  useEffect(() => {
    if (reduced || !tourMs || cards.length < 2 || takenOver.current) return;
    const start = () => {
      if (timer.current != null) return;
      timer.current = window.setInterval(() => {
        if (takenOver.current) return;
        const next = ((activeRef.current ?? -1) + 1) % cards.length;
        onActive(next);
        if (!wide) {
          const el = wrap.current?.querySelectorAll(".ld-card")[next] as HTMLElement | undefined;
          el?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
        }
      }, tourMs);
      setTouring(true);
    };
    start();
    const onVis = () => (document.hidden ? stop() : start());
    document.addEventListener("visibilitychange", onVis);
    return () => {
      document.removeEventListener("visibilitychange", onVis);
      stop();
    };
  }, [reduced, tourMs, cards.length, onActive, stop, wide]);

  const takeOver = useCallback(() => {
    takenOver.current = true;
    stop();
  }, [stop]);

  useEffect(() => {
    const el = wrap.current;
    if (!el || wide) return;
    let raf = 0;
    const settle = () => {
      const box = el.getBoundingClientRect();
      const center = box.left + box.width / 2;
      let best = 0;
      let bestD = Infinity;
      el.querySelectorAll(".ld-card").forEach((c, i) => {
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
  }, [wide, onActive]);

  const onMove = useCallback(
    (e: ReactPointerEvent<HTMLDivElement>) => {
      const el = wrap.current;
      if (!el) return;
      const r = el.getBoundingClientRect();
      mx.set(((e.clientX - r.left) / r.width) * 2 - 1);
      my.set(((e.clientY - r.top) / r.height) * 2 - 1);
    },
    [mx, my],
  );

  const reset = useCallback(() => {
    mx.set(0);
    my.set(0);
    if (!takenOver.current) onActive(activeRef.current);
  }, [mx, my, onActive]);

  return (
    <motion.div
      ref={wrap}
      className={`ld-fan${touring ? " is-touring" : ""}${pinned != null ? " has-pin" : ""}`}
      onPointerMove={onMove}
      onPointerEnter={takeOver}
      onPointerLeave={reset}
      onTouchStart={takeOver}
      style={{ rotateX: tiltX, rotateY: tiltY, transformPerspective: 1400 }}
    >
      {cards.map((c, i) => {
        const isActive = shown === i;
        const isPinned = pinned === i;
        const n = cards.length;
        const mid = (n - 1) / 2;
        const d = i - mid;

        /* ── 桌面：大幅叠压 + 中间抬升 ──
           每张往右错 112px，压住 43%，只露左条。
           中间的卡更高更大，形成"两边低中间高"的弧。 */
        const stepX = 112;
        const centerBoost = Math.max(0, 1 - Math.abs(d) / 2.6);

        /* 打散"整齐感"：每张给一点固定的错落（不是随机，刷新也不跳）。
           整齐排列看着像表格，错开一点才像"散落在桌上的一叠卡"。 */
        const jY = wide ? JITTER_Y[i % JITTER_Y.length] : 0;
        const jX = wide ? JITTER_X[i % JITTER_X.length] : 0;
        const jR = wide ? JITTER_R[i % JITTER_R.length] : 0;

        const arcY = (wide ? -52 * centerBoost : 0) + jY;
        const scaleBase = wide ? 0.94 + centerBoost * 0.16 : 1;
        const rotBase = (wide ? d * 3.6 : 0) + jR;

        /* 让位：激活时邻卡分开 */
        const yld = wide ? 22 : 0;
        const dir = i < shown ? -1 : i > shown ? 1 : 0;
        const gap = Math.abs(i - shown);
        const shift = isPinned ? 0 : dir * Math.max(0, 1 - (gap - 1) * 0.45) * yld;

        /* 点击置顶那张：抬得最高、转正、放大，像从牌堆里被抽出来 */
        /* 置顶要"明显划出来"：抬到 -118（比弧线高出一大截）+ 放大 14%，
           否则只是原地微动，看不出"从牌堆里被抽出来"。 */
        const liftY = isPinned ? (wide ? -118 : -26) : isActive ? (wide ? -26 : -8) : arcY;
        const liftScale = isPinned ? scaleBase * 1.14 : isActive ? scaleBase * 1.05 : scaleBase;

        return (
          <motion.button
            key={c.id}
            type="button"
            className={`ld-card${isActive ? " is-active" : ""}${isPinned ? " is-pinned" : ""}`}
            style={
              {
                "--ld-card": c.color,
                "--i": i,
                "--float-dur": FLOAT_DUR[i % FLOAT_DUR.length],
                left: wide ? `calc(50% + ${(d * stepX + jX).toFixed(1)}px - 95px)` : undefined,
              } as CSSProperties
            }
            onPointerEnter={() => onActive(i)}
            onClick={() => {
              takeOver();
              const next = pinned === i ? null : i;
              setPinned(next);
              /* 手机上点哪张，就把它滑到屏幕中间——"划出到最上层"要有位移感，
                 只在原地放大不算"划出"。桌面是绝对定位，不用滚。 */
              if (next != null && !wide) {
                /* 不用 scrollIntoView：它会被 scroll-padding 带偏（实测偏左 16px）。
                   直接算 scrollLeft，把卡中心对到容器中心，误差 0。 */
                /* 居中：置顶期间 CSS 会关掉 scroll-snap（.has-pin），
                   否则 mandatory snap 会在滚动结束后把卡重新吸回去（实测偏左 16px）。 */
                const box = wrap.current;
                const el = box?.querySelectorAll(".ld-card")[i] as HTMLElement | undefined;
                if (box && el) {
                  const br = box.getBoundingClientRect();
                  const er = el.getBoundingClientRect();
                  const delta = er.left + er.width / 2 - (br.left + br.width / 2);
                  box.scrollTo({ left: box.scrollLeft + delta, behavior: "smooth" });
                }
              }
            }}
            onFocus={() => {
              takeOver();
              onActive(i);
            }}
            onBlur={() => onActive(null)}
            animate={{
              y: liftY,
              x: shift,
              scale: liftScale,
              rotate: isPinned ? 0 : isActive ? rotBase * 0.4 : rotBase,
              /* 置顶那张压住所有卡；其余按右压左的自然层序 */
              zIndex: isPinned ? 200 : isActive ? i * 2 + 40 : i * 2 + Math.round(centerBoost * 6),
            }}
            transition={{
              /* 置顶/让位用软一点的弹簧：抽卡要有"被拉出来"的手感，不能硬切 */
              type: "spring",
              stiffness: isPinned ? 260 : 300,
              damping: isPinned ? 22 : 26,
              mass: isPinned ? 0.9 : 0.8,
            }}
            whileTap={{ scale: liftScale * 0.98 }}
          >
            <span className="ld-card__gloss" aria-hidden="true" />

            {/* 浮动层：卡片自己在动（不管有没有人操作）。
                单独一层做 translate3d，避免和 motion 的 y/scale/rotate 抢同一个 transform。 */}
            <span className="ld-card__float">
              <span className="ld-card__head">
                <span className="ld-card__name">{c.title}</span>
                <span className="ld-card__sub">{c.subtitle}</span>
              </span>

              <span className="ld-card__art">
                <Toy kind={c.toy} size={130} />
              </span>
            </span>
          </motion.button>
        );
      })}
    </motion.div>
  );
}

/** 纸面上的两团暖光，只为不死板 */
export function LdGlow() {
  return (
    <div className="ld-glow" aria-hidden="true">
      <span className="ld-glow__a" />
      <span className="ld-glow__b" />
    </div>
  );
}
