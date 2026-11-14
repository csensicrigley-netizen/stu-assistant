import { useState } from "react";
import type React from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { Icon, type IconName } from "./Icon";
import {
  JELLY_SOFT,
  OUT,
  PILL,
  PRESS,
  SPRING,
  STEP_IN,
  TAP,
  stagger,
} from "../lib/motion";
import { BIG_SECTIONS, PERIODS, type BigId } from "../lib/sections";
import { PALETTE_OPTIONS, type PalettePref } from "../lib/useTheme";
import "../styles/landing-gallery.css";

export interface LandingGalleryProps {
  onStart: () => void;
  onPreview?: () => void;
  onOpenQr: () => void;
  apkUrl: string;
  versionName: string;
  isNative: boolean;
}

type CardSize = "s" | "m" | "l" | "xl";
type SettingTab = "palette" | "size" | "rhythm" | "haptics";
type WeekFilter = "w9" | "w10" | "exam";
type CampusTab = "bus" | "charger";

const SIZE_OPTIONS: readonly { key: CardSize; label: string; desc: string }[] = [
  { key: "s", label: "S", desc: "紧凑密度" },
  { key: "m", label: "M", desc: "标准视图" },
  { key: "l", label: "L", desc: "舒展留白" },
  { key: "xl", label: "XL", desc: "醒目字号" },
];

const SIDEBAR_ITEMS: readonly {
  key: SettingTab;
  label: string;
  icon: IconName;
  tileClass: string;
  note: string;
}[] = [
  {
    key: "palette",
    label: "外观色系",
    icon: "palette",
    tileClass: "ld-prev-tile--pink",
    note: "六套校园主题即时预览",
  },
  {
    key: "size",
    label: "卡片尺寸",
    icon: "monitor",
    tileClass: "ld-prev-tile--blue",
    note: "四档字号与内边距自由调节",
  },
  {
    key: "rhythm",
    label: "作息刻度",
    icon: "clock",
    tileClass: "ld-prev-tile--teal",
    note: "按教务处真实分钟比例排布",
  },
  {
    key: "haptics",
    label: "触感反馈",
    icon: "spark",
    tileClass: "ld-prev-tile--violet",
    note: "轻巧克制的勾选与切换触感",
  },
];

const SHORT_BIG: Record<BigId, string> = {
  1: "一大节",
  2: "二大节",
  3: "三大节",
  4: "四大节",
  5: "五大节",
};

const RIBBON_BIG_LABEL: Record<BigId, string> = {
  1: "一",
  2: "二",
  3: "三",
  4: "四",
  5: "五",
};

const BIG_HOP_META: Record<BigId, { route: string; note: string }> = {
  1: { route: "桑浦山 D座206 → E305", note: "大节课间 20 分钟 · 换楼充裕" },
  2: { route: "桑浦山 D座206 → E305", note: "大节课间 20 分钟 · 换楼提醒" },
  3: { route: "桑浦山 E座302 → 科报厅", note: "午休 85 分钟后 · 课间 20 分钟" },
  4: { route: "桑浦山 科报厅 → 淑德书院", note: "课后晚休 45 分钟 · 衔接晚课" },
  5: { route: "桑浦山 机房B2 → 书院宿区", note: "21:55 晚课结束 · 校道照明常开" },
};

const WEEK_ORDER: Record<WeekFilter, number> = {
  w9: 0,
  w10: 1,
  exam: 2,
};

const WEEK_TABS: readonly { key: WeekFilter; label: string }[] = [
  { key: "w9", label: "第 9 周" },
  { key: "w10", label: "第 10 周" },
  { key: "exam", label: "考试周" },
];

interface SpectrumSlot {
  title: string;
  room: string;
  tone: "rose" | "sky" | "mint" | "violet" | "amber";
  span: "short" | "long";
}

interface DayColumn {
  day: string;
  am: SpectrumSlot | null;
  pm: SpectrumSlot | null;
}

const WEEK_SCHEDULES: Record<WeekFilter, readonly DayColumn[]> = {
  w9: [
    {
      day: "周一",
      am: { title: "生物信息学", room: "E305", tone: "rose", span: "long" },
      pm: { title: "学术英语", room: "D104", tone: "sky", span: "short" },
    },
    {
      day: "周二",
      am: { title: "基因组学", room: "D206", tone: "mint", span: "short" },
      pm: { title: "生物统计学", room: "E302", tone: "violet", span: "long" },
    },
    {
      day: "周三",
      am: null,
      pm: { title: "细胞生物学", room: "科报厅", tone: "amber", span: "long" },
    },
    {
      day: "周四",
      am: { title: "生物信息实验", room: "机房B2", tone: "sky", span: "long" },
      pm: { title: "表观遗传学", room: "淑德书院", tone: "rose", span: "short" },
    },
    {
      day: "周五",
      am: { title: "基因组学", room: "D206", tone: "mint", span: "short" },
      pm: null,
    },
  ],
  w10: [
    {
      day: "周一",
      am: { title: "生物信息学", room: "E305", tone: "rose", span: "long" },
      pm: null,
    },
    {
      day: "周二",
      am: { title: "基因组学", room: "D206", tone: "mint", span: "short" },
      pm: { title: "生物统计学", room: "E305", tone: "violet", span: "long" },
    },
    {
      day: "周三",
      am: { title: "分子诊断导论", room: "医学院201", tone: "sky", span: "short" },
      pm: { title: "细胞生物学", room: "科报厅", tone: "amber", span: "long" },
    },
    {
      day: "周四",
      am: null,
      pm: { title: "表观遗传研讨", room: "淑德书院", tone: "rose", span: "long" },
    },
    {
      day: "周五",
      am: { title: "体育与健康", room: "综合馆", tone: "mint", span: "short" },
      pm: { title: "文献阅读组会", room: "E410", tone: "violet", span: "short" },
    },
  ],
  exam: [
    {
      day: "周一",
      am: { title: "生物统计期末", room: "E302·021座", tone: "violet", span: "long" },
      pm: null,
    },
    {
      day: "周二",
      am: null,
      pm: null,
    },
    {
      day: "周三",
      am: { title: "基因组学闭卷", room: "D104·015座", tone: "mint", span: "long" },
      pm: null,
    },
    {
      day: "周四",
      am: null,
      pm: { title: "细胞生物考试", room: "E201·008座", tone: "amber", span: "long" },
    },
    {
      day: "周五",
      am: { title: "生信报告答辩", room: "机房B2", tone: "rose", span: "short" },
      pm: null,
    },
  ],
};

interface DemoTask {
  id: string;
  title: string;
  source: string;
  due: string;
}

const DEMO_TASKS: readonly DemoTask[] = [
  {
    id: "t1",
    title: "生物信息学 · 序列比对实验报告",
    source: "MySTU",
    due: "今天 23:59",
  },
  {
    id: "t2",
    title: "基因组学 · 第 10 周随堂测验",
    source: "雨课堂",
    due: "3 天内",
  },
  {
    id: "t3",
    title: "生物统计学 · R 语言回归练习",
    source: "MySTU",
    due: "3 天内",
  },
];

interface CampusLiveRow {
  icon: IconName;
  title: string;
  sub: string;
  tag: string;
  strong: boolean;
}

const CAMPUS_ROWS: Record<CampusTab, readonly CampusLiveRow[]> = {
  bus: [
    {
      icon: "bus",
      title: "39路 · 汕大正门站",
      sub: "开往东厦客运站 · 距校门 2 站",
      tag: "4 分钟到站",
      strong: true,
    },
    {
      icon: "bus",
      title: "K3 快线 · 汕大正门站",
      sub: "直达汕头高铁站 · 准点运行",
      tag: "11 分钟",
      strong: false,
    },
    {
      icon: "bus",
      title: "181B · 跨校区专线",
      sub: "桑浦山校区 ↔ 东海岸校区",
      tag: "18:30 发车",
      strong: false,
    },
  ],
  charger: [
    {
      icon: "bolt",
      title: "思源书院 · 东侧车棚桩",
      sub: "02 / 05 / 08 号插座可用",
      tag: "空闲 6 / 10 口",
      strong: true,
    },
    {
      icon: "bolt",
      title: "图书馆 · 北侧非机动车区",
      sub: "01 / 04 / 07 / 09 号插座可用",
      tag: "空闲 4 / 10 口",
      strong: false,
    },
    {
      icon: "info",
      title: "敬一书院 · 架空层桩点",
      sub: "信号未上报，不计入可用插座数",
      tag: "设备离线",
      strong: false,
    },
  ],
};

const SUBCARD_PANEL_VARIANTS = {
  enter: (dir: number) => ({ opacity: 0, x: dir * 14, scale: 0.995 }),
  center: { opacity: 1, x: 0, scale: 1, transition: STEP_IN },
  exit: (dir: number) => ({ opacity: 0, x: dir * -10, scale: 0.996, transition: OUT }),
};

const REDUCED_PANEL_VARIANTS = {
  enter: { opacity: 1, x: 0, scale: 1 },
  center: { opacity: 1, x: 0, scale: 1, transition: { duration: 0 } },
  exit: { opacity: 1, x: 0, scale: 1, transition: { duration: 0 } },
};

const NOTE_VARIANTS = {
  enter: { opacity: 0, y: 5 },
  center: { opacity: 1, y: 0, transition: STEP_IN },
  exit: { opacity: 0, y: -4, transition: OUT },
};

const REDUCED_NOTE_VARIANTS = {
  enter: { opacity: 1, y: 0 },
  center: { opacity: 1, y: 0, transition: { duration: 0 } },
  exit: { opacity: 1, y: 0, transition: { duration: 0 } },
};

export function LandingGallery({
  onStart,
  onPreview,
  onOpenQr,
  apkUrl,
  versionName,
  isNative,
}: LandingGalleryProps): React.JSX.Element {
  const motionHookPref = useReducedMotion();
  const reduceMotion =
    Boolean(motionHookPref) ||
    (typeof window !== "undefined" &&
      typeof window.matchMedia === "function" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches);

  const [activeSetting, setActiveSetting] = useState<SettingTab>("palette");
  const [cardSize, setCardSize] = useState<CardSize>("m");
  const [selectedPalette, setSelectedPalette] = useState<PalettePref>("sweet");
  const [bigState, setBigState] = useState<{ current: BigId; dir: 1 | -1 }>({
    current: 2,
    dir: 1,
  });
  const [weekState, setWeekState] = useState<{ current: WeekFilter; dir: 1 | -1 }>({
    current: "w10",
    dir: 1,
  });
  const [doneTaskIds, setDoneTaskIds] = useState<readonly string[]>(["t3"]);
  const [toggledTaskId, setToggledTaskId] = useState<string | null>(null);
  const [campusState, setCampusState] = useState<{ current: CampusTab; dir: 1 | -1 }>({
    current: "bus",
    dir: 1,
  });

  const activeBig = bigState.current;
  const bigDir = bigState.dir;
  const weekFilter = weekState.current;
  const weekDir = weekState.dir;
  const campusTab = campusState.current;
  const campusDir = campusState.dir;

  const currentPalette =
    PALETTE_OPTIONS.find((item) => item.key === selectedPalette) ?? PALETTE_OPTIONS[0];
  const currentSizeMeta =
    SIZE_OPTIONS.find((item) => item.key === cardSize) ?? SIZE_OPTIONS[1];
  const currentSettingMeta =
    SIDEBAR_ITEMS.find((item) => item.key === activeSetting) ?? SIDEBAR_ITEMS[0];
  const currentBig =
    BIG_SECTIONS.find((section) => section.id === activeBig) ?? BIG_SECTIONS[1];
  const currentBigPeriods = PERIODS.filter((period) => period.big === activeBig);
  const currentBigHop = BIG_HOP_META[activeBig];
  const weekColumns = WEEK_SCHEDULES[weekFilter];
  const doneCount = doneTaskIds.length;
  const remainingTasks = DEMO_TASKS.length - doneCount;
  const campusRows = CAMPUS_ROWS[campusTab];

  const pillTransition = reduceMotion ? { duration: 0 } : PILL;
  const panelVariants = reduceMotion ? REDUCED_PANEL_VARIANTS : SUBCARD_PANEL_VARIANTS;
  const noteVariants = reduceMotion ? REDUCED_NOTE_VARIANTS : NOTE_VARIANTS;

  const revealProps = reduceMotion
    ? { initial: false as const }
    : {
        initial: { opacity: 0, y: 22, scale: 0.99 },
        whileInView: { opacity: 1, y: 0, scale: 1 },
        viewport: { once: true, amount: 0.08, margin: "0px 0px -24px 0px" },
        transition: JELLY_SOFT,
      };

  const selectBigSection = (next: BigId) => {
    setBigState((prev) =>
      prev.current === next
        ? prev
        : { current: next, dir: next >= prev.current ? 1 : -1 },
    );
  };

  const selectWeekFilter = (next: WeekFilter) => {
    setWeekState((prev) =>
      prev.current === next
        ? prev
        : {
            current: next,
            dir: WEEK_ORDER[next] >= WEEK_ORDER[prev.current] ? 1 : -1,
          },
    );
  };

  const toggleTask = (id: string) => {
    setToggledTaskId(id);
    setDoneTaskIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id],
    );
  };

  const selectCampusTab = (next: CampusTab) => {
    setCampusState((prev) =>
      prev.current === next
        ? prev
        : { current: next, dir: next === "charger" ? 1 : -1 },
    );
  };

  const renderRibbonSegment = (sec: (typeof BIG_SECTIONS)[number]) => {
    const isActive = activeBig === sec.id;
    return (
      <motion.button
        key={sec.id}
        type="button"
        className="ld-prev-ribbon__seg"
        style={{ flex: `${sec.duration} 1 0%`, borderRadius: 10 }}
        aria-pressed={isActive}
        onClick={() => selectBigSection(sec.id)}
        animate={
          reduceMotion
            ? undefined
            : { y: isActive ? -2 : 0, scale: isActive ? 1.02 : 1 }
        }
        whileTap={reduceMotion ? undefined : PRESS}
        transition={SPRING}
      >
        {isActive && (
          <motion.span
            layoutId="ld-prev-ribbon-pill"
            className="ld-prev-ribbon__pill"
            style={{ borderRadius: 10 }}
            transition={pillTransition}
            aria-hidden="true"
          />
        )}
        <span className="ld-prev-ribbon__content">
          <b>{RIBBON_BIG_LABEL[sec.id]}</b>
          <small>{sec.duration}m</small>
        </span>
      </motion.button>
    );
  };

  return (
    <>
      {/* ================================================================
          Section 1: #theming
          ================================================================ */}
      <section id="theming" className="ld-section" aria-labelledby="ld-theming-title">
        <div className="ld-container">
          <motion.div className="ld-section__intro" {...revealProps}>
            <span className="ld-eyebrow">THEMING</span>
            <h2 id="ld-theming-title" className="ld-section__title">
              六套校园色系，契合你的日常节奏。
            </h2>
            <p className="ld-section__lead">
              从春日樱粉到桑浦海蓝，色系与卡片字号随心切换；课表底座严格对齐汕大教务处官方作息，不把午休与晚休藏进均分格子里。
            </p>
          </motion.div>

          <div className="ld-grid">
            {/* Card 1: Sleeve Settings & Campus Palette Preview */}
            <motion.article className="ld-fcard ld-fcard--split" {...revealProps}>
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">六套汕大校园色系，四档字号呼吸感</h3>
                <p className="ld-fcard__body">
                  灵感取自桑浦山海风、盛夏红棉、水库青松与钟楼晚霞。切换色系只调整纸面与主色氛围，所有功能层级、无障碍对比度与系统深浅色跟随保持一致。
                </p>
                <p className="ld-fcard__meta">
                  支持 S / M / L / XL 四档卡片尺寸 · 此处预览仅在卡片内生效
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--settings"
                aria-label="外观与卡片尺寸交互预览"
              >
                <div className="ld-prev-sidebar" role="group" aria-label="设置分组">
                  {SIDEBAR_ITEMS.map((item) => {
                    const isSelected = activeSetting === item.key;
                    return (
                      <button
                        key={item.key}
                        type="button"
                        className="ld-prev-sidebar__item"
                        aria-pressed={isSelected}
                        onClick={() => setActiveSetting(item.key)}
                      >
                        {isSelected && (
                          <motion.span
                            layoutId="ld-prev-sidebar-pill"
                            className="ld-prev-sidebar__pill"
                            style={{ borderRadius: 12 }}
                            transition={pillTransition}
                            aria-hidden="true"
                          />
                        )}
                        <span className={`ld-prev-tile ${item.tileClass}`} aria-hidden="true">
                          <Icon name={item.icon} size={15} strokeWidth={2} />
                        </span>
                        <span className="ld-prev-sidebar__text">{item.label}</span>
                      </button>
                    );
                  })}
                </div>

                <div className="ld-prev-pane">
                  <div
                    className={`ld-prev-group ${
                      activeSetting === "size" ? "ld-prev-group--focus" : ""
                    }`}
                  >
                    <div className="ld-prev-bar">
                      <span className="ld-prev-label">卡片尺寸</span>
                      <span className="ld-prev-muted">
                        {currentSizeMeta.label} · {currentSizeMeta.desc}
                      </span>
                    </div>
                    <div className="ld-prev-sizes" role="group" aria-label="选择卡片尺寸">
                      {SIZE_OPTIONS.map((opt) => {
                        const isSelected = cardSize === opt.key;
                        return (
                          <button
                            key={opt.key}
                            type="button"
                            className="ld-prev-size"
                            aria-pressed={isSelected}
                            onClick={() => setCardSize(opt.key)}
                          >
                            {isSelected && (
                              <motion.span
                                layoutId="ld-prev-size-pill"
                                className="ld-prev-active-pill"
                                style={{ borderRadius: 9999 }}
                                transition={pillTransition}
                                aria-hidden="true"
                              />
                            )}
                            <span className="ld-prev-pill__content">{opt.label}</span>
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div
                    className={`ld-prev-group ${
                      activeSetting === "palette" ? "ld-prev-group--focus" : ""
                    }`}
                  >
                    <div className="ld-prev-bar">
                      <span className="ld-prev-label">校园色系</span>
                      <span className="ld-prev-muted">
                        {currentPalette.label} · {currentPalette.subtitle}
                      </span>
                    </div>
                    <div className="ld-prev-palettes" role="group" aria-label="选择校园色系">
                      {PALETTE_OPTIONS.map((item) => {
                        const isSelected = selectedPalette === item.key;
                        return (
                          <button
                            key={item.key}
                            type="button"
                            className="ld-prev-pill"
                            aria-pressed={isSelected}
                            onClick={() => setSelectedPalette(item.key)}
                          >
                            {isSelected && (
                              <motion.span
                                layoutId="ld-prev-palette-pill"
                                className="ld-prev-active-pill"
                                style={{ borderRadius: 9999 }}
                                transition={pillTransition}
                                aria-hidden="true"
                              />
                            )}
                            <span className="ld-prev-pill__content">
                              <span
                                className="ld-prev-dot"
                                style={{ backgroundColor: item.swatchBrand }}
                                aria-hidden="true"
                              />
                              <span>{item.label}</span>
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <motion.div
                    layout={!reduceMotion}
                    transition={SPRING}
                    className={`ld-prev-mini-course ld-prev-mini-course--${cardSize}`}
                    style={{
                      backgroundColor: currentPalette.swatchPaper,
                      borderLeftColor: currentPalette.swatchBrand,
                      borderRadius: 12,
                    }}
                  >
                    <motion.div
                      layout={!reduceMotion ? "position" : false}
                      transition={SPRING}
                      className="ld-prev-mini-course__top"
                    >
                      <span className="ld-prev-mini-course__badge">
                        <span
                          className="ld-prev-dot"
                          style={{ backgroundColor: currentPalette.swatchBrand }}
                          aria-hidden="true"
                        />
                        {currentPalette.label} · {currentPalette.subtitle}
                      </span>
                      <span className="ld-prev-mini-course__time">10:00–12:35</span>
                    </motion.div>
                    <motion.div
                      layout={!reduceMotion ? "position" : false}
                      transition={SPRING}
                      className="ld-prev-mini-course__title"
                    >
                      生物统计学 · 第二大节
                    </motion.div>
                    <motion.div
                      layout={!reduceMotion ? "position" : false}
                      transition={SPRING}
                      className="ld-prev-mini-course__meta"
                    >
                      <span className="ld-prev-mini-course__note-wrap">
                        <span>桑浦山 E座305 · </span>
                        <AnimatePresence mode="wait" initial={false}>
                          <motion.span
                            key={activeSetting}
                            className="ld-prev-mini-course__note"
                            variants={noteVariants}
                            initial="enter"
                            animate="center"
                            exit="exit"
                          >
                            {currentSettingMeta.note}
                          </motion.span>
                        </AnimatePresence>
                      </span>
                      <span
                        className="ld-prev-mini-course__accent"
                        style={{ backgroundColor: currentPalette.swatchAccent }}
                      >
                        155 分钟
                      </span>
                    </motion.div>
                  </motion.div>
                </div>
              </div>
            </motion.article>

            {/* Card 2: STU 13-Period & 5 Unequal Big-Sections Rhythm */}
            <motion.article
              className="ld-fcard ld-fcard--split ld-fcard--reverse"
              {...revealProps}
            >
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">教务处官方作息：五大节不等长真实时间轴</h3>
                <p className="ld-fcard__body">
                  汕大一天 13 节课（印作 1–9、0、A、B、C）归为五大节，真实时长分别是 100 / 155 / 100 / 155 / 155 分钟。我们按真实分钟数绘制比例色带，85 分钟午休与 45 分钟晚休原样留白，大节间换教室路线一目了然。
                </p>
                <p className="ld-fcard__meta">
                  依据《汕头大学课堂作息时间表》建模 · 每小节 45 分钟 / 小节间 10 分钟
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--rhythm"
                aria-label="五大节作息时间轴预览"
              >
                <div className="ld-prev-bar">
                  <span className="ld-prev-label">点击切换五大节刻度</span>
                  <span className="ld-prev-muted">08:00–21:55 全天实长</span>
                </div>

                <div className="ld-prev-pills" role="group" aria-label="选择大节">
                  {BIG_SECTIONS.map((sec) => {
                    const isSelected = activeBig === sec.id;
                    return (
                      <button
                        key={sec.id}
                        type="button"
                        className="ld-prev-pill"
                        aria-pressed={isSelected}
                        onClick={() => selectBigSection(sec.id)}
                      >
                        {isSelected && (
                          <motion.span
                            layoutId="ld-prev-big-pill"
                            className="ld-prev-active-pill"
                            style={{ borderRadius: 9999 }}
                            transition={pillTransition}
                            aria-hidden="true"
                          />
                        )}
                        <span className="ld-prev-pill__content">{SHORT_BIG[sec.id]}</span>
                      </button>
                    );
                  })}
                </div>

                <div className="ld-prev-surface">
                  <div className="ld-prev-ribbon" aria-label="全天分钟比例时间轴">
                    {BIG_SECTIONS.slice(0, 2).map((sec) => renderRibbonSegment(sec))}
                    <div
                      className="ld-prev-ribbon__break"
                      style={{ flex: "85 1 0%" }}
                      title="午休 12:35–14:00（85 分钟）"
                    >
                      <span>午休</span>
                    </div>
                    {BIG_SECTIONS.slice(2, 4).map((sec) => renderRibbonSegment(sec))}
                    <div
                      className="ld-prev-ribbon__break"
                      style={{ flex: "45 1 0%" }}
                      title="晚休 18:35–19:20（45 分钟）"
                    >
                      <span>晚休</span>
                    </div>
                    {renderRibbonSegment(BIG_SECTIONS[4])}
                  </div>

                  <AnimatePresence mode="wait" initial={false} custom={bigDir}>
                    <motion.div
                      key={activeBig}
                      custom={bigDir}
                      className="ld-prev-rhythm-panel"
                      variants={panelVariants}
                      initial="enter"
                      animate="center"
                      exit="exit"
                    >
                      <div className="ld-prev-bar">
                        <span className="ld-prev-label">
                          {currentBig.name} · {currentBig.start}–{currentBig.end}
                        </span>
                        <span className="ld-prev-tag ld-prev-tag--strong">
                          共 {currentBig.duration} 分钟
                        </span>
                      </div>

                      <div className="ld-prev-periods">
                        {currentBigPeriods.map((period, idx) => (
                          <motion.span
                            key={period.index}
                            className="ld-prev-period-chip"
                            style={{ borderRadius: 8 }}
                            initial={reduceMotion ? false : { opacity: 0, y: 6, scale: 0.98 }}
                            animate={{ opacity: 1, y: 0, scale: 1 }}
                            transition={
                              reduceMotion
                                ? { duration: 0 }
                                : { ...STEP_IN, delay: stagger(idx) }
                            }
                          >
                            <b>第 {period.code} 节</b>
                            <span>
                              {period.start}–{period.end}
                            </span>
                          </motion.span>
                        ))}
                      </div>
                    </motion.div>
                  </AnimatePresence>
                </div>

                <AnimatePresence mode="wait" initial={false} custom={bigDir}>
                  <motion.div
                    key={activeBig}
                    custom={bigDir}
                    className="ld-prev-hop"
                    style={{ borderRadius: 12 }}
                    variants={panelVariants}
                    initial="enter"
                    animate="center"
                    exit="exit"
                  >
                    <span className="ld-prev-hop__left">
                      <span className="ld-prev-hop__icon" aria-hidden="true">
                        <Icon name="walk" size={16} />
                      </span>
                      <span>{currentBigHop.route}</span>
                    </span>
                    <span className="ld-prev-tag">{currentBigHop.note}</span>
                  </motion.div>
                </AnimatePresence>
              </div>
            </motion.article>
          </div>
        </div>
      </section>

      {/* ================================================================
          Section 2: #features
          ================================================================ */}
      <section id="features" className="ld-section" aria-labelledby="ld-features-title">
        <div className="ld-container">
          <motion.div className="ld-section__intro" {...revealProps}>
            <span className="ld-eyebrow">FEATURE LIST</span>
            <h2 id="ld-features-title" className="ld-section__title">
              从早八课表到晚归校车，一处收齐。
            </h2>
            <p className="ld-section__lead">
              把散落在教务系统、MySTU、雨课堂与校园角落的高频信息整理成干净的日常清单，打开即看，看完即走。
            </p>
          </motion.div>

          <div className="ld-grid">
            {/* Card 3: Weekly Proportional Spectrum & Multi-Source Import */}
            <motion.article className="ld-fcard ld-fcard--split" {...revealProps}>
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">一周课表光谱与多源自动导入</h3>
                <p className="ld-fcard__body">
                  无缝支持本部教务自动同步、医学院教务自动同步，以及 CSV 表格与粘贴文本手动导入。同日课程自动分配高区分度色相，教学周、单双周与考试周随时切换。
                </p>
                <p className="ld-fcard__meta">
                  支持本部教务 · 医学院教务自动同步 · CSV 与粘贴导入
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--week"
                aria-label="一周课表光谱与周次切换预览"
              >
                <div className="ld-prev-bar">
                  <div className="ld-prev-pills" aria-label="支持的数据来源">
                    <span className="ld-prev-tag ld-prev-tag--white">本部教务</span>
                    <span className="ld-prev-tag ld-prev-tag--white">医学院教务</span>
                    <span className="ld-prev-tag ld-prev-tag--white">CSV / 粘贴</span>
                  </div>
                  <div className="ld-prev-pills" role="group" aria-label="切换示例周次">
                    {WEEK_TABS.map((tab) => {
                      const isSelected = weekFilter === tab.key;
                      return (
                        <button
                          key={tab.key}
                          type="button"
                          className="ld-prev-pill"
                          aria-pressed={isSelected}
                          onClick={() => selectWeekFilter(tab.key)}
                        >
                          {isSelected && (
                            <motion.span
                              layoutId="ld-prev-week-pill"
                              className="ld-prev-active-pill"
                              style={{ borderRadius: 9999 }}
                              transition={pillTransition}
                              aria-hidden="true"
                            />
                          )}
                          <span className="ld-prev-pill__content">{tab.label}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>

                <div className="ld-prev-surface">
                  <AnimatePresence mode="wait" initial={false} custom={weekDir}>
                    <motion.div
                      key={weekFilter}
                      custom={weekDir}
                      className="ld-prev-week-panel"
                      variants={panelVariants}
                      initial="enter"
                      animate="center"
                      exit="exit"
                    >
                      <div className="ld-prev-week-head" aria-hidden="true">
                        {weekColumns.map((col) => (
                          <span key={col.day}>{col.day}</span>
                        ))}
                      </div>

                      <div className="ld-prev-week-grid">
                        {weekColumns.map((col, colIdx) => (
                          <div key={`${col.day}-am`} className="ld-prev-week-col">
                            {col.am ? (
                              <motion.div
                                className={`ld-prev-course ld-prev-course--${col.am.tone} ld-prev-course--${col.am.span}`}
                                style={{ borderRadius: 10 }}
                                initial={
                                  reduceMotion ? false : { opacity: 0, y: 8, scale: 0.97 }
                                }
                                animate={{ opacity: 1, y: 0, scale: 1 }}
                                transition={
                                  reduceMotion
                                    ? { duration: 0 }
                                    : { ...STEP_IN, delay: stagger(colIdx) }
                                }
                              >
                                <b>{col.am.title}</b>
                                <small>{col.am.room}</small>
                              </motion.div>
                            ) : (
                              <div className="ld-prev-course ld-prev-course--empty" />
                            )}
                          </div>
                        ))}
                      </div>

                      <div className="ld-prev-lunch">
                        12:35–14:00 午休留白（绝不跨午休合并色块）
                      </div>

                      <div className="ld-prev-week-grid">
                        {weekColumns.map((col, colIdx) => (
                          <div key={`${col.day}-pm`} className="ld-prev-week-col">
                            {col.pm ? (
                              <motion.div
                                className={`ld-prev-course ld-prev-course--${col.pm.tone} ld-prev-course--${col.pm.span}`}
                                style={{ borderRadius: 10 }}
                                initial={
                                  reduceMotion ? false : { opacity: 0, y: 8, scale: 0.97 }
                                }
                                animate={{ opacity: 1, y: 0, scale: 1 }}
                                transition={
                                  reduceMotion
                                    ? { duration: 0 }
                                    : { ...STEP_IN, delay: stagger(colIdx + 2) }
                                }
                              >
                                <b>{col.pm.title}</b>
                                <small>{col.pm.room}</small>
                              </motion.div>
                            ) : (
                              <div className="ld-prev-course ld-prev-course--empty" />
                            )}
                          </div>
                        ))}
                      </div>
                    </motion.div>
                  </AnimatePresence>
                </div>
              </div>
            </motion.article>

            {/* Card 4: Deadlines Check-off, Exam Seat & Weighted GPA */}
            <motion.article
              className="ld-fcard ld-fcard--split ld-fcard--reverse"
              {...revealProps}
            >
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">作业截止排队、考场座次与绩点估算</h3>
                <p className="ld-fcard__body">
                  汇聚 MySTU 与雨课堂作业截止提醒，按“今天截止 / 3 天内”自动排队；点击即可本地勾选完成，不会改动学校后台。期末考场座号与本地加权绩点同屏呈现，边界清晰诚实。
                </p>
                <p className="ld-fcard__meta">
                  点击右侧作业条目可直接体验本地勾选 · 绩点明确标注非官方估算
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--academic"
                aria-label="作业截止与考场绩点预览"
              >
                <div className="ld-prev-surface">
                  <div className="ld-prev-bar">
                    <span className="ld-prev-label">待交作业排队</span>
                    <motion.span
                      key={doneCount}
                      className="ld-prev-tag ld-prev-tag--strong"
                      style={{ borderRadius: 9999 }}
                      initial={
                        toggledTaskId !== null && !reduceMotion
                          ? { scale: 0.84, y: -2 }
                          : false
                      }
                      animate={{ scale: 1, y: 0 }}
                      transition={TAP}
                    >
                      已完成 {doneCount}/{DEMO_TASKS.length} · 待交 {remainingTasks} 项
                    </motion.span>
                  </div>

                  <div className="ld-prev-tasks">
                    {DEMO_TASKS.map((task) => {
                      const done = doneTaskIds.includes(task.id);
                      const shouldPopCheck =
                        toggledTaskId === task.id && !reduceMotion;
                      return (
                        <motion.button
                          key={task.id}
                          type="button"
                          className="ld-prev-task"
                          style={{ borderRadius: 10 }}
                          aria-pressed={done}
                          onClick={() => toggleTask(task.id)}
                          whileTap={reduceMotion ? undefined : PRESS}
                          transition={TAP}
                        >
                          <span className="ld-prev-task__main">
                            <motion.span
                              key={`${task.id}-${done ? "done" : "todo"}`}
                              className="ld-prev-check"
                              style={{ borderRadius: 9999 }}
                              initial={shouldPopCheck ? { scale: 0.68 } : false}
                              animate={{ scale: 1 }}
                              transition={TAP}
                              aria-hidden="true"
                            >
                              <Icon name="check" size={13} strokeWidth={2.4} />
                            </motion.span>
                            <span className="ld-prev-task__copy">
                              <span className="ld-prev-task__title">{task.title}</span>
                              <span className="ld-prev-task__sub">
                                {task.source} · {done ? "已在本机标记完成" : "点击标记已交"}
                              </span>
                            </span>
                          </span>
                          <span
                            className={`ld-prev-tag ${
                              !done && task.due.startsWith("今天")
                                ? "ld-prev-tag--strong"
                                : "ld-prev-tag--white"
                            }`}
                          >
                            {done ? "已完成" : task.due}
                          </span>
                        </motion.button>
                      );
                    })}
                  </div>
                </div>

                <div className="ld-prev-stats">
                  <div className="ld-prev-stat">
                    <div className="ld-prev-bar">
                      <span className="ld-prev-muted">期末考场与座次</span>
                      <span className="ld-prev-tag">第 19 周</span>
                    </div>
                    <span className="ld-prev-label">生物统计学 · 闭卷笔试</span>
                    <span className="ld-prev-muted">桑浦山 E座302 · 021座</span>
                  </div>

                  <div className="ld-prev-stat">
                    <div className="ld-prev-bar">
                      <span className="ld-prev-muted">加权平均绩点</span>
                      <span className="ld-prev-tag ld-prev-tag--strong">非官方估算</span>
                    </div>
                    <span className="ld-prev-stat__value">3.82</span>
                    <span className="ld-prev-muted">已计 64.5 学分 · 设备本地测算</span>
                  </div>
                </div>
              </div>
            </motion.article>

            {/* Card 5: Campus Live Bus, 14 Verified Charging Stations & Library */}
            <motion.article className="ld-fcard ld-fcard--split" {...revealProps}>
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">桑浦山校车时刻、14 台核验充电桩与图书馆</h3>
                <p className="ld-fcard__body">
                  出门前瞄一眼 39 路、K3 与 181B 校门发车到站状态；校园充电桩仅收录实地核验无误的 14 台设备，空闲插座与离线状态坦诚区分，并附上桑浦山图书馆每日开放时段。
                </p>
                <p className="ld-fcard__meta">
                  覆盖思源书院、图书馆、敬一书院等 14 台核验桩点 · 离线不虚标空闲
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--campus"
                aria-label="校园巴士、充电桩与图书馆预览"
              >
                <div className="ld-prev-bar">
                  <div className="ld-prev-pills" role="group" aria-label="切换巴士或充电桩">
                    <button
                      type="button"
                      className="ld-prev-pill"
                      aria-pressed={campusTab === "bus"}
                      onClick={() => selectCampusTab("bus")}
                    >
                      {campusTab === "bus" && (
                        <motion.span
                          layoutId="ld-prev-campus-pill"
                          className="ld-prev-active-pill"
                          style={{ borderRadius: 9999 }}
                          transition={pillTransition}
                          aria-hidden="true"
                        />
                      )}
                      <span className="ld-prev-pill__content">
                        <Icon name="bus" size={15} />
                        <span>实时巴士</span>
                      </span>
                    </button>
                    <button
                      type="button"
                      className="ld-prev-pill"
                      aria-pressed={campusTab === "charger"}
                      onClick={() => selectCampusTab("charger")}
                    >
                      {campusTab === "charger" && (
                        <motion.span
                          layoutId="ld-prev-campus-pill"
                          className="ld-prev-active-pill"
                          style={{ borderRadius: 9999 }}
                          transition={pillTransition}
                          aria-hidden="true"
                        />
                      )}
                      <span className="ld-prev-pill__content">
                        <Icon name="bolt" size={15} />
                        <span>充电桩插座 (14)</span>
                      </span>
                    </button>
                  </div>
                  <span className="ld-prev-muted">桑浦山校区实况</span>
                </div>

                <div className="ld-prev-surface">
                  <AnimatePresence mode="wait" initial={false} custom={campusDir}>
                    <motion.div
                      key={campusTab}
                      custom={campusDir}
                      className="ld-prev-rows"
                      variants={panelVariants}
                      initial="enter"
                      animate="center"
                      exit="exit"
                    >
                      {campusRows.map((row, idx) => (
                        <motion.div
                          key={row.title}
                          className="ld-prev-row"
                          style={{ borderRadius: 10 }}
                          initial={reduceMotion ? false : { opacity: 0, y: 8, scale: 0.99 }}
                          animate={{ opacity: 1, y: 0, scale: 1 }}
                          transition={
                            reduceMotion
                              ? { duration: 0 }
                              : { ...STEP_IN, delay: stagger(idx) }
                          }
                        >
                          <div className="ld-prev-row__main">
                            <span className="ld-prev-row__icon" aria-hidden="true">
                              <Icon name={row.icon} size={17} />
                            </span>
                            <div className="ld-prev-row__text">
                              <b>{row.title}</b>
                              <small>{row.sub}</small>
                            </div>
                          </div>
                          <span
                            className={`ld-prev-tag ${
                              row.strong ? "ld-prev-tag--strong" : "ld-prev-tag--white"
                            }`}
                          >
                            {row.tag}
                          </span>
                        </motion.div>
                      ))}
                    </motion.div>
                  </AnimatePresence>
                </div>

                <div className="ld-prev-row ld-prev-row--card">
                  <div className="ld-prev-row__main">
                    <span className="ld-prev-row__icon" aria-hidden="true">
                      <Icon name="book" size={17} />
                    </span>
                    <div className="ld-prev-row__text">
                      <b>桑浦山图书馆</b>
                      <small>今日开放时段 08:00–22:00</small>
                    </div>
                  </div>
                  <span className="ld-prev-tag">正常开馆</span>
                </div>
              </div>
            </motion.article>

            {/* Card 6 (#privacy): Privacy & Read-Only Commitment */}
            <motion.article
              id="privacy"
              className="ld-fcard ld-fcard--split ld-fcard--reverse"
              {...revealProps}
            >
              <div className="ld-fcard__copy">
                <h3 className="ld-fcard__title">隐私只属于你，全程只读绝不代办</h3>
                <p className="ld-fcard__body">
                  绑定时仅由你本人一次性通过学校统一身份认证，成功后只保留可随时吊销的加密会话，不保存校园网明文密码。助手严格保持只读，绝不替你向学校系统提交任何写操作。
                </p>
                <p className="ld-fcard__meta">
                  本人授权 · 只读不代办 · 随时一键清除账号与缓存
                </p>
              </div>

              <div
                className="ld-subcard ld-prev ld-prev--privacy"
                aria-label="隐私与只读安全边界"
              >
                <div className="ld-prev-rows">
                  <div className="ld-prev-row ld-prev-row--card">
                    <div className="ld-prev-row__main">
                      <span className="ld-prev-row__icon" aria-hidden="true">
                        <Icon name="shield" size={18} />
                      </span>
                      <div className="ld-prev-row__text">
                        <b>本人授权一次性认证</b>
                        <small>连接时直通学校统一认证，只存加密会话，密码绝不落盘。</small>
                      </div>
                    </div>
                    <span className="ld-prev-tag ld-prev-tag--strong">本人授权</span>
                  </div>

                  <div className="ld-prev-row ld-prev-row--card">
                    <div className="ld-prev-row__main">
                      <span className="ld-prev-row__icon" aria-hidden="true">
                        <Icon name="check" size={18} />
                      </span>
                      <div className="ld-prev-row__text">
                        <b>只读不代办</b>
                        <small>不签到 · 不交作业 · 不选课 · 不缴费，勾选仅记在本机。</small>
                      </div>
                    </div>
                    <span className="ld-prev-tag">只读边界</span>
                  </div>

                  <div className="ld-prev-row ld-prev-row--card">
                    <div className="ld-prev-row__main">
                      <span className="ld-prev-row__icon" aria-hidden="true">
                        <Icon name="trash" size={18} />
                      </span>
                      <div className="ld-prev-row__text">
                        <b>一键清除</b>
                        <small>随时解绑或注销账号，立即清空服务器上的会话令牌与同步缓存。</small>
                      </div>
                    </div>
                    <span className="ld-prev-tag">即时销毁</span>
                  </div>
                </div>
              </div>
            </motion.article>
          </div>
        </div>
      </section>

      {/* ================================================================
          Section 3: #download
          ================================================================ */}
      <section id="download" className="ld-section" aria-labelledby="ld-download-title">
        <div className="ld-container">
          <motion.div className="ld-section__intro" {...revealProps}>
            <span className="ld-eyebrow">GET STARTED</span>
            <h2 id="ld-download-title" className="ld-section__title">
              装进手机，或直接在浏览器开始。
            </h2>
            <p className="ld-section__lead">
              Android 安装包与全平台网页版共用同一套极简界面与账号同步；iPhone 与桌面浏览器打开即用。
            </p>
          </motion.div>

          <div className="ld-grid ld-grid--pair">
            {/* Left Pair Card: Android APK & Full-Platform Web */}
            <motion.article className="ld-fcard ld-download-card" {...revealProps}>
              <div className="ld-download-card__body">
                <div className="ld-fcard__copy">
                  <h3 className="ld-fcard__title">Android 客户端与全平台网页版</h3>
                  <p className="ld-fcard__body">
                    安卓手机可直接安装独立客户端，获得状态栏沉浸适配与离线秒开体验；iOS、iPadOS 与电脑浏览器直接访问网页版，也可免登录先看示例课表。
                  </p>
                </div>

                <div className="ld-subcard ld-prev">
                  <div className="ld-prev-row ld-prev-row--card">
                    <div className="ld-prev-row__main">
                      <span className="ld-prev-row__icon" aria-hidden="true">
                        <Icon name="monitor" size={17} />
                      </span>
                      <div className="ld-prev-row__text">
                        <b>跨设备同账号同步 · v{versionName}</b>
                        <small>Android 原生包 · Safari / Chrome 添加到主屏幕</small>
                      </div>
                    </div>
                    <span className="ld-prev-tag">免费无广告</span>
                  </div>
                </div>
              </div>

              <div className="ld-prev-actions">
                {!isNative ? (
                  <motion.a
                    className="ld-btn ld-btn--outline"
                    href={apkUrl}
                    download
                    style={{ borderRadius: 9999 }}
                    whileHover={reduceMotion ? undefined : { y: -1.5 }}
                    whileTap={reduceMotion ? undefined : PRESS}
                    transition={TAP}
                  >
                    <span>下载 Android 安装包</span>
                    <span className="ld-btn__badge">v{versionName}</span>
                  </motion.a>
                ) : (
                  <motion.button
                    type="button"
                    className="ld-btn ld-btn--outline"
                    onClick={onStart}
                    style={{ borderRadius: 9999 }}
                    whileHover={reduceMotion ? undefined : { y: -1.5 }}
                    whileTap={reduceMotion ? undefined : PRESS}
                    transition={TAP}
                  >
                    <span>使用校园账号登录</span>
                    <span className="ld-btn__badge">v{versionName}</span>
                  </motion.button>
                )}

                <motion.button
                  type="button"
                  className="ld-btn ld-btn--outline"
                  onClick={onPreview ?? onStart}
                  style={{ borderRadius: 9999 }}
                  whileHover={reduceMotion ? undefined : { y: -1.5 }}
                  whileTap={reduceMotion ? undefined : PRESS}
                  transition={TAP}
                >
                  <span>{onPreview ? "免登录浏览网页版" : "直接在网页版开始"}</span>
                  <span className="ld-btn__badge">Web</span>
                </motion.button>
              </div>
            </motion.article>

            {/* Right Pair Card: Campus Feedback Group QR */}
            <motion.article className="ld-fcard ld-download-card" {...revealProps}>
              <div className="ld-download-card__body">
                <div className="ld-fcard__copy">
                  <h3 className="ld-fcard__title">加入校园反馈群</h3>
                  <p className="ld-fcard__body">
                    遇到课表周次特例、某台充电桩插座编号变动，或者有想加进助手的校园小点子，欢迎随时进群告诉我们。
                  </p>
                </div>

                <div className="ld-subcard ld-prev">
                  <div className="ld-prev-qr">
                    <div className="ld-prev-qr__frame">
                      <img
                        className="ld-prev-qr__img"
                        src="./feedback-group.png"
                        alt="STU 事项助手反馈群二维码"
                        width={88}
                        height={88}
                      />
                    </div>
                    <div className="ld-prev-qr__copy">
                      <b>STU 事项助手交流反馈群</b>
                      <small>
                        日常问题解答 · 版本更新预告 · 桑浦山与东海岸同学共建交流
                      </small>
                    </div>
                  </div>
                </div>
              </div>

              <div className="ld-prev-actions">
                <motion.button
                  type="button"
                  className="ld-btn ld-btn--outline"
                  onClick={onOpenQr}
                  style={{ borderRadius: 9999 }}
                  whileHover={reduceMotion ? undefined : { y: -1.5 }}
                  whileTap={reduceMotion ? undefined : PRESS}
                  transition={TAP}
                >
                  <span>放大查看二维码</span>
                  <span className="ld-btn__badge">扫码</span>
                </motion.button>
              </div>
            </motion.article>
          </div>
        </div>
      </section>
    </>
  );
}
