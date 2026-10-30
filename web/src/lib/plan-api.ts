/** Daily plan endpoints using the shared authenticated transport. */
import { ApiError, request } from "./http";

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
