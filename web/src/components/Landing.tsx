import { useEffect, useState, type CSSProperties } from "react";
import {
  AnimatePresence,
  motion,
  useReducedMotion,
  type Transition,
} from "motion/react";
import { IS_NATIVE } from "../lib/api";
import {
  JELLY_SOFT,
  OUT,
  PILL,
  PRESS,
  SPRING,
  TAP,
  stagger,
} from "../lib/motion";
import { APP_VERSION_NAME, apkHref } from "../lib/version";
import { Icon } from "./Icon";
import { LandingGallery } from "./LandingGallery";
import { LandingHeroWindow, type HeroMode } from "./LandingHeroWindow";
import { Logo } from "./Logo";

const HERO_MODES: ReadonlyArray<{
  mode: HeroMode;
  label: string;
  dotColor: string;
}> = [
  { mode: "today", label: "今日课堂", dotColor: "#ec4899" },
  { mode: "week", label: "一周光谱", dotColor: "#14b8a6" },
  { mode: "campus", label: "校园生活", dotColor: "#f59e0b" },
];

function heroEntrance(index: number, y: number, reduceMotion: boolean) {
  if (reduceMotion) {
    return {
      initial: false as const,
      animate: { opacity: 1, y: 0, scale: 1 },
    };
  }
  const delay = stagger(index);
  const transition: Transition = {
    ...JELLY_SOFT,
    delay,
    restDelta: 0.005,
    opacity: { duration: 0.22, ease: [0.22, 1, 0.36, 1], delay },
  };
  return {
    initial: { opacity: 0, y, scale: 0.99 },
    animate: { opacity: 1, y: 0, scale: 1 },
    transition,
  };
}

export function Landing({
  onStart,
  onPreview,
}: {
  onStart: () => void;
  onPreview?: () => void;
}) {
  const [heroMode, setHeroMode] = useState<HeroMode>("today");
  const [announceVisible, setAnnounceVisible] = useState(true);
  const [qrOpen, setQrOpen] = useState(false);

  const motionReduced = useReducedMotion();
  const [mediaReduced, setMediaReduced] = useState(
    () =>
      typeof window !== "undefined" &&
      window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  );

  useEffect(() => {
    const mq = window.matchMedia("(prefers-reduced-motion: reduce)");
    const onChange = () => setMediaReduced(mq.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, []);

  const reduceMotion = Boolean(motionReduced) || mediaReduced;

  const apkUrl = apkHref("/download/stu-assistant.apk");

  /* Keep browser chrome pure white while Landing is mounted; restore user theme on unmount */
  useEffect(() => {
    const meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) return;
    let prev = meta.getAttribute("content");
    if (prev !== "#ffffff") {
      meta.setAttribute("content", "#ffffff");
    }
    const obs = new MutationObserver(() => {
      const cur = meta.getAttribute("content");
      if (cur !== "#ffffff") {
        if (cur !== null) prev = cur;
        meta.setAttribute("content", "#ffffff");
      }
    });
    obs.observe(meta, { attributes: true, attributeFilter: ["content"] });
    return () => {
      obs.disconnect();
      if (prev !== null && prev !== "#ffffff") {
        meta.setAttribute("content", prev);
      }
    };
  }, []);

  /* Dismiss QR modal on Escape */
  useEffect(() => {
    if (!qrOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setQrOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [qrOpen]);

  const ctaHover = reduceMotion ? undefined : { y: -1.5, transition: SPRING };
  const ctaTap = reduceMotion ? undefined : { ...PRESS, transition: TAP };

  return (
    <div className="ld" id="top">
      <header>
        <motion.nav
          className="ld-nav"
          aria-label="主要导航"
          {...heroEntrance(0, 6, reduceMotion)}
        >
          <a className="ld-nav__brand" href="#top">
            <Logo size={26} tone="light" className="ld-nav__logo" />
            <span className="ld-nav__wordmark">STU 助手</span>
          </a>

          <div className="ld-nav__pills" role="group" aria-label="切换窗口预览视图">
            {HERO_MODES.map((item) => {
              const active = heroMode === item.mode;
              return (
                <button
                  key={item.mode}
                  type="button"
                  className={`ld-nav__pill${active ? " is-active" : ""}`}
                  aria-pressed={active}
                  onClick={() => setHeroMode(item.mode)}
                >
                  {active && (
                    <motion.span
                      className="ld-nav__pill-bg"
                      layoutId="ld-nav-mode-pill"
                      transition={PILL}
                      aria-hidden="true"
                    />
                  )}
                  <motion.span
                    className="ld-nav__dot"
                    style={{ "--dot-color": item.dotColor } as CSSProperties}
                    initial={false}
                    animate={
                      reduceMotion ? undefined : { scale: active ? 1.16 : 1 }
                    }
                    transition={SPRING}
                    aria-hidden="true"
                  />
                  <span className="ld-nav__pill-label">{item.label}</span>
                </button>
              );
            })}
          </div>

          <div className="ld-nav__links">
            <a className="ld-nav__link ld-nav__link--hideSm" href="#theming">
              外观
            </a>
            <a className="ld-nav__link" href="#features">
              功能
            </a>
            <a className="ld-nav__link" href="#privacy">
              隐私
            </a>
            <button
              type="button"
              className="ld-nav__link"
              onClick={() => setQrOpen(true)}
            >
              反馈群
            </button>
          </div>
        </motion.nav>
      </header>

      <main className="ld-main">
        <section className="ld-hero" aria-labelledby="ld-hero-display">
          <AnimatePresence initial={!reduceMotion}>
            {announceVisible && (
              <motion.div
                key="ld-announce"
                className="ld-announce"
                role="region"
                aria-label="版本更新公告"
                {...heroEntrance(1, 8, reduceMotion)}
                exit={{ opacity: 0, y: -8, scale: 0.96, transition: OUT }}
                whileHover={
                  reduceMotion ? undefined : { y: -1, transition: SPRING }
                }
              >
                <span className="ld-announce__icon" aria-hidden="true">
                  <Icon name="spark" size={16} />
                </span>
                <span className="ld-announce__text">
                  <strong>STU 助手 v{APP_VERSION_NAME} 已发布</strong> — 六套校园色系与医学院教务自动同步
                </span>
                <motion.button
                  type="button"
                  className="ld-announce__dismiss"
                  whileTap={reduceMotion ? undefined : PRESS}
                  transition={TAP}
                  onClick={() => setAnnounceVisible(false)}
                  aria-label="关闭版本公告"
                >
                  <Icon name="close" size={14} />
                </motion.button>
              </motion.div>
            )}
          </AnimatePresence>

          <motion.div
            className="ld-hero__stage"
            {...heroEntrance(2, 10, reduceMotion)}
          >
            <LandingHeroWindow activeMode={heroMode} onSelectMode={setHeroMode} />
          </motion.div>

          <motion.h1
            id="ld-hero-display"
            className="ld-display"
            {...heroEntrance(3, 10, reduceMotion)}
          >
            STU 助手
          </motion.h1>

          <motion.h2
            className="ld-hero__headline"
            {...heroEntrance(4, 10, reduceMotion)}
          >
            下一节课在哪，作业几点截止。
          </motion.h2>

          <motion.p
            className="ld-hero__sub"
            {...heroEntrance(5, 8, reduceMotion)}
          >
            教务课表、MySTU 作业、实时校车与充电桩收在一处，按教务处官方作息算准每一节课。
          </motion.p>

          <motion.div
            className="ld-hero__actions"
            {...heroEntrance(6, 8, reduceMotion)}
          >
            <motion.button
              type="button"
              className="ld-btn ld-btn--primary"
              whileHover={ctaHover}
              whileTap={ctaTap}
              transition={SPRING}
              onClick={onStart}
            >
              开始使用
            </motion.button>

            {!IS_NATIVE && (
              <motion.a
                className="ld-btn ld-btn--outline"
                href={apkUrl}
                download
                whileHover={ctaHover}
                whileTap={ctaTap}
                transition={SPRING}
              >
                <span>下载 Android 客户端</span>
                <span className="ld-btn__badge">v{APP_VERSION_NAME}</span>
              </motion.a>
            )}

            {onPreview && (
              <motion.button
                type="button"
                className="ld-btn ld-btn--outline"
                whileHover={ctaHover}
                whileTap={ctaTap}
                transition={SPRING}
                onClick={onPreview}
              >
                <span>先看看示例</span>
                <span className="ld-btn__badge">免登录</span>
              </motion.button>
            )}
          </motion.div>

          <motion.div
            className="ld-hero__trust"
            {...heroEntrance(7, 6, reduceMotion)}
          >
            <span className="ld-hero__trustItem">
              <Icon name="shield" size={15} />
              <span>密码由本人授权，加密存储随时可清</span>
            </span>
            <span className="ld-hero__trustItem">
              <Icon name="check" size={15} />
              <span>只读不代办：不签到、不交作业、不缴费</span>
            </span>
          </motion.div>
        </section>

        <LandingGallery
          onStart={onStart}
          onPreview={onPreview}
          onOpenQr={() => setQrOpen(true)}
          apkUrl={apkUrl}
          versionName={APP_VERSION_NAME}
          isNative={IS_NATIVE}
        />
      </main>

      <footer className="ld-footer">
        <div className="ld-footer__inner">
          <div className="ld-footer__bar">
            <div className="ld-footer__brand">
              <Logo size={22} tone="light" className="ld-nav__logo" />
              <span>STU 助手</span>
              <span className="ld-footer__version">v{APP_VERSION_NAME}</span>
            </div>

            <div className="ld-footer__links">
              <a
                className="ld-footer__link"
                href="https://jwc.stu.edu.cn/info/1080/1939.htm"
                target="_blank"
                rel="noreferrer noopener"
              >
                <span>课堂作息时间表</span>
                <Icon name="external" size={14} />
              </a>
              <a
                className="ld-footer__link"
                href="https://jwc.stu.edu.cn/info/1080/3307.htm"
                target="_blank"
                rel="noreferrer noopener"
              >
                <span>2026–2027 教学校历</span>
                <Icon name="external" size={14} />
              </a>
              <a
                className="ld-footer__link"
                href="https://github.com/0error0warning/stu-mcp"
                target="_blank"
                rel="noreferrer noopener"
              >
                <span>stu-mcp (MIT)</span>
                <Icon name="external" size={14} />
              </a>
              <button
                type="button"
                className="ld-footer__link"
                onClick={() => setQrOpen(true)}
              >
                <span>加入反馈群</span>
              </button>
            </div>
          </div>

          <p className="ld-footer__note">
            个人项目，与汕头大学官方无关 · 学校通知与教务原文始终优先 · 联合创始人 小爱 · 大象 · FKL
          </p>
        </div>
      </footer>

      <AnimatePresence>
        {qrOpen && (
          <motion.div
            key="ld-qr-modal"
            className="ld-modal"
            role="presentation"
            initial={reduceMotion ? { opacity: 1 } : { opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0, transition: OUT }}
            transition={{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }}
            onClick={() => setQrOpen(false)}
          >
            <motion.div
              className="ld-modal__dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="ld-qr-title"
              initial={
                reduceMotion
                  ? { opacity: 1, scale: 1, y: 0 }
                  : { opacity: 0, scale: 0.94, y: 14 }
              }
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={
                reduceMotion
                  ? { opacity: 0, transition: OUT }
                  : { opacity: 0, scale: 0.96, y: 8, transition: OUT }
              }
              transition={SPRING}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="ld-modal__head">
                <h2 id="ld-qr-title" className="ld-modal__title">
                  加入校园反馈群
                </h2>
                <motion.button
                  type="button"
                  className="ld-modal__close"
                  whileTap={reduceMotion ? undefined : PRESS}
                  transition={TAP}
                  onClick={() => setQrOpen(false)}
                  aria-label="关闭二维码弹窗"
                >
                  <Icon name="close" size={18} />
                </motion.button>
              </div>

              <div className="ld-modal__qrFrame">
                <img
                  className="ld-modal__qr"
                  src="./feedback-group.png"
                  alt="STU 事项助手反馈群二维码"
                />
              </div>

              <p className="ld-modal__caption">
                扫码加入反馈群，交流课表适配、桑浦山校车时刻或新功能建议。
              </p>

              <div className="ld-modal__actions">
                <motion.button
                  type="button"
                  className="ld-btn ld-btn--outline"
                  whileHover={ctaHover}
                  whileTap={ctaTap}
                  transition={SPRING}
                  onClick={() => setQrOpen(false)}
                >
                  关闭
                </motion.button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
