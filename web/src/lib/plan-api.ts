/**
 * 每日计划的后端客户端。
 *
 * 为什么不直接塞进 `api.ts`：那份文件是别人在维护的公共客户端，动它会影响
 * 所有页面。这里复刻同一套约定（同一个 token、同一个 base、同一套 ApiError
 * 语义），只是把 path 前缀换成 `/v1/plan`。
 *
 * 注意：`api.ts` 的 `request` 没有导出，所以下面这份是照着它写的等价实现——
 * 15 秒超时、超时/网络错误自动重试一次、401 时广播 AUTH_LOST、非 2xx 把
 * `detail` 当错误码抛 `ApiError`。行为一致，错误码对 `errorText()` 同样适用。
 */
import { ApiError, apiBase, getToken, AUTH_LOST } from "./api";

export interface Plan {
  id: string;
  date: string;
  title: string;
  note: string;
  start: string | null;
  end: string | null;
  done: boolean;
  priority: 0 | 1 | 2;
  sort: number;
  created_at: number;
  updated_at: number;
}

/** 某一天还有几项没做完（`day_summary` 的返回值）。 */
export interface PlanUpcoming {
  date: string;
  count: number;
}

export interface PlanSummary {
  today_total: number;
  today_done: number;
  today_open: number;
  overdue: number;
  upcoming: PlanUpcoming[];
}

export interface PlanListResult {
  items: Plan[];
  summary: PlanSummary;
}

export interface PlanListParams {
  start?: string;
  end?: string;
  includeDone?: boolean;
}

const EMPTY_SUMMARY: PlanSummary = { today_total: 0, today_done: 0, today_open: 0, overdue: 0, upcoming: [] };

async function request<T>(path: string, init: RequestInit = {}, timeoutMs = 15_000): Promise<T> {
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

  try {
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
  } catch (exc) {
    if (exc instanceof ApiError) throw exc;
    throw new ApiError(0, "network");
  }
}

function listQuery(params?: PlanListParams): string {
  const q = new URLSearchParams();
  if (params?.start) q.set("start", params.start);
  if (params?.end) q.set("end", params.end);
  if (params?.includeDone === false) q.set("include_done", "0");
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** 后端只收这几个字段；`Partial<Plan>` 里多带的 id / created_at 之类会被忽略。 */
function pick(fields: Partial<Plan>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const key of ["date", "title", "note", "start", "end", "done", "priority", "sort"] as const) {
    if (fields[key] !== undefined) out[key] = fields[key];
  }
  return out;
}

export const planApi = {
  list: async (params?: PlanListParams): Promise<PlanListResult> => {
    const res = await request<Partial<PlanListResult>>(`/v1/plan${listQuery(params)}`);
    return {
      items: Array.isArray(res.items) ? res.items : [],
      summary: { ...EMPTY_SUMMARY, ...(res.summary ?? {}) },
    };
  },

  create: (fields: Partial<Plan>) =>
    request<Plan>("/v1/plan", { method: "POST", body: JSON.stringify(pick(fields)) }),

  update: (id: string, fields: Partial<Plan>) =>
    request<Plan>(`/v1/plan/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify(pick(fields)) }),

  remove: (id: string) => request<{ ok: boolean }>(`/v1/plan/${encodeURIComponent(id)}`, { method: "DELETE" }),

  toggle: (id: string, done: boolean) =>
    request<Plan>(`/v1/plan/${encodeURIComponent(id)}/toggle`, {
      method: "POST",
      body: JSON.stringify({ done }),
    }),

  reorder: (date: string, orderedIds: string[]) =>
    request<{ ok: boolean; moved: number }>("/v1/plan/reorder", {
      method: "POST",
      body: JSON.stringify({ date, ids: orderedIds }),
    }),
};

/** 计划页自己的错误文案；认不出的码退回 `ApiError.code`。 */
export function planErrorText(exc: unknown): string {
  const code = exc instanceof ApiError ? exc.code : "network";
  return (
    {
      network: "连不上助手服务器，检查一下网络。",
      timeout: "服务器没有及时响应，稍后再试。",
      invalid_token: "登录状态过期了，请重新登录。",
      plan_not_found: "这条计划已经不在了，刷新一下。",
      invalid_input: "填写的内容格式不对。",
      "标题不能为空": "标题不能为空。",
      "标题最多 200 个字": "标题最多 200 个字。",
      "备注最多 1000 个字": "备注最多 1000 个字。",
      "开始和结束时间要么都填，要么都空": "开始和结束时间要么都填，要么都空。",
      "结束时间要晚于开始时间": "结束时间要晚于开始时间。",
      "日期要写成 2026-10-29 这样": "日期格式不对。",
      "这个日期不存在": "这个日期不存在。",
      "优先级只能是 0、1、2": "优先级只能选普通 / 重要 / 紧急。",
    }[code] ?? `没有成功（${code}）`
  );
}
