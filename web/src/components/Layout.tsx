import type { ReactNode } from "react";
import { useShell, useStore } from "../lib/store";
import { agoText } from "../lib/clock";
import { Icon } from "./Icon";

/**
 * 进场编排只有一个出口：lib/motion.ts 的 rise()。
 * 这里原来还留着一份自己的实现（y:10 / i*0.04），跟 motion.ts 那份不一致，
 * 页面上就出现了两种"落位手感"。现在统一重导出，谁 import 都拿到同一套。
 */
export { rise } from "../lib/motion";

export function PageHead({
  eyebrow,
  title,
  sub,
  aside,
}: {
  eyebrow?: ReactNode;
  title: ReactNode;
  sub?: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <header className="head">
      <div className="head__row">
        <div className="head__text">
          {eyebrow && <p className="head__eyebrow">{eyebrow}</p>}
          <h1 className="head__title">{title}</h1>
        </div>
        {aside && <div className="head__aside">{aside}</div>}
      </div>
      {sub && <p className="head__sub">{sub}</p>}
    </header>
  );
}

export function Section({
  title,
  action,
  children,
  id,
  note,
}: {
  title: ReactNode;
  action?: { label: string; onClick: () => void };
  children: ReactNode;
  id?: string;
  note?: ReactNode;
}) {
  return (
    <section className="sec" id={id} aria-labelledby={id ? `${id}-h` : undefined}>
      <div className="sec__head">
        <h2 className="sec__title" id={id ? `${id}-h` : undefined}>
          {title}
        </h2>
        {note && <span className="sec__note">{note}</span>}
        {action && (
          <button type="button" className="link-btn" onClick={action.onClick}>
            {action.label}
          </button>
        )}
      </div>
      {children}
    </section>
  );
}

/**
 * Which data the screen is showing, always visible. It is also the refresh
 * control: tap to sync (signed in) or to connect an account (sample data).
 */
export function ModePill() {
  const { mode, sample, syncedAt, loading, syncing, refresh } = useStore();
  const { connect } = useShell();

  if (syncing || loading) {
    return (
      <span className="pill pill--busy" role="status">
        <span className="pill__dot" aria-hidden="true" />
        {syncing ? "同步中" : "读取中"}
      </span>
    );
  }
  if (sample) {
    return (
      <button type="button" className="pill-btn" onClick={connect} aria-label="当前是示例数据，点按连接校园账号">
        <span className="pill pill--sample">
          <span className="pill__dot" aria-hidden="true" />
          示例数据
          <Icon name="chevron-right" size={14} strokeWidth={2.2} />
        </span>
      </button>
    );
  }
  const label =
    mode === "cached" ? (syncedAt ? `离线 · ${agoText(syncedAt)}` : "未连接") : syncedAt ? `${agoText(syncedAt)}更新` : "已更新";
  return (
    <button type="button" className="pill-btn" onClick={() => void refresh("sync")} aria-label={`${label}，点按同步`}>
      <span className={`pill ${mode === "cached" ? "pill--stale" : "pill--live"}`}>
        <span className="pill__dot" aria-hidden="true" />
        {label}
        <Icon name="refresh" size={13} strokeWidth={2.2} />
      </span>
    </button>
  );
}

export function Empty({ icon, title, children }: { icon: ReactNode; title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <div className="empty__icon" aria-hidden="true">
        {icon}
      </div>
      <p className="empty__title">{title}</p>
      {children && <div className="empty__body">{children}</div>}
    </div>
  );
}

/** One-line notice at the top of a screen, e.g. an expired school session. */
export function Notice({
  tone = "warn",
  children,
  action,
}: {
  tone?: "warn" | "info";
  children: ReactNode;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className={`notice-bar notice-bar--${tone}`} role="status">
      <Icon name={tone === "warn" ? "alert" : "info"} size={18} />
      <p>{children}</p>
      {action && (
        <button type="button" className="notice-bar__btn" onClick={action.onClick}>
          {action.label}
        </button>
      )}
    </div>
  );
}
