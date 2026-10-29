/**
 * 医学院教务（强智「教学服务平台」）的后端客户端。
 *
 * 和 `plan-api.ts` 一样：不复用 `api.ts` 里没导出的 `request`，但**复刻同一套
 * 约定**（同一个 token、同一个 base、同一套 ApiError 语义、401 广播 AUTH_LOST），
 * 只把 path 前缀换成 `/v1/med`。
 *
 * 一条重要的产品约束：医学院登录**强制图形验证码**，所以这里不可能是"填一次
 * 账号密码就完事"的静默登录——验证码图片必须显示给人看，由他自己敲。
 * 密码同样只在这一来一回里用一次，后端换到 token 就把它丢掉，不落库。
 */
import { ApiError, apiBase, getToken, AUTH_LOST } from "./api";

export interface MedStatus {
  linked: boolean;
  username: string;
  name: string;
  updated_at: number | null;
}

export interface MedCaptcha {
  /** 取验证码时用的毫秒时间戳，登录时必须原样回传。 */
  yzm_code: string;
  /** `data:image/jpeg;base64,...`，直接丢给 <img src>。 */
  image: string;
}

export interface MedLoginInput {
  /** "sso" = 学校统一认证（默认，学生平时用的那组，无验证码）；"local" = 教务本地口令 + 验证码 */
  mode: "sso" | "local";
  username: string;
  password: string;
  verifyCode: string;
  yzmCode: string;
}

export interface MedSyncResult {
  ok: boolean;
  semester: string;
  courses: number;
  meetings: number;
  status: "ok" | "partial";
  message: string;
}

async function request<T>(path: string, init: RequestInit = {}, timeoutMs = 25_000): Promise<T> {
  const base = apiBase();
  const token = getToken();
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const attempt = async (): Promise<Response> => {
    const ctrl = new AbortController();
    const timer = window.setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const res = await fetch(base + path, { ...init, headers, signal: ctrl.signal });
      // fetch 只等到响应头；把 body 也读进内存，隧道抖动时才不会"头到了、体挂住"
      const text = await res.text();
      return new Response(text, { status: res.status, statusText: res.statusText, headers: res.headers });
    } finally {
      window.clearTimeout(timer);
    }
  };

  let res: Response;
  try {
    res = await attempt();
  } catch {
    try {
      res = await attempt();
    } catch {
      throw new ApiError(0, "timeout");
    }
  }

  if (!res.ok) {
    let code = `http_${res.status}`;
    try {
      const body = (await res.json()) as { detail?: unknown };
      if (typeof body.detail === "string") code = body.detail;
      else if (Array.isArray(body.detail)) code = "invalid_input";
    } catch {
      /* 非 JSON 错误体 */
    }
    if (res.status === 401 && token) window.dispatchEvent(new CustomEvent(AUTH_LOST));
    throw new ApiError(res.status, code);
  }
  return (await res.json()) as T;
}

export const medApi = {
  /** 有没有换到过 token。 */
  status: () => request<MedStatus>("/v1/med/status"),

  /** 取一张新的验证码（每次点开都要重取，旧的那张 1–2 分钟就过期）。 */
  captcha: () => request<MedCaptcha>("/v1/med/captcha"),

  /** 用账号密码 + 验证码换 token。密码只在这一次请求里出现。 */
  login: (input: MedLoginInput) =>
    request<{ ok: boolean; name: string }>("/v1/med/login", {
      method: "POST",
      body: JSON.stringify({
        mode: input.mode,
        username: input.username,
        password: input.password,
        verify_code: input.verifyCode,
        yzm_code: input.yzmCode,
      }),
    }),

  /** 拉课表（后端顺便把成绩、考试也存了），写成和主校区同形的数据。 */
  sync: (semester: string) =>
    request<MedSyncResult>("/v1/med/sync", {
      method: "POST",
      body: JSON.stringify({ semester }),
    }),

  /** 解除绑定：删掉服务端那份 token。 */
  unlink: () => request<{ ok: boolean }>("/v1/med/session", { method: "DELETE" }),
};

/** 把后端的错误码翻成人话。医学院的失败大多是"验证码/密码/掉线"三类。 */
export function medErrorText(exc: unknown): string {
  if (exc instanceof ApiError) {
    switch (exc.code) {
      case "bad_captcha":
        return "验证码不对或已经过期了，换一张再试一次。";
      case "cas_captcha":
        return "这次统一认证要验证码，切到「教务本地口令」那条路再试。";
      case "bad_credentials":
        return "学校统一认证说学号或密码不对，就是你平时在浏览器里登录用的那组。";
      case "med_unreachable":
        return "连不上医学院教务系统，可能是校园网限制，换个网络再试。";
      case "med_expired":
        return "医学院的登录过期了，需要重新输一次账号密码。";
      case "timeout":
        return "医学院那边太久没回话，稍等一下再试。";
      case "network":
        return "网络断了，检查一下再试。";
      case "not_linked":
        return "还没连过医学院账号，先把账号密码填上。";
      default:
        return exc.code.startsWith("http_") ? "服务器出了点问题，稍后再试。" : exc.code;
    }
  }
  return "出了点意外，再试一次。";
}
