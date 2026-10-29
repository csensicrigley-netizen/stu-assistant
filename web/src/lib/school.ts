/**
 * 学校账号密码只留在这台设备上。
 *
 * 教务会话会自己过期。App 不再让用户每次手动"重新连接"：把学校密码存在本机
 * localStorage，掉线时用它调一次 /v1/relogin 自动续上。密码不离开设备到任何存储，
 * 服务端只在那一请求的内存里用一次。
 */
import { api } from "./api";

const KEY = "stu.school";

export interface SchoolCreds {
  account: string;
  password: string;
  savedAt: number;
}

export function readSchool(): SchoolCreds | null {
  try {
    const raw = localStorage.getItem(KEY);
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
    localStorage.setItem(KEY, JSON.stringify({ account, password, savedAt: Date.now() }));
  } catch {
    /* 隐私模式等写不进去就算了，用户下次会再被问一次 */
  }
}

export function clearSchool(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* 同上 */
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
