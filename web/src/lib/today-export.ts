/**
 * Helpers for the one-click `today` companion timetable handoff.
 *
 * Security invariants:
 *   - Only whitelisted `today://sheet/stu-import?code=<12-char>` deep links are opened.
 *   - No credentials, emails, or tokens are ever stored or placed in URLs.
 */
import { ApiError, errorText, type TodayExportTicket } from "./api";

export type { TodayExportTicket };

export const FROM_TODAY_KEY = "stu.from_today";
export const TODAY_DOWNLOAD_URL = "https://today.oyishi.asia/";
const TODAY_DEEP_LINK_RE = /^today:\/\/sheet\/stu-import\?code=[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}$/;

export function hasFromTodayQuery(search?: string): boolean {
  try {
    const qs = search ?? (typeof location !== "undefined" ? location.search : "");
    return new URLSearchParams(qs).get("from")?.trim().toLowerCase() === "today";
  } catch {
    return false;
  }
}

export function captureFromTodayFlag(search?: string): boolean {
  if (hasFromTodayQuery(search)) {
    try {
      if (typeof sessionStorage !== "undefined") sessionStorage.setItem(FROM_TODAY_KEY, "1");
    } catch {
      /* private mode */
    }
    return true;
  }
  return isFromToday(search);
}

export function isFromToday(search?: string): boolean {
  if (hasFromTodayQuery(search)) return true;
  try {
    return typeof sessionStorage !== "undefined" && sessionStorage.getItem(FROM_TODAY_KEY) === "1";
  } catch {
    return false;
  }
}

export function clearFromTodayFlag(): void {
  try {
    if (typeof sessionStorage !== "undefined") sessionStorage.removeItem(FROM_TODAY_KEY);
  } catch {
    /* ignore */
  }
}

export function isAllowedTodayDeepLink(url: string): boolean {
  return TODAY_DEEP_LINK_RE.test(url);
}

export function openTodayDeepLink(deepLink: string): boolean {
  if (!isAllowedTodayDeepLink(deepLink)) return false;
  try {
    if (typeof window !== "undefined" && window.location) {
      window.location.href = deepLink;
      return true;
    }
  } catch {
    /* blocked by environment */
  }
  return false;
}

export function formatExportCode(code: string): string {
  const clean = String(code || "").replace(/\s+/g, "").toUpperCase();
  return clean.replace(/(.{4})(?=.)/g, "$1 ");
}

export function campusLabel(campus: string): string {
  if (campus === "med") return "汕大医学院";
  if (campus === "main") return "汕大本部";
  return "手动导入";
}

export function todayExportErrorText(exc: unknown): string {
  const code = exc instanceof ApiError ? exc.code : "";
  if (code === "no_timetable") return "当前还没有可导出的课表，请先同步教务、连接医学院或手动导入课表。";
  if (code === "too_many_attempts") return "生成导入码太频繁了，请稍后再试。";
  return errorText(exc);
}
