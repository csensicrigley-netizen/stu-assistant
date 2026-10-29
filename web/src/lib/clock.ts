/**
 * One clock for the whole App, always in Asia/Shanghai.
 *
 * A student who flies home over the holiday still needs "08:00" to mean the
 * campus 08:00, so nothing here reads the device time zone.
 *
 * `?now=2026-10-28T10:20` pins the clock (preview and screenshot runs only),
 * so every state of the Today screen can be reviewed on demand.
 */
import { useEffect, useState } from "react";

const TZ = "Asia/Shanghai";

function pinned(): number | null {
  try {
    const raw = new URLSearchParams(location.search).get("now");
    if (!raw) return null;
    const ms = Date.parse(/[+-]\d\d:\d\d$|Z$/.test(raw) ? raw : `${raw}+08:00`);
    return Number.isNaN(ms) ? null : ms;
  } catch {
    return null;
  }
}

const PIN = pinned();
const PIN_STARTED = Date.now();

/** Current epoch ms; when pinned, the pinned moment keeps ticking forward. */
export function nowMs(): number {
  return PIN === null ? Date.now() : PIN + (Date.now() - PIN_STARTED);
}

const partsFmt = new Intl.DateTimeFormat("en-CA", {
  timeZone: TZ,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  weekday: "short",
  hourCycle: "h23",
});

const WD: Record<string, number> = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

export interface Moment {
  ms: number;
  /** "2026-10-28" */
  date: string;
  year: number;
  month: number;
  day: number;
  /** Monday = 1 … Sunday = 7, the school's own numbering */
  weekday: number;
  /** minutes since local midnight */
  minutes: number;
}

export function moment(ms = nowMs()): Moment {
  const parts = Object.fromEntries(
    partsFmt.formatToParts(new Date(ms)).map((p) => [p.type, p.value]),
  ) as Record<string, string>;
  const year = Number(parts.year);
  const month = Number(parts.month);
  const day = Number(parts.day);
  return {
    ms,
    date: `${parts.year}-${parts.month}-${parts.day}`,
    year,
    month,
    day,
    weekday: WD[parts.weekday] ?? 1,
    minutes: Number(parts.hour) * 60 + Number(parts.minute),
  };
}

/** Midnight (Shanghai) of a "YYYY-MM-DD" date, as epoch ms. */
export function dayStartMs(isoDate: string): number {
  return Date.parse(`${isoDate}T00:00:00+08:00`);
}

export function addDaysIso(isoDate: string, days: number): string {
  return moment(dayStartMs(isoDate) + days * 86_400_000 + 3_600_000).date;
}

export const WEEKDAY_SHORT = ["一", "二", "三", "四", "五", "六", "日"];
export const WEEKDAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];

export function weekdayCn(weekday: number): string {
  return WEEKDAY_CN[weekday - 1] ?? `周${weekday}`;
}

/** "10月28日" */
export function monthDayCn(isoDate: string): string {
  const [, m, d] = isoDate.split("-").map(Number);
  return `${m}月${d}日`;
}

/** "10/28" */
export function monthDaySlash(isoDate: string): string {
  const [, m, d] = isoDate.split("-").map(Number);
  return `${m}/${d}`;
}

/** "HH:MM" of an epoch, Shanghai time. */
export function clockOf(ms: number): string {
  const m = moment(ms);
  return `${String(Math.floor(m.minutes / 60)).padStart(2, "0")}:${String(m.minutes % 60).padStart(2, "0")}`;
}

/** 135 -> "2 小时 15 分"; 40 -> "40 分" */
export function spanText(minutes: number): string {
  const m = Math.max(0, Math.round(minutes));
  const h = Math.floor(m / 60);
  const r = m % 60;
  if (h === 0) return `${r} 分`;
  if (r === 0) return `${h} 小时`;
  return `${h} 小时 ${r} 分`;
}

/** "刚刚" / "12 分钟前" / "3 小时前" / "昨天" / "10/21" */
export function agoText(ms: number, now = nowMs()): string {
  const diff = (now - ms) / 1000;
  if (diff < 60) return "刚刚";
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  const days = Math.round((dayStartMs(moment(now).date) - dayStartMs(moment(ms).date)) / 86_400_000);
  if (days === 1) return "昨天";
  if (days < 7) return `${days} 天前`;
  return monthDaySlash(moment(ms).date);
}

/** Whole days from today (Shanghai) to a date: 0 today, 1 tomorrow, -1 yesterday. */
export function daysUntil(isoDate: string, now = nowMs()): number {
  return Math.round((dayStartMs(isoDate) - dayStartMs(moment(now).date)) / 86_400_000);
}

/** Re-render on a timer, paused while the page is hidden. */
export function useNow(intervalMs = 30_000): Moment {
  const [value, setValue] = useState(() => moment());
  useEffect(() => {
    let id: number | undefined;
    const tick = () => setValue(moment());
    const start = () => {
      tick();
      id = window.setInterval(tick, intervalMs);
    };
    const stop = () => window.clearInterval(id);
    const onVis = () => (document.hidden ? stop() : start());
    start();
    document.addEventListener("visibilitychange", onVis);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", onVis);
    };
  }, [intervalMs]);
  return value;
}
