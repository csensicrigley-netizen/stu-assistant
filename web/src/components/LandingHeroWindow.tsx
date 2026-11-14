import * as React from "react";
import {
  AnimatePresence,
  motion,
  useMotionValue,
  useReducedMotion,
  useSpring,
  useTransform,
  type MotionValue,
  type Variants,
} from "motion/react";
import { Icon, type IconName } from "./Icon";
import { clockOf, monthDayCn, useNow, weekdayCn } from "../lib/clock";
import { termPosition } from "../lib/calendar";
import { OUT, PILL, SPRING, STEP_IN, TAP, stagger } from "../lib/motion";
import "../styles/landing-showcase.css";

export type HeroMode = "today" | "week" | "campus";

export interface LandingHeroWindowProps {
  activeMode: HeroMode;
  onSelectMode: (mode: HeroMode) => void;
}

interface ModeTab {
  id: HeroMode;
  label: string;
}

const MODE_TABS: readonly ModeTab[] = [
  { id: "today", label: "今日课堂" },
  { id: "week", label: "一周光谱" },
  { id: "campus", label: "校园生活" },
];

const MODE_INDEX: Record<HeroMode, number> = {
  today: 0,
  week: 1,
  campus: 2,
};

const PARALLAX_SPRING = {
  stiffness: 180,
  damping: 24,
  mass: 0.8,
  restDelta: 0.001,
  restSpeed: 0.01,
} as const;

const PANEL_VARIANTS: Variants = {
  enter: (dir: number) => ({
    opacity: 0,
    x: dir * 18,
    scale: 0.99,
  }),
  center: {
    opacity: 1,
    x: 0,
    scale: 1,
    transition: STEP_IN,
  },
  exit: (dir: number) => ({
    opacity: 0,
    x: dir * -14,
    scale: 0.992,
    transition: OUT,
  }),
};

const PANEL_VARIANTS_REDUCED: Variants = {
  enter: { opacity: 1, x: 0, scale: 1 },
  center: { opacity: 1, x: 0, scale: 1, transition: { duration: 0 } },
  exit: { opacity: 1, x: 0, scale: 1, transition: { duration: 0 } },
};

const ORB_FACE_VARIANTS: Variants = {
  rest: { scale: 1, y: 0, transition: SPRING },
  selected: { scale: 1.035, y: -1, transition: SPRING },
  hover: { scale: 1.09, y: -4, transition: SPRING },
  tap: { scale: 0.92, y: 0, transition: TAP },
};

interface WeekSlot {
  course: string;
  room: string;
  tone: "sky" | "mint" | "ocean" | "violet" | "amber" | "rose" | "teal";
  span: "std" | "tall";
}

interface WeekColumn {
  weekday: number;
  label: string;
  slots: readonly WeekSlot[];
  freeLabel?: string;
}

const WEEK_COLUMNS: readonly WeekColumn[] = [
  {
    weekday: 1,
    label: "周一",
    slots: [
      { course: "生物信息学", room: "1–2节 · D206", tone: "sky", span: "std" },
      { course: "细胞生物学", room: "6–7节 · E104", tone: "mint", span: "std" },
    ],
  },
  {
    weekday: 2,
    label: "周二",
    slots: [
      { course: "生物统计学", room: "3–5节 · E305", tone: "ocean", span: "tall" },
    ],
    freeLabel: "下午自习",
  },
  {
    weekday: 3,
    label: "周三",
    slots: [
      { course: "基因组学", room: "3–4节 · D302", tone: "violet", span: "std" },
      { course: "学术英语", room: "8–9节 · C201", tone: "sky", span: "std" },
    ],
  },
  {
    weekday: 4,
    label: "周四",
    slots: [
      { course: "表观遗传学", room: "1–2节 · E208", tone: "amber", span: "std" },
      { course: "生信实验", room: "6–9节 · 实验B", tone: "rose", span: "tall" },
    ],
  },
  {
    weekday: 5,
    label: "周五",
    slots: [
      { course: "基因组学", room: "3–4节 · 研习室", tone: "teal", span: "std" },
    ],
    freeLabel: "下午无课",
  },
];

interface CampusStatusItem {
  id: string;
  icon: IconName;
  iconTone: "bus" | "bolt" | "book";
  title: string;
  subtitle: string;
  badge: string;
  badgeTone: "live" | "sky" | "neutral";
}

const CAMPUS_ITEMS: readonly CampusStatusItem[] = [
  {
    id: "bus",
    icon: "bus",
    iconTone: "bus",
    title: "39路 4 分钟到站 · 正门",
    subtitle: "下一班 K3 快线 12 分钟 · 往东海岸校区与高铁站",
    badge: "即将进站",
    badgeTone: "live",
  },
  {
    id: "charging",
    icon: "bolt",
    iconTone: "bolt",
    title: "思源书院充电桩 空闲 6/20",
    subtitle: "14 台实测核验 · 敬一书院 4/10 · 图书馆东侧 3/10",
    badge: "6 口可用",
    badgeTone: "sky",
  },
  {
    id: "library",
    icon: "book",
    iconTone: "book",
    title: "桑浦山图书馆 开放至 22:00",
    subtitle: "二楼阅览区与研讨室开放 · 距 E 座步行 4 分钟",
    badge: "开放中",
    badgeTone: "neutral",
  },
];

interface OrbitSatellite {
  index: 1 | 2 | 3 | 4 | 5 | 6;
  variant: "clock" | "week" | "palette" | "academic" | "bus" | "shield";
  mode: HeroMode;
  primary?: boolean;
  icon?: IconName;
  iconSize?: number;
  withMiniBolt?: boolean;
  parallaxX: number;
  parallaxY: number;
  label: string;
  shortTitle: string;
}

const ORBIT_SATELLITES: readonly OrbitSatellite[] = [
  {
    index: 1,
    variant: "clock",
    mode: "today",
    primary: true,
    icon: "clock",
    iconSize: 30,
    parallaxX: 9.5,
    parallaxY: 8,
    label: "切换至今日课堂：下一节课与换教室提醒",
    shortTitle: "下一节课",
  },
  {
    index: 2,
    variant: "week",
    mode: "week",
    primary: true,
    icon: "week",
    iconSize: 31,
    parallaxX: 11,
    parallaxY: 9.2,
    label: "切换至一周光谱：五日真实时长课表",
    shortTitle: "一周课表",
  },
  {
    index: 3,
    variant: "palette",
    mode: "today",
    parallaxX: 7.5,
    parallaxY: 6.5,
    label: "切换至今日课堂：六套校园外观色系",
    shortTitle: "外观色系",
  },
  {
    index: 4,
    variant: "academic",
    mode: "week",
    icon: "academic",
    iconSize: 30,
    parallaxX: 8.8,
    parallaxY: 7.5,
    label: "切换至一周光谱：作业截止与学业进度",
    shortTitle: "作业与学业",
  },
  {
    index: 5,
    variant: "bus",
    mode: "campus",
    primary: true,
    icon: "bus",
    iconSize: 30,
    withMiniBolt: true,
    parallaxX: 10.6,
    parallaxY: 9,
    label: "切换至校园生活：实时公交与核验充电桩",
    shortTitle: "校车与充电",
  },
  {
    index: 6,
    variant: "shield",
    mode: "campus",
    icon: "shield",
    iconSize: 28,
    parallaxX: 7,
    parallaxY: 6,
    label: "切换至校园生活：只读绝不代办隐私守护",
    shortTitle: "只读隐私保护",
  },
];

interface OrbitSatelliteButtonProps {
  orb: OrbitSatellite;
  activeMode: HeroMode;
  reduceMotion: boolean;
  smoothX: MotionValue<number>;
  smoothY: MotionValue<number>;
  onSelectMode: (mode: HeroMode) => void;
}

function OrbitSatelliteButton({
  orb,
  activeMode,
  reduceMotion,
  smoothX,
  smoothY,
  onSelectMode,
}: OrbitSatelliteButtonProps): React.JSX.Element {
  const isSelected = Boolean(orb.primary && activeMode === orb.mode);
  const invX = useTransform(smoothX, [-1, 1], [orb.parallaxX, -orb.parallaxX]);
  const invY = useTransform(smoothY, [-1, 1], [orb.parallaxY, -orb.parallaxY]);

  return (
    <button
      type="button"
      className={`ld-orb ld-orb--${orb.index} ld-orb--${orb.variant}${isSelected ? " is-selected" : ""}`}
      data-mode={orb.mode}
      aria-label={orb.label}
      aria-pressed={activeMode === orb.mode}
      title={orb.shortTitle}
      onClick={() => onSelectMode(orb.mode)}
    >
      <motion.span
        className="ld-orb__parallax"
        aria-hidden="true"
        initial={false}
        animate={reduceMotion ? undefined : isSelected ? "selected" : "rest"}
        whileHover={reduceMotion ? undefined : "hover"}
        whileTap={reduceMotion ? undefined : "tap"}
        style={reduceMotion ? undefined : { x: invX, y: invY }}
      >
        <span className="ld-orb__float">
          <motion.span
            className="ld-orb__face"
            variants={reduceMotion ? undefined : ORB_FACE_VARIANTS}
            transition={SPRING}
          >
            {orb.icon ? (
              <span className="ld-orb__glyph">
                <Icon name={orb.icon} size={orb.iconSize ?? 30} strokeWidth={2} />
              </span>
            ) : (
              <span className="ld-orb__aa">Aa</span>
            )}
            {orb.withMiniBolt && (
              <span className="ld-orb__mini-bolt">
                <Icon name="bolt" size={11} strokeWidth={2.2} />
              </span>
            )}
          </motion.span>
        </span>
      </motion.span>
    </button>
  );
}

export function LandingHeroWindow({
  activeMode,
  onSelectMode,
}: LandingHeroWindowProps): React.JSX.Element {
  const now = useNow(30_000);
  const reduceMotion = Boolean(useReducedMotion());
  const pos = termPosition(now.date);

  const [modeTrack, setModeTrack] = React.useState<{ mode: HeroMode; dir: 1 | -1 }>({
    mode: activeMode,
    dir: 1,
  });

  let dir = modeTrack.dir;
  if (modeTrack.mode !== activeMode) {
    const nextDir: 1 | -1 =
      MODE_INDEX[activeMode] >= MODE_INDEX[modeTrack.mode] ? 1 : -1;
    dir = nextDir;
    setModeTrack({ mode: activeMode, dir: nextDir });
  }

  const pointerX = useMotionValue(0);
  const pointerY = useMotionValue(0);
  const smoothX = useSpring(pointerX, PARALLAX_SPRING);
  const smoothY = useSpring(pointerY, PARALLAX_SPRING);

  const winX = useTransform(smoothX, [-1, 1], [-3.8, 3.8]);
  const winY = useTransform(smoothY, [-1, 1], [-3.2, 3.2]);
  const winRotateY = useTransform(smoothX, [-1, 1], [-2.1, 2.1]);
  const winRotateX = useTransform(smoothY, [-1, 1], [2.1, -2.1]);

  React.useEffect(() => {
    if (reduceMotion) {
      pointerX.set(0);
      pointerY.set(0);
      return;
    }
    const onResize = () => {
      if (window.innerWidth < 860) {
        pointerX.set(0);
        pointerY.set(0);
      }
    };
    window.addEventListener("resize", onResize, { passive: true });
    return () => window.removeEventListener("resize", onResize);
  }, [reduceMotion, pointerX, pointerY]);

  const handlePointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    if (
      reduceMotion ||
      e.pointerType === "touch" ||
      window.innerWidth < 860 ||
      !window.matchMedia("(hover: hover) and (pointer: fine)").matches
    ) {
      return;
    }
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return;
    const nx = Math.max(-1, Math.min(1, ((e.clientX - rect.left) / rect.width) * 2 - 1));
    const ny = Math.max(-1, Math.min(1, ((e.clientY - rect.top) / rect.height) * 2 - 1));
    pointerX.set(nx);
    pointerY.set(ny);
  };

  const handlePointerLeave = () => {
    pointerX.set(0);
    pointerY.set(0);
  };

  const liveStamp = pos
    ? `第 ${pos.week} 周 · ${weekdayCn(now.weekday)} ${clockOf(now.ms)}`
    : `${monthDayCn(now.date)} · ${weekdayCn(now.weekday)} ${clockOf(now.ms)}`;

  const contextLabel =
    activeMode === "today"
      ? "教务处作息 · 实时校准"
      : activeMode === "week"
        ? pos
          ? `${pos.phase?.label ?? "校历"} · 第 ${pos.week} 周`
          : "秋季学期课表"
        : "桑浦山 · 实时状态";

  const activeWeekday = now.weekday >= 1 && now.weekday <= 5 ? now.weekday : 2;
  const activePanelVariants = reduceMotion ? PANEL_VARIANTS_REDUCED : PANEL_VARIANTS;

  return (
    <div
      className="ld-showcase"
      data-active-mode={activeMode}
      onPointerMove={handlePointerMove}
      onPointerLeave={handlePointerLeave}
    >
      <motion.div
        className="ld-win"
        role="region"
        aria-label="STU 事项助手产品预览窗口"
        style={
          reduceMotion
            ? undefined
            : {
                x: winX,
                y: winY,
                rotateX: winRotateX,
                rotateY: winRotateY,
              }
        }
      >
        {/* macOS Window Chrome */}
        <div className="ld-win__bar">
          <div className="ld-win__lights" aria-hidden="true">
            <span className="ld-win__light ld-win__light--close" />
            <span className="ld-win__light ld-win__light--min" />
            <span className="ld-win__light ld-win__light--zoom" />
          </div>

          <div className="ld-win__title">
            <span>STU 事项助手</span>
            <span className="ld-win__title-campus"> — 桑浦山校区</span>
          </div>

          <div className="ld-win__clock" aria-label={`汕头大学校历与北京时间：${liveStamp}`}>
            {liveStamp}
          </div>
        </div>

        {/* Crisp White Floating Campus Card */}
        <div className="ld-win__card">
          <div className="ld-win__head">
            <div className="ld-win__tabs" role="group" aria-label="预览卡片视图">
              {MODE_TABS.map((tab) => {
                const isOn = activeMode === tab.id;
                return (
                  <button
                    key={tab.id}
                    type="button"
                    className={`ld-win__tab${isOn ? " is-active" : ""}`}
                    aria-pressed={isOn}
                    onClick={() => onSelectMode(tab.id)}
                  >
                    {isOn && (
                      <motion.span
                        className="ld-win__tab-bg"
                        layoutId="ld-win-mode-pill"
                        style={{ borderRadius: 9999 }}
                        transition={reduceMotion ? { duration: 0 } : PILL}
                        aria-hidden="true"
                      />
                    )}
                    <span
                      className={`ld-win__tab-dot ld-win__tab-dot--${tab.id}`}
                      aria-hidden="true"
                    />
                    <span className="ld-win__tab-label">{tab.label}</span>
                  </button>
                );
              })}
            </div>

            <div className="ld-win__context">{contextLabel}</div>
          </div>

          <div className="ld-win__body">
            <AnimatePresence mode="wait" initial={false} custom={dir}>
              {activeMode === "today" && (
                <motion.div
                  key="today"
                  className="ld-win__panel"
                  custom={dir}
                  variants={activePanelVariants}
                  initial="enter"
                  animate="center"
                  exit="exit"
                >
                  <div className="ld-today">
                    <motion.div
                      className="ld-today__main"
                      initial={reduceMotion ? false : { opacity: 0, y: 8 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={
                        reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(0) }
                      }
                    >
                      <div className="ld-today__art" aria-hidden="true">
                        <Icon name="today" size={24} strokeWidth={1.9} />
                        <span className="ld-today__art-tag">3–5 节</span>
                      </div>

                      <div className="ld-today__info">
                        <div className="ld-today__eyebrow-row">
                          <span className="ld-today__live">
                            <span className="ld-today__live-dot" aria-hidden="true" />
                            <span>下一节课 · 课间换教室</span>
                          </span>
                          <span className="ld-today__hop">
                            <Icon name="walk" size={13} strokeWidth={1.9} />
                            <span>D座206 → E305</span>
                          </span>
                        </div>

                        <p className="ld-today__title">生物统计学 · 桑浦山 E305</p>
                        <p className="ld-today__sub">10:00–12:35 · 第二大节 155 分钟</p>
                      </div>
                    </motion.div>

                    <motion.div
                      className="ld-today__progress"
                      aria-hidden="true"
                      initial={reduceMotion ? false : { opacity: 0, y: 6 }}
                      animate={{ opacity: 1, y: 0 }}
                      transition={
                        reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(1) }
                      }
                    >
                      <div className="ld-today__bar">
                        <motion.span
                          className="ld-today__bar-fill"
                          initial={reduceMotion ? false : { scaleX: 0 }}
                          animate={{ scaleX: 1 }}
                          style={{ transformOrigin: "left", borderRadius: 9999 }}
                          transition={
                            reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(1) }
                          }
                        />
                      </div>
                      <div className="ld-today__ticks">
                        <span>10:00 第 3 节</span>
                        <span>10:55 第 4 节</span>
                        <span>12:35 下课</span>
                      </div>
                    </motion.div>

                    <div className="ld-today__foot">
                      <motion.div
                        className="ld-today__pill ld-today__pill--warn"
                        initial={reduceMotion ? false : { opacity: 0, y: 6, scale: 0.99 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        transition={
                          reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(2) }
                        }
                      >
                        <Icon name="items" size={15} strokeWidth={1.9} />
                        <span className="ld-today__pill-text">
                          生物信息学作业 · 23:59 截止
                        </span>
                      </motion.div>
                      <motion.div
                        className="ld-today__pill ld-today__pill--calm"
                        initial={reduceMotion ? false : { opacity: 0, y: 6, scale: 0.99 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        transition={
                          reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(3) }
                        }
                      >
                        <Icon name="clock" size={15} strokeWidth={1.9} />
                        <span className="ld-today__pill-text">
                          午休留白 · 12:35–14:00（85 分钟）
                        </span>
                      </motion.div>
                    </div>
                  </div>
                </motion.div>
              )}

              {activeMode === "week" && (
                <motion.div
                  key="week"
                  className="ld-win__panel"
                  custom={dir}
                  variants={activePanelVariants}
                  initial="enter"
                  animate="center"
                  exit="exit"
                >
                  <div className="ld-week">
                    <div className="ld-week__meta">
                      <span>
                        周一至周五
                        <span className="ld-week__meta-detail">
                          {" "}
                          · 按 100 / 155 分钟真实时长排布
                        </span>
                      </span>
                      <span className="ld-week__tag">本周 14 学时 · 零冲突</span>
                    </div>

                    <div className="ld-week__grid">
                      {WEEK_COLUMNS.map((col, i) => {
                        const isToday = col.weekday === activeWeekday;
                        return (
                          <motion.div
                            key={col.weekday}
                            className={`ld-week__col${isToday ? " is-today" : ""}`}
                            initial={reduceMotion ? false : { opacity: 0, y: 8, scale: 0.985 }}
                            animate={{ opacity: 1, y: 0, scale: 1 }}
                            transition={
                              reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(i) }
                            }
                          >
                            <div className="ld-week__day">
                              <span>{col.label}</span>
                              {isToday && (
                                <span className="ld-week__day-dot" aria-hidden="true" />
                              )}
                            </div>

                            {col.slots.map((slot) => (
                              <div
                                key={`${col.weekday}-${slot.course}`}
                                className={`ld-week__slot ld-week__slot--${slot.span} ld-week__slot--${slot.tone}`}
                              >
                                <span className="ld-week__course">{slot.course}</span>
                                <span className="ld-week__room">{slot.room}</span>
                              </div>
                            ))}

                            {col.freeLabel && (
                              <div className="ld-week__free">{col.freeLabel}</div>
                            )}
                          </motion.div>
                        );
                      })}
                    </div>
                  </div>
                </motion.div>
              )}

              {activeMode === "campus" && (
                <motion.div
                  key="campus"
                  className="ld-win__panel"
                  custom={dir}
                  variants={activePanelVariants}
                  initial="enter"
                  animate="center"
                  exit="exit"
                >
                  <div className="ld-campus">
                    {CAMPUS_ITEMS.map((item, i) => (
                      <motion.div
                        key={item.id}
                        className="ld-campus__row"
                        initial={reduceMotion ? false : { opacity: 0, y: 8, scale: 0.99 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        transition={
                          reduceMotion ? { duration: 0 } : { ...SPRING, delay: stagger(i) }
                        }
                      >
                        <div
                          className={`ld-campus__icon ld-campus__icon--${item.iconTone}`}
                          aria-hidden="true"
                        >
                          <Icon name={item.icon} size={19} strokeWidth={1.95} />
                        </div>

                        <div className="ld-campus__copy">
                          <p className="ld-campus__title">{item.title}</p>
                          <p className="ld-campus__sub">{item.subtitle}</p>
                        </div>

                        <span
                          className={`ld-campus__badge ld-campus__badge--${item.badgeTone}`}
                        >
                          {item.badgeTone === "live" && (
                            <span className="ld-campus__live-dot" aria-hidden="true" />
                          )}
                          <span>{item.badge}</span>
                        </span>
                      </motion.div>
                    ))}
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>
        </div>
      </motion.div>

      {/* 6 Orbiting 3D Gradient Squircle Satellite Icons */}
      <div className="ld-orbs" role="group" aria-label="功能快捷卫星图标">
        {ORBIT_SATELLITES.map((orb) => (
          <OrbitSatelliteButton
            key={orb.variant}
            orb={orb}
            activeMode={activeMode}
            reduceMotion={reduceMotion}
            smoothX={smoothX}
            smoothY={smoothY}
            onSelectMode={onSelectMode}
          />
        ))}
      </div>
    </div>
  );
}
