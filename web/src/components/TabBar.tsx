import { motion } from "motion/react";
import { Icon, type IconName } from "./Icon";
import { Logo } from "./Logo";
import { ModePill } from "./Layout";
import { PILL } from "../lib/motion";

export type TabKey = "today" | "week" | "study" | "campus" | "me";

export const TABS: { key: TabKey; label: string; icon: IconName }[] = [
  { key: "today", label: "今天", icon: "today" },
  { key: "week", label: "课表", icon: "week" },
  { key: "study", label: "学业", icon: "study" },
  { key: "campus", label: "校园", icon: "campus" },
  { key: "me", label: "我的", icon: "me" },
];

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
  return (
    <nav className="tabbar" aria-label="主导航">
      <div className="tabbar__row">
        {TABS.map((tab) => {
          const on = tab.key === current;
          const badge = badges[tab.key];
          return (
            <button
              key={tab.key}
              type="button"
              className={`tabbar__btn${on ? " is-on" : ""}`}
              onClick={() => onChange(tab.key)}
              aria-current={on ? "page" : undefined}
              aria-label={badge ? `${tab.label}，${badge} 项待处理` : tab.label}
            >
              <span className="tabbar__icon">
                {on && (
                  <motion.span
                    className="tabbar__pill"
                    layoutId="tab-pill"
                    transition={PILL}
                  />
                )}
                <Icon name={tab.icon} size={22} strokeWidth={on ? 2 : 1.75} />
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

/** Left rail for wide browser windows: brand, the same five places, status. */
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
      <nav className="side__nav" aria-label="主导航">
        {TABS.map((tab) => {
          const on = tab.key === current;
          const badge = badges[tab.key];
          return (
            <button
              key={tab.key}
              type="button"
              className={`side__item${on ? " is-on" : ""}`}
              onClick={() => onChange(tab.key)}
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
