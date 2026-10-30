/**
 * 学校账号密码只留在这台设备上，而且**按「助手账号 + 服务器」分区**。
 *
 * 为什么必须分区：这台设备可能是宿舍共用的。原来只有一个全局 key
 * `stu.school`，A 存过校园账号后退出，B 登录同一台机器、自己的校园会话过期，
 * 自动续连就会拿 **B 的 Bearer + A 的校园账号密码** 去请求——串的是校园身份，
 * 不只是残留一个界面设置（审查报告第 04 项）。
 *
 * 现在的规矩：
 *   * key 是 `stu.school:<服务器>:<助手账号>`，换人换服务器都读不到别人的；
 *   * 退出 / 删除账号时 `clearAllSchool()` 把本机这一批全清掉；
 *   * 老版本的全局 `stu.school` 在第一次读时被清掉，不做迁移——
 *     我们无法判断它属于谁，留着就是隐患。
 *
 * 密码不离开设备：只在掉线时那一次 /v1/relogin 请求里用一下。
 */
import { api, apiBase } from "./api";

const LEGACY_KEY = "stu.school";
const PREFIX = "stu.school:";

export interface SchoolCreds {
  account: string;
  password: string;
  savedAt: number;
}

/**
 * 当前身份的分区键。
 *
 * 用「服务器 + 助手账号」拼：同一台机器上换服务器（测试站 ↔ 正式站）或换人，
 * 都不该读到上一份凭据。账号用登录后的邮箱/uid，取不到就退化成服务器级分区，
 * 至少不会跨服务器串。
 */
function slot(): string {
  let server = "local";
  try {
    server = apiBase() || "local";
  } catch {
    /* 还没配置服务器 */
  }
  let who = "anon";
  try {
    who = localStorage.getItem("stu.account") || "anon";
  } catch {
    /* 隐私模式 */
  }
  return `${PREFIX}${server}|${who}`;
}

function dropLegacy(): void {
  try {
    // 老版本只有一个全局 key，判断不了归属，直接删掉比留着安全
    if (localStorage.getItem(LEGACY_KEY) !== null) localStorage.removeItem(LEGACY_KEY);
  } catch {
    /* 忽略 */
  }
}

export function readSchool(): SchoolCreds | null {
  dropLegacy();
  try {
    const raw = localStorage.getItem(slot());
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<SchoolCreds>;
    if (!parsed?.account || !parsed?.password) return null;
    return { account: parsed.account, password: parsed.password, savedAt: parsed.savedAt ?? 0 };
  } catch {
    return null;
  }
}

export function saveSchool(account: string, password: string): void {
  try {
    localStorage.setItem(slot(), JSON.stringify({ account, password, savedAt: Date.now() }));
  } catch {
    /* 隐私模式等写不进去就算了，用户下次会再被问一次 */
  }
}

/** 清掉**当前身份**这一份。 */
export function clearSchool(): void {
  try {
    localStorage.removeItem(slot());
  } catch {
    /* 同上 */
  }
}

/**
 * 清掉这台设备上所有 `stu.school:*`。
 *
 * 退出登录 / 删除账号时必须调它：否则换人用同一台机器时，上一份校园凭据
 * 还躺在 localStorage 里，等着被下一次自动续连拿去用。
 */
export function clearAllSchool(): void {
  try {
    const doomed: string[] = [];
    for (let i = 0; i < localStorage.length; i += 1) {
      const k = localStorage.key(i);
      if (k && (k === LEGACY_KEY || k.startsWith(PREFIX))) doomed.push(k);
    }
    for (const k of doomed) localStorage.removeItem(k);
  } catch {
    /* 忽略 */
  }
}

/** 用本机存的密码把过期的会话续上；没存或学校要人工验证就返回 false。 */
export async function autoRelogin(services: string[]): Promise<boolean> {
  const creds = readSchool();
  if (!creds || services.length === 0) return false;
  try {
    await api.relogin(services, creds.account, creds.password);
    return true;
  } catch {
    return false;
  }
}
