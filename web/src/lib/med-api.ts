/** Medical endpoints using the shared authenticated transport. */
import { ApiError, request as requestJson } from "./http";

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

function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  return requestJson<T>(path, init, 25_000);
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
