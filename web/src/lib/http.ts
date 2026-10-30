/** Shared transport for the app API, plans, medical and timetable import. */
import { Capacitor } from "@capacitor/core";

const TOKEN_KEY = "stu.token";
const BASE_KEY = "stu.api";
const BUILD_BASE = (import.meta.env.VITE_API_BASE ?? "").replace(/\/+$/, "");

export const IS_NATIVE = Capacitor.isNativePlatform();

export function apiBase(): string {
  if (!IS_NATIVE) return "";
  try {
    return (localStorage.getItem(BASE_KEY) || BUILD_BASE).replace(/\/+$/, "");
  } catch {
    return BUILD_BASE;
  }
}

export function setApiBase(base: string | null): void {
  try {
    if (base) localStorage.setItem(BASE_KEY, base.replace(/\/+$/, ""));
    else localStorage.removeItem(BASE_KEY);
  } catch {
    /* storage blocked */
  }
}

export function needsServer(): boolean {
  return IS_NATIVE && !apiBase();
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage blocked */
  }
}

export class ApiError extends Error {
  constructor(public status: number, public code: string) {
    super(code);
  }
}

export const AUTH_LOST = "stu:auth-lost";

async function readBody(
  path: string, init: RequestInit, timeoutMs: number, base: string, retries: number,
): Promise<string> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (!headers.has("Accept")) headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const attempt = async () => {
    const ctrl = new AbortController();
    const timer = window.setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const response = await fetch(base + path, { ...init, headers, signal: ctrl.signal });
      // Keep the same timeout active until the entire body has arrived.
      return { response, body: await response.text() };
    } finally {
      window.clearTimeout(timer);
    }
  };

  let result: Awaited<ReturnType<typeof attempt>> | undefined;
  for (let i = 0; i <= retries; i += 1) {
    try {
      result = await attempt();
      break;
    } catch {
      if (i === retries) throw new ApiError(0, "timeout");
    }
  }
  if (!result) throw new ApiError(0, "timeout");
  const { response, body } = result;
  if (!response.ok) {
    let code = `http_${response.status}`;
    try {
      const detail: unknown = JSON.parse(body)?.detail;
      if (typeof detail === "string") code = detail;
      else if (Array.isArray(detail)) code = "invalid_input";
    } catch {
      /* Non-JSON errors still carry the HTTP status. */
    }
    // An old account/server's response must not sign out the current session.
    const stillCurrent = token !== null && token === getToken() && base === apiBase();
    if (response.status === 401 && stillCurrent && !path.startsWith("/v1/auth/")) {
      window.dispatchEvent(new CustomEvent(AUTH_LOST));
    }
    throw new ApiError(response.status, code);
  }
  return body;
}

export async function request<T>(
  path: string, init: RequestInit = {}, timeoutMs = 15_000, base = apiBase(), retries = 1,
): Promise<T> {
  const body = await readBody(path, init, timeoutMs, base, retries);
  try {
    return JSON.parse(body) as T;
  } catch {
    throw new ApiError(0, "network");
  }
}

export function requestText(
  path: string, timeoutMs = 20_000, base = apiBase(), retries = 0,
): Promise<string> {
  return readBody(path, { headers: { Accept: "text/csv" } }, timeoutMs, base, retries);
}
