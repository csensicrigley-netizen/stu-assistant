/**
 * 网页端校园凭据清理工具（v1.2.0 起禁止在浏览器 `localStorage` 中保存学校密码）。
 *
 * 历史版本曾在 `localStorage` 中以明文 JSON 写入 `stu.school` / `stu.school:*`
 * 以便会话过期后自动重登。为避免同源脚本或共用设备读取校园密码，当前版本：
 *   - 永远不再把学校密码写入 `localStorage`；
 *   - 在读取、登录、退出或删除账号时自动清理历史遗留的 `stu.school` 与 `stu.school:*`；
 *   - 会话过期时提示用户重新输入一次密码。
 */

const LEGACY_KEY = "stu.school";
const PREFIX = "stu.school:";

export interface SchoolCreds {
  account: string;
  password: string;
  savedAt: number;
}

/**
 * 清除当前浏览器中历史遗留的所有 `stu.school` 与 `stu.school:*` 明文凭据。
 */
export function clearAllSchool(): void {
  try {
    const doomed: string[] = [];
    if (localStorage.getItem(LEGACY_KEY) !== null) doomed.push(LEGACY_KEY);
    for (let i = 0; i < localStorage.length; i += 1) {
      const k = localStorage.key(i);
      if (k && (k === LEGACY_KEY || k.startsWith(PREFIX)) && !doomed.includes(k)) {
        doomed.push(k);
      }
    }
    for (const k of doomed) localStorage.removeItem(k);
  } catch {
    /* 隐私模式或存储受限时忽略 */
  }
}

/** 网页端不再保存学校密码；调用时顺手清掉历史明文残留并返回 null。 */
export function readSchool(): SchoolCreds | null {
  clearAllSchool();
  return null;
}

/** 网页端禁止将学校密码写入 `localStorage`；仅清理历史残留。 */
export function saveSchool(_account: string, _password: string): void {
  clearAllSchool();
}

/** 清除历史遗留的学校密码记录。 */
export function clearSchool(): void {
  clearAllSchool();
}

/** 网页端不保存密码，不执行静默重登；会话过期后由用户重新输入。 */
export async function autoRelogin(_services: string[]): Promise<boolean> {
  clearAllSchool();
  return false;
}
