import { useCallback, useEffect, useMemo, useState, useTransition } from "react";
import { AnimatePresence, MotionConfig, motion } from "motion/react";
import { SideNav, TabBar, TABS, type TabKey } from "./components/TabBar";
import { TodayScreen } from "./screens/Today";
import { WeekScreen } from "./screens/Week";
import { StudyScreen } from "./screens/Study";
import { CampusScreen } from "./screens/Campus";
import { MeScreen } from "./screens/Me";
import { Welcome, type Step } from "./screens/Welcome";
import { UpdateBanner } from "./components/UpdateBanner";
import { useTheme } from "./lib/useTheme";
import { AUTH_LOST, getToken, setToken } from "./lib/api";
import { pageMotion } from "./lib/motion";
import { initNative, onBack } from "./lib/native";

import { PREVIEW_KEY, ShellCtx, StoreProvider, useStore } from "./lib/store";
import { dueBucket } from "./lib/records";

const KEYS = TABS.map((t) => t.key);

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
  const [tab, setTab] = useState<TabKey>(initialTab);
  const [, startTab] = useTransition();
  const store = useStore();

  /**
   * 切页之所以会"一卡一卡"：新页面是一次重渲染，如果同步做，动画第一帧就被
   * 渲染任务挤掉了。放进 startTransition，React 会让动画先走、列表慢慢补上，
   * 手指看到的就是丝滑的位移，而不是先定住再跳。
   */
  const go = useCallback((next: TabKey) => {
    if (next === tab) return;
    startTab(() => setTab(next));
  }, [tab]);

  // a tab is a fresh context, not a continuation
  useEffect(() => {
    window.scrollTo({ top: 0, behavior: "instant" as ScrollBehavior });
  }, [tab]);

  // Android back: any other tab steps back to 今天 before leaving the App
  useEffect(
    () =>
      onBack(() => {
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
          popLayout 而不是 wait：切页时旧页面立刻退场（120ms 纯透明度），
          新页面同时用果冻弹簧落位。wait 会先等旧页退干净再让新页进，
          中间那一下空白就是手感"卡"的来源之一。
        */}
        <AnimatePresence mode="popLayout" initial={false}>
          <motion.main
            key={tab}
            id="main"
            className={`page page--${tab}`}
            initial={pageMotion.initial}
            animate={pageMotion.animate}
            exit={pageMotion.exit}
          >
            {tab === "today" && <TodayScreen />}
            {tab === "week" && <WeekScreen />}
            {tab === "study" && <StudyScreen />}
            {tab === "campus" && <CampusScreen />}
            {tab === "me" && <MeScreen onSignOut={onSignOut} />}
          </motion.main>
        </AnimatePresence>
        <TabBar current={tab} onChange={go} badges={badges} />
      </div>
    </ShellCtx.Provider>
  );
}
