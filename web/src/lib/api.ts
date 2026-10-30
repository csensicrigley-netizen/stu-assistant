/** Backend endpoint shapes; transport is shared with the feature clients. */
import { ApiError, request } from "./http";
export { ApiError, apiBase, setApiBase, getToken, setToken, IS_NATIVE, needsServer, AUTH_LOST } from "./http";

/* ------------------------------------------------------------------ types */

export interface BusLine {
  name: string;
  from: string;
  to: string;
  first: string;
  last: string;
  headway: number;
  stops: number;
  eta_sec: number | null;
  next_sec: number | null;
  eta_min: number | null;
  next_min: number | null;
  source: "live" | "schedule";
  note: string;
}

export interface BusSource {
  name: string;
  url: string;
  note: string;
}

export interface BusSnapshot {
  ok: boolean;
  stop: string;
  updated_at: string;
  live: boolean;
  live_count: number;
  note: string;
  official: string;
  sources: BusSource[];
  lines: BusLine[];
}

export interface FeedItem {
  id: number;
  kind: string;
  source: string;
  title: string;
  updated_at: number;
  detail: Record<string, unknown>;
}

export interface StatusSource {
  source: string;
  code: number | null;
  state: string;
  detail: string;
  at: number | null;
  items: number;
}

export interface BindChallenge {
  id: string;
  kind: "captcha" | "qr" | "totp";
  image_b64?: string;
  media_type?: string;
  content?: string;
  label?: string;
}

export interface BindJob {
  job_id: string;
  state: "running" | "waiting_input" | "ok" | "failed" | "cancelled";
  phase: string;
  challenges: BindChallenge[];
  result?: { bound: string[]; errors: Record<string, string>; demo?: boolean } | null;
  error?: { code: string; detail?: unknown } | null;
}

export interface Me {
  id: string;
  email: string;
  created_at: number | null;
  bound: string[];
  demo: boolean;
}

export interface ChargingState {
  device: { code: string; name: string; location: string; capacity: number };
  state: {
    ports: { num: number; state: number; label: string }[];
    counts: { total: number; observed: number; free: number; occupied: number; fault: number; unknown: number };
    collected_at?: string | null;
  };
}

export const SYNC_SOURCES = "public,jw,mystu";

/* -------------------------------------------------------------- endpoints */

export const api = {
  /** reachability check for a server address the user typed */
  health: (base: string) => request<{ ok: boolean; demo: boolean }>("/v1/health", {}, 8_000, base.replace(/\/+$/, "")),

  /**
   * 服务器登记的最新版本 + 我这一版要不要更新。
   * 公开接口：没登录、甚至还没填服务器地址时都能问（更新提示不能等登录）。
   */
  version: (name: string, code: number) =>
    request<{
      latest: { versionName: string; versionCode: number; apk: string; notes: string[]; mandatory: boolean; releasedAt?: string | null };
      update: {
        versionName: string;
        versionCode: number;
        apk: string;
        notes: string[];
        mandatory: boolean;
        current?: { versionName: string | null; versionCode: number | null };
      } | null;
      outdated: boolean;
    }>(`/v1/meta/version?name=${encodeURIComponent(name)}&code=${code}`, {}, 8_000),

  /**
   * 本人授权（OA 通知）。
   * 只记「谁、什么范围、什么时候、同意的是哪版文案」。
   * 没有这条记录，服务端不会去读 OA，连 WebVPN 都不让你连。
   */
  consentList: () => request<{ consents: { scope: string; at: number; version: string }[] }>("/v1/consent"),

  consentGrant: (version = "1") =>
    request<{ ok: boolean; scope: string; at: number; version: string }>("/v1/consent", {
      method: "POST",
      body: JSON.stringify({ scope: "oa", version }),
    }),

  consentWithdraw: () => request<{ ok: boolean; withdrawn: string }>("/v1/consent?scope=oa", { method: "DELETE" }),

  register: (email: string, passphrase: string) =>
    request<{ token: string; user: { id: string; email: string } }>("/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, passphrase }),
    }, 45_000),

  login: (email: string, passphrase: string) =>
    request<{ token: string; user: { id: string; email: string } }>("/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, passphrase }),
    }, 45_000),

  me: () => request<Me>("/v1/me"),

  bind: (services: string[], student_id: string, password: string) =>
    request<BindJob>("/v1/bind", {
      method: "POST",
      body: JSON.stringify({ services, student_id, password }),
    }),

  bindState: (jobId: string) => request<BindJob>(`/v1/bind/${encodeURIComponent(jobId)}`),

  bindInput: (jobId: string, challengeId: string, value: string) =>
    request<BindJob>(`/v1/bind/${encodeURIComponent(jobId)}/input`, {
      method: "POST",
      body: JSON.stringify({ challenge_id: challengeId, value }),
    }),

  bindCancel: (jobId: string) => request<BindJob>(`/v1/bind/${encodeURIComponent(jobId)}`, { method: "DELETE" }),

  /**
   * 用本机保存的学校密码把过期会话续上（密码只在这次请求里用一次）。
   * 学校要求验证码/动态码时抛错，交给界面让用户手动重连。
   */
  relogin: (services: string[], student_id: string, password: string) =>
    request<{ ok: boolean; services: string[]; errors: Record<string, string> }>(
      "/v1/relogin",
      {
        method: "POST",
        body: JSON.stringify({ services, student_id, password }),
      },
      60_000,
    ),

  /**
   * Start a background sync and return at once; poll `status()` until
   * `syncing` is false. The server applies a 10-minute cooldown per source
   * (1 minute with force=true).
   */
  sync: (force = false, sources = SYNC_SOURCES) =>
    request<{ demo: boolean; started: boolean; syncing: boolean }>(
      `/v1/sync?sources=${encodeURIComponent(sources)}&force=${force}`,
      { method: "POST" },
    ),

  // 课表整份文档在慢网（iOS + 5G 切 Wi-Fi）上很容易超过 15 秒，单独放宽到 40 秒
  timetable: (semester = "") =>
    request<Record<string, unknown>>(`/v1/timetable?semester=${encodeURIComponent(semester)}`, {}, 40_000),

  feed: (kinds = "", limit = 100) =>
    request<{ items: FeedItem[]; count: number }>(`/v1/feed?kinds=${encodeURIComponent(kinds)}&limit=${limit}`, {}, 30_000),

  notices: (limit = 10) => request<{ items: FeedItem[]; count: number }>(`/v1/notices?limit=${limit}`),

  /** 今天 5 条冷知识：同一天固定，隔天换一批。 */
  facts: () =>
    request<{ day: string; count: number; items: { id: string; text: string; index: number }[] }>("/v1/facts"),

  /** 到桑浦山校区的公交到站（实时优先，没有上游则按班次推算） */
  bus: () => request<BusSnapshot>("/v1/bus"),

  grades: (semester = "") =>
    request<{ rows: Record<string, unknown>[]; count: number }>(`/v1/grades?semester=${encodeURIComponent(semester)}`, {}, 30_000),

  exams: (semester = "") =>
    request<{ rows: Record<string, unknown>[]; count: number }>(`/v1/exams?semester=${encodeURIComponent(semester)}`),

  status: () =>
    request<{ sources: StatusSource[]; bound: { service: string }[]; demo: boolean; syncing?: boolean }>("/v1/status"),

  charging: (code: string) => request<ChargingState>(`/v1/charging/${encodeURIComponent(code)}`),

  chargingAll: () =>
    request<{ devices: { device: ChargingState["device"]; state: ChargingState["state"] | null; error?: string }[] }>(
      "/v1/charging",
      {},
      30_000,
    ),

  deleteAccount: () => request<{ ok: boolean }>("/v1/account", { method: "DELETE" }),

  logout: () => request<{ ok: boolean }>("/v1/account/logout", { method: "POST" }),

  /**
   * 本人 WebVPN 会话的保活状态。服务端每 20 分钟用自己的会话读一次 OA，
   * 只有学校要数盾动态码时才置 need_input，此时要本人现输一次。
   */
  webvpnState: () =>
    request<{ ok: boolean; need_input: boolean; detail: string; at: string; interval_minutes: number }>(
      "/v1/webvpn/state",
    ),

  /**
   * 只连 WebVPN：学号密码用服务器上存着的那份，用户只要现输六位数盾动态码。
   * 返回的 job 停在 waiting_input，challenges 里有一条 totp，
   * 把码用 bindInput 交回去就建好会话了。
   */
  webvpnConnect: () => request<BindJob>("/v1/webvpn/connect", { method: "POST" }),

  // --- WebVPN 人工验证登录（学校开了图形码，协议版过不去）------------------
  /** 开始登录：返回一张图形验证码。没存过学校账号时抛 409 need_credentials。 */
  webvpnStart: (studentId?: string, password?: string) =>
    request<{
      ok: boolean;
      stage: "captcha";
      token: string;
      needs_captcha: boolean;
      /** 学校按 IP/风险自适应决定要不要图码；不要的时候这里是 null */
      captcha: { image_b64: string; media_type: string } | null;
      interval_seconds: number;
    }>("/v1/webvpn/login/start", {
      method: "POST",
      body: JSON.stringify({ student_id: studentId ?? null, password: password ?? null }),
    }),

  /** 换一张验证码图。 */
  webvpnNewCaptcha: (token: string) =>
    request<{ ok: boolean; token: string; captcha: { image_b64: string; media_type: string } }>(
      `/v1/webvpn/login/captcha?token=${encodeURIComponent(token)}`,
    ),

  /**
   * 交图形验证码。三种结果：
   *   { ok:true }                        —— 会话建好了
   *   { ok:false, need:"totp", token }   —— 还要六位动态码
   *   { ok:false, code:"captcha_rejected", captcha:{...} } —— 图码不对，已附新图
   */
  webvpnCaptcha: (token: string, captcha: string) =>
    request<{
      ok: boolean;
      need?: "totp";
      token?: string;
      hint?: string;
      code?: string;
      message?: string;
      captcha?: { image_b64: string; media_type: string };
      cookies?: number;
    }>("/v1/webvpn/login/captcha", {
      method: "POST",
      body: JSON.stringify({ token, captcha }),
    }),

  /** 交数盾六位动态码。错了可以留着 token 等下一个码再试。 */
  webvpnTotp: (token: string, code: string) =>
    request<{ ok: boolean; code?: string; message?: string; token?: string; cookies?: number }>(
      "/v1/webvpn/login/totp",
      { method: "POST", body: JSON.stringify({ token, code }) },
    ),

  webvpnAbort: () => request<{ ok: boolean }>("/v1/webvpn/login/abort", { method: "POST" }),

  /** 自动续期状态：configured=true 表示服务器能自己滚动态码，过期不用再问人。 */
  webvpnAutoStatus: () =>
    request<{ configured: boolean; enabled: boolean; status: string; reason?: string | null }>(
      "/v1/webvpn/auto",
    ),

  /** 把数盾令牌密钥交给服务器（otpauth:// 地址或 Base32 密钥）。 */
  webvpnAutoEnable: (secret: string) =>
    request<{ ok: boolean; status: string; message?: string }>("/v1/webvpn/auto", {
      method: "POST",
      body: JSON.stringify({ secret }),
    }),

  webvpnAutoDisable: () => request<{ ok: boolean; status: string }>("/v1/webvpn/auto", { method: "DELETE" }),
};

/** Human wording for every error code the sign-in and bind screens can see. */
export function errorText(exc: unknown): string {
  const code = exc instanceof ApiError ? exc.code : "network";
  return (
    {
      bad_credentials: "邮箱或口令不对。",
      email_taken: "这个邮箱已经注册过，换成登录试试。",
      too_many_attempts: "尝试太频繁了，过几分钟再试。",
      login_throttled: "这一小时里连接次数太多了，稍后再试，避免学校锁定账号。",
      invalid_token: "登录状态过期了，请重新登录。",
      invalid_input: "填写的内容格式不对，检查一下邮箱和口令（至少 8 位）。",
      account_disabled: "这个账号已停用。",
      timeout: "服务器没有及时响应，稍后再试。",
      network: "连不上助手服务器，检查一下网络或服务器地址。",
    }[code] ?? `没有成功（${code}）`
  );
}
