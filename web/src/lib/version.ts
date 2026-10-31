/**
 * 版本号 + 「有新版本」检查。
 *
 * 这个文件里的两个常量必须和 android/app/build.gradle 里的 versionName /
 * versionCode 一致 —— `deploy/make-release.ps1` 会做这个校验，不一致直接中止，
 * 免得出现「页面说 1.0.20、装上的包还是 1.0.19」这种最烦人的假更新。
 *
 * 两条独立的更新线索：
 *   1. `checkForUpdate()` —— 问服务器 `/v1/meta/version`：装着的 App 是不是旧包；
 *   2. `checkPageUpdate()` —— 比对自己和 `version.json`：网页那份静态资源是不是旧的
 *      （PWA 会把旧 js 缓存住，刷新一下才换得掉，所以必须主动提示）。
 *
 * 检查失败永远不抛错：版本检查是附带动作，不能因为它把整个 App 弄白屏。
 */
import { api, apiBase, IS_NATIVE } from "./api";

export const APP_VERSION_NAME = "1.0.59";
export const APP_VERSION_CODE = 60;

export type UpdateInfo = {
  versionName: string;
  versionCode: number;
  apk: string;
  notes: string[];
  mandatory: boolean;
  /** "apk" = 要装新安装包；"page" = 网页静态资源旧了，刷新即可 */
  kind?: "apk" | "page";
};

const DISMISS_KEY = "stu.update.skip";

/** 下载地址：网页版同源，App 里要拼上自己连的那个服务器地址。 */
export function apkHref(path: string): string {
  const clean = path.startsWith("/") ? path : `/${path}`;
  return IS_NATIVE ? `${apiBase()}${clean}` : clean;
}

/**
 * 用户点过「稍后」的版本号。
 *
 * 记在 localStorage，并且**记录的是"我已经知道这个版本了"**：
 * 只要服务器上的版本没超过它，就永远不再弹。装了新包 / 出现更新的版本，
 * 版本号一变，提示自然回来 —— 不会再出现"已经最新了还一直弹"这种事。
 */
export function skippedCode(): number | null {
  try {
    const raw = localStorage.getItem(DISMISS_KEY);
    if (!raw) return null;
    const value = Number(raw);
    return Number.isFinite(value) && value > 0 ? value : null;
  } catch {
    return null;
  }
}

export function skipFor(code: number, _hoursKeep = 0): void {
  try {
    // 只升不降：万一有旧页面把更小的号写进来，别把已经忽略的高版本又放出来
    const known = skippedCode() ?? 0;
    localStorage.setItem(DISMISS_KEY, String(Math.max(known, code)));
  } catch {
    /* 隐私模式：跳过就只在内存里生效 */
  }
}

/** 清掉「已知版本」记录，让 `checkForUpdate` 立刻重新提示（「检查更新」按钮用）。 */
export function clearSkip(): void {
  try {
    localStorage.removeItem(DISMISS_KEY);
  } catch {
    /* 忽略 */
  }
}

/**
 * 记下「这一版我已经提示过了」。
 *
 * 每个版本只提示一次，不是每次轮询都弹。之前每 5 分钟重新 setInfo 一次，
 * 用户看到的就是"一直弹窗"（2026-10-29 的真实投诉）。
 */
export function markKnown(code: number): void {
  skipFor(code);
}

const AUTORELOAD_KEY = "stu.page.autoreload";
// 六小时：一天最多自动刷一两次。真遇到 service worker 卡住，
// 也绝不能变成"每十分钟刷一次"的骚扰。
const AUTORELOAD_GAP = 6 * 60 * 60 * 1000;

/**
 * 网页版发现自己的静态资源旧了：**直接自动刷一次**，别去弹窗。
 *
 * 网页版刷新本来就是最新，用户不需要被问"要不要更新"。
 * 十分钟内只自动刷一次：万一 service worker 卡住刷不干净，
 * 第二次就安静放弃，绝不进入"刷—弹—刷"的死循环。
 */
export function autoReloadForStalePage(): boolean {
  if (IS_NATIVE) return false;
  try {
    const last = Number(sessionStorage.getItem(AUTORELOAD_KEY) || 0);
    if (last && Date.now() - last < AUTORELOAD_GAP) return false;
    sessionStorage.setItem(AUTORELOAD_KEY, String(Date.now()));
    window.location.reload();
    return true;
  } catch {
    return false;
  }
}

/**
 * 问服务器要不要更新装着的 App。
 * 返回 null 表示「不用更新 / 问不到」两种无害情况，界面什么都不用做。
 */
export async function checkForUpdate(opts: { ignoreSkip?: boolean } = {}): Promise<UpdateInfo | null> {
  try {
    const res = await api.version(APP_VERSION_NAME, APP_VERSION_CODE);
    if (!res?.outdated || !res.update) return null;
    const info: UpdateInfo = {
      versionName: res.update.versionName,
      versionCode: res.update.versionCode,
      apk: res.update.apk || "/download/stu-assistant.apk",
      notes: res.update.notes ?? [],
      mandatory: Boolean(res.update.mandatory),
      kind: "apk",
    };
    if (!info.mandatory && !opts.ignoreSkip && skippedCode() === info.versionCode) return null;
    return info;
  } catch {
    return null;
  }
}

/**
 * 网页版自己的静态资源是不是旧的。
 *
 * `version.json` 是发版时写进 dist 的小文件，请求带 no-store 绕开 service worker：
 * 页面里的 js 是旧的、服务器上的 version.json 已经新了 —— 那就该请他刷一下。
 * App 内不走这条路（App 的资源打在包体里，只有装新包才会变）。
 */
export async function checkPageUpdate(opts: { ignoreSkip?: boolean } = {}): Promise<UpdateInfo | null> {
  if (IS_NATIVE) return null;
  try {
    const res = await fetch(`/version.json?t=${Date.now()}`, { cache: "no-store" });
    if (!res.ok) return null;
    const data = (await res.json()) as {
      versionName?: string;
      versionCode?: number;
      notes?: string[];
      releasedAt?: string;
    };
    const code = Number(data.versionCode ?? 0);
    if (!code || code <= APP_VERSION_CODE) return null;
    const info: UpdateInfo = {
      versionName: String(data.versionName ?? ""),
      versionCode: code,
      apk: "/download/stu-assistant.apk",
      notes: Array.isArray(data.notes) ? data.notes : [],
      mandatory: false,
      kind: "page",
    };
    if (!opts.ignoreSkip && skippedCode() === info.versionCode) return null;
    return info;
  } catch {
    return null;
  }
}

/** 两条线索取更高版本的那个；都没有就是 null。 */
export async function checkAnyUpdate(opts: { ignoreSkip?: boolean } = {}): Promise<UpdateInfo | null> {
  const [apk, page] = await Promise.all([
    checkForUpdate(opts),
    checkPageUpdate(opts),
  ]);
  if (apk && page) return apk.versionCode >= page.versionCode ? apk : page;
  return apk ?? page;
}
