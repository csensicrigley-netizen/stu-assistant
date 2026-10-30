import { lazy, Suspense, useCallback, useEffect, useMemo, useState, useTransition } from "react";
import { AnimatePresence, MotionConfig, motion } from "motion/react";
import { SideNav, TabBar, TABS, type TabKey } from "./components/TabBar";
import { TodayScreen } from "./screens/Today";
/*
 * 按页拆包：今天之外的四页都不是打开就要看的，跟着主包一起下等于让首屏
 * 多背几十 KB 的解析时间。首帧之后立刻预取（见下面的 preload），所以点标签
 * 时它们已经在缓存里，平时看不到切换等待。
 */
const PlanScreen = lazy(() => import("./screens/Plan").then((m) => ({ default: m.PlanScreen })));
const WeekScreen = lazy(() => import("./screens/Week").then((m) => ({ default: m.WeekScreen })));
const StudyScreen = lazy(() => import("./screens/Study").then((m) => ({ default: m.StudyScreen })));
const CampusScreen = lazy(() => import("./screens/Campus").then((m) => ({ default: m.CampusScreen })));
const MeScreen = lazy(() => import("./screens/Me").then((m) => ({ default: m.MeScreen })));
const ImportScreen = lazy(() => import("./screens/Import").then((m) => ({ default: m.ImportScreen })));
const MedBindScreen = lazy(() => import("./screens/MedBind").then((m) => ({ default: m.MedBindScreen })));
import { Welcome, type Step } from "./screens/Welcome";
import { UpdateBanner } from "./components/UpdateBanner";
import { useTheme } from "./lib/useTheme";
import { AUTH_LOST, getToken, setToken } from "./lib/api";
import { pageVariants } from "./lib/motion";
import { initNative, onBack } from "./lib/native";

import { PREVIEW_KEY, ShellCtx, StoreProvider, useStore } from "./lib/store";
import { dueBucket } from "./lib/records";

const KEYS = TABS.map((t) => t.key);

/**
 * 每张页面在"横向空间"里的位置。切页的方向感就是从这里来的：
 * 往右走（下标变大）新页从右边进来，往左走反过来。
 * import / medbind 都是课表的子页面，夹在 week 与 study 之间 —— 进它们算"往里走"，
 * 而且两者互不重叠（2.4 / 2.6），从「导入」跳「连教务」也是一个方向感的位移。
 */
const ORDER: Record<TabKey, number> = {
  today: 0,
  plan: 1,
  week: 2,
  import: 2.4,
  medbind: 2.6,
  study: 3,
  campus: 4,
  me: 5,
};

function dirOf(from: TabKey, to: TabKey): 1 | -1 {
  return ORDER[to] >= ORDER[from] ? 1 : -1;
}

function initialTab(): TabKey {
  try {
    const asked = new URLSearchParams(location.search).get("tab") as TabKey | null;
    if (asked && KEYS.includes(asked)) return asked;
  } catch {
    /* no location */
  }
  return "today";
}

function hasPreviewFlag(): boolean {
  try {
    return new URLSearchParams(location.search).has("preview") || sessionStorage.getItem(PREVIEW_KEY) === "1";
  } catch {
    return false;
  }
}

function setPreviewFlag(on: boolean) {
  try {
    if (on) sessionStorage.setItem(PREVIEW_KEY, "1");
    else sessionStorage.removeItem(PREVIEW_KEY);
  } catch {
    /* ignore */
  }
}

type Gate = { kind: "welcome"; step?: Step; notice?: string } | { kind: "app" };

export default function App() {
  useTheme();
  const [gate, setGate] = useState<Gate>(() => (getToken() || hasPreviewFlag() ? { kind: "app" } : { kind: "welcome" }));
  const [epoch, setEpoch] = useState(0);

  useEffect(() => initNative(), []);

  // the server rejected our token (expired, or the account was deleted)
  useEffect(() => {
    const onLost = () => {
      setToken(null);
      setPreviewFlag(false);
      setEpoch((n) => n + 1);
      setGate({ kind: "welcome", step: "account", notice: "登录已过期，请重新登录。" });
    };
    window.addEventListener(AUTH_LOST, onLost);
    return () => window.removeEventListener(AUTH_LOST, onLost);
  }, []);

  const enterApp = useCallback(() => {
    setPreviewFlag(false);
    setEpoch((n) => n + 1);
    setGate({ kind: "app" });
  }, []);

  if (gate.kind === "welcome") {
    return (
      <MotionConfig reducedMotion="user">
        <Welcome
          start={gate.step}
          notice={gate.notice}
          onPreview={() => {
            setPreviewFlag(true);
            setGate({ kind: "app" });
          }}
          onDone={enterApp}
          onCancel={getToken() || hasPreviewFlag() ? () => setGate({ kind: "app" }) : undefined}
        />
        {/* 还没登录也要能提醒更新：装的是旧包，登录流程本身可能就是坏的 */}
        <UpdateBanner />
      </MotionConfig>
    );
  }

  return (
    <MotionConfig reducedMotion="user">
      <StoreProvider key={epoch}>
        <Shell
          onSignOut={() => {
            setPreviewFlag(false);
            setEpoch((n) => n + 1);
            setGate({ kind: "welcome" });
          }}
          onConnect={() => setGate({ kind: "welcome", step: getToken() ? "bind" : "account" })}
        />
        <UpdateBanner />
      </StoreProvider>
    </MotionConfig>
  );
}

function Shell({ onSignOut, onConnect }: { onSignOut: () => void; onConnect: () => void }) {
  /*
   * 页面与方向放在同一份 state 里：setTab 与 dir 必须同一次提交，
   * 否则退场那一帧会拿到上一次的方向，旧页会朝"进来的方向"退出去 —— 看起来像倒放。
   */
  const [nav, setNav] = useState<{ tab: TabKey; dir: 1 | -1 }>(() => ({ tab: initialTab(), dir: 1 }));
  const tab = nav.tab;
  const [, startTab] = useTransition();
  const store = useStore();

  /**
   * 切页之所以会"一卡一卡"：新页面是一次重渲染，如果同步做，动画第一帧就被
   * 渲染任务挤掉了。放进 startTransition，React 会让动画先走、列表慢慢补上，
   * 手指看到的就是丝滑的位移，而不是先定住再跳。
   */
  // campus 需要能带子视图：首页"查看校历"要落在校历那一栏，而不是通知列表
  // （审查报告第 30 项）。sub 只在跳 campus 时有意义。
  const [campusView, setCampusView] = useState<string | undefined>(undefined);
  const go = useCallback(
    (next: TabKey, sub?: string) => {
      if (sub && next === "campus") setCampusView(sub);
      // Resolve against queued navigation too: rapid native selections can arrive
      // before the previous transition commits to the Web view.
      startTab(() => setNav((prev) => next === prev.tab ? prev : { tab: next, dir: dirOf(prev.tab, next) }));
    },
    [startTab],
  );

  // a tab is a fresh context, not a continuation
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" as ScrollBehavior });
  }, [tab]);

  /*
   * 首帧画完之后再把其余页面的 chunk 拉下来。放在 setTimeout 里而不是立刻
   * import()，是为了不跟首屏抢网络和主线程：首屏先出来，剩下的趁人还在看
   * 今天这一页的时候下完，点标签就不用等。
   */
  useEffect(() => {
    const id = window.setTimeout(() => {
      void import("./screens/Plan");
      void import("./screens/Week");
      void import("./screens/Study");
      void import("./screens/Campus");
      void import("./screens/Me");
      void import("./screens/Import");
      void import("./screens/MedBind");
    }, 240);
    return () => window.clearTimeout(id);
  }, []);

  // Android back: 子页面先回它的来处，其余页面回"今天"，只有"今天"才退出 App
  useEffect(
    () =>
      onBack(() => {
        if (tab === "import" || tab === "medbind") {
          go("week");
          return true;
        }
        if (tab === "today") return false;
        go("today");
        return true;
      }),
    [tab, go],
  );

  const urgent = store.tasks.filter((t) => {
    if (store.done.has(t.id)) return false;
    const b = dueBucket(t);
    return b === "today" || b === "overdue";
  }).length;
  const badges = { study: urgent, me: store.needsReconnect.length || undefined };

  const shell = useMemo(() => ({ go, connect: onConnect }), [go, onConnect]);

  return (
    <ShellCtx.Provider value={shell}>
      <div className="app">
        <a className="skip" href="#main">
          跳到主要内容
        </a>
        <SideNav current={tab} onChange={go} badges={badges} />
        {/*
          popLayout 而不是 wait：切页时旧页面立刻退场（120ms 纯透明度 + 14px 反向位移），
          新页面同时用弹簧从它那一侧落位。wait 会先等旧页退干净再让新页进，
          中间那一下空白就是手感"卡"的来源之一。

          popLayout 还会把退场中的那一页从文档流里摘出去（position: absolute），
          所以旧页不再参与布局计算，不会跟新页抢高度 —— 下面这个 key 变化时
          页面高度变化再大，也不会看到"先塌一下再撑开"。

          custom={nav.dir} 同时交给 AnimatePresence 与页面：退场动画用的是
          "这一次切换的方向"，而不是旧页自己上次的方向。
        */}
        <AnimatePresence mode="popLayout" initial={false} custom={nav.dir}>
          <motion.main
            key={tab}
            id="main"
            className={`page page--${tab}`}
            custom={nav.dir}
            variants={pageVariants}
            initial="enter"
            animate="center"
            exit="exit"
          >
            {/*
              Suspense 放在 motion.main 里面：这样页面容器的弹簧照旧跑，
              只是内容晚一两帧到。fallback 用等高骨架，避免落地时的高度跳变
              ——跳变会被当成"卡"，比空白更难受。
            */}
            <Suspense
              fallback={
                <div className="stack-3" aria-hidden="true">
                  <div className="sk" style={{ height: "18vh", borderRadius: "var(--r-xl)" }} />
                  <div className="sk" style={{ height: "34vh", borderRadius: "var(--r-xl)" }} />
                </div>
              }
            >
              {tab === "today" && <TodayScreen />}
              {tab === "plan" && <PlanScreen />}
              {tab === "week" && <WeekScreen />}
              {tab === "import" && <ImportScreen />}
              {tab === "medbind" && <MedBindScreen />}
              {tab === "study" && <StudyScreen />}
              {tab === "campus" && <CampusScreen initialView={campusView} />}
              {tab === "me" && <MeScreen onSignOut={onSignOut} />}
            </Suspense>
          </motion.main>
        </AnimatePresence>
        <TabBar current={tab} onChange={go} badges={badges} />
      </div>
    </ShellCtx.Provider>
  );
}
