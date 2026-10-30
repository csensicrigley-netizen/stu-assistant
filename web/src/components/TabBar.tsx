import { motion } from "motion/react";
import { useMemo } from "react";
import { Icon, type IconName } from "./Icon";
import { Logo } from "./Logo";
import { ModePill } from "./Layout";
import { PILL } from "../lib/motion";
import { isCancelledInteraction } from "../lib/haptics";
import { useNativeNavigation } from "../lib/native-navigation";

// Import and medical connection are timetable subpages, not extra destinations.
export type TabKey = "today" | "plan" | "week" | "study" | "campus" | "me" | "import" | "medbind";

export const TABS: { key: TabKey; label: string; icon: IconName }[] = [
  { key: "today", label: "今天", icon: "today" },
  { key: "plan", label: "计划", icon: "check" },
  { key: "week", label: "课表", icon: "week" },
  { key: "study", label: "学业", icon: "study" },
  { key: "campus", label: "校园", icon: "campus" },
  { key: "me", label: "我的", icon: "me" },
];

export function navigationTab(current: TabKey): TabKey {
  return current === "import" || current === "medbind" ? "week" : current;
}

/**
 * Bottom navigation on phones and tablets. The indicator sits behind the icon
 * only, so it can never cover a label.
 */
export function TabBar({
  current,
  onChange,
  badges = {},
}: {
  current: TabKey;
  onChange: (key: TabKey) => void;
  badges?: Partial<Record<TabKey, number>>;
}) {
  const active = navigationTab(current);
  const items = useMemo(() => TABS.map((tab) => ({ ...tab, badge: badges[tab.key] })),
    [badges.today, badges.plan, badges.week, badges.study, badges.campus, badges.me]);
  const nativeMounted = useNativeNavigation(active, items, (key) => {
    if (TABS.some((tab) => tab.key === key)) onChange(key as TabKey);
  });
  if (nativeMounted) return null;
  return (
    <nav className="tabbar" aria-label="主导航" data-haptic="selection">
      <div className="tabbar__row">
        {TABS.map((tab) => {
          const lit = tab.key === active;
          const badge = badges[tab.key];
          return (
            <button
              key={tab.key}
              type="button"
              className={`tabbar__btn${lit ? " is-on" : ""}`}
              onClick={(event) => {
                if (!isCancelledInteraction(event.nativeEvent)) onChange(tab.key);
              }}
              aria-current={lit ? "page" : undefined}
              aria-label={badge ? `${tab.label}，${badge} 项待处理` : tab.label}
            >
              <span className="tabbar__icon">
                {lit && <motion.span className="tabbar__pill" layoutId="tab-pill" transition={PILL} />}
                <Icon name={tab.icon} size={22} strokeWidth={lit ? 2 : 1.75} />
                {badge ? (
                  <span className="tabbar__badge" aria-hidden="true">
                    {badge > 9 ? "9+" : badge}
                  </span>
                ) : null}
              </span>
              <span className="tabbar__label" aria-hidden="true">
                {tab.label}
              </span>
            </button>
          );
        })}
      </div>
    </nav>
  );
}

/** Wide-browser navigation uses the same destinations and selection semantics. */
export function SideNav({
  current,
  onChange,
  badges = {},
  footer,
}: {
  current: TabKey;
  onChange: (key: TabKey) => void;
  badges?: Partial<Record<TabKey, number>>;
  footer?: React.ReactNode;
}) {
  return (
    <aside className="side" aria-label="侧边导航">
      <div className="side__brand">
        <Logo size={36} />
        <div>
          <p className="side__name">STU 事项助手</p>
          <p className="side__tag">汕头大学 · 学生自用</p>
        </div>
      </div>
      <nav className="side__nav" aria-label="主导航" data-haptic="selection">
        {TABS.map((tab) => {
          const on = tab.key === navigationTab(current);
          const badge = badges[tab.key];
          return (
            <button
              key={tab.key}
              type="button"
              className={`side__item${on ? " is-on" : ""}`}
              onClick={(event) => {
                if (!isCancelledInteraction(event.nativeEvent)) onChange(tab.key);
              }}
              aria-current={on ? "page" : undefined}
            >
              {on && (
                <motion.span
                  className="side__pill"
                  layoutId="side-pill"
                  transition={PILL}
                />
              )}
              <Icon name={tab.icon} size={20} strokeWidth={on ? 2 : 1.75} />
              <span className="side__label">{tab.label}</span>
              {badge ? <span className="side__badge">{badge > 9 ? "9+" : badge}</span> : null}
            </button>
          );
        })}
      </nav>
      <div className="side__foot">
        <ModePill />
        {footer}
      </div>
    </aside>
  );
}
