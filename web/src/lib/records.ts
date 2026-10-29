/**
 * Everything that is not the timetable: tasks, exams, grades, notices.
 *
 * Shapes follow stu-mcp v0.7.3 (docs/phase0/01-data-contract.md §2.2–2.5).
 * School text is kept verbatim; the only things derived here are the ones the
 * contract says the client must derive (overdue / due today), and each derived
 * value keeps the raw string next to it.
 */
import { dayStartMs, moment, nowMs } from "./clock";

const str = (v: unknown): string => (typeof v === "string" ? v : v == null ? "" : String(v));
const num = (v: unknown): number | null => {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  if (typeof v === "string" && v.trim() !== "" && Number.isFinite(Number(v))) return Number(v);
  return null;
};
const rec = (v: unknown): Record<string, unknown> => (v && typeof v === "object" ? (v as Record<string, unknown>) : {});

/* ------------------------------------------------------------------ tasks */

export interface Task {
  id: string;
  title: string;
  course: string;
  source: "mystu" | "yuketang" | string;
  url: string | null;
  /** epoch ms when the school gave a parseable time */
  dueMs: number | null;
  /** what the school wrote, shown when it is not a parseable time */
  dueRaw: string;
  schoolStatus: string;
}

export function normalizeTask(raw: unknown): Task {
  const r = rec(raw);
  const due = str(r.due_at ?? r.due);
  const parsed = due ? Date.parse(/T\d/.test(due) ? due : `${due}T23:59:00+08:00`) : NaN;
  return {
    id: str(r.id),
    title: str(r.title) || "未命名任务",
    course: str(r.course),
    source: str(r.source) || (str(r.id).startsWith("yuketang") ? "yuketang" : "mystu"),
    url: str(r.url) || null,
    dueMs: Number.isNaN(parsed) ? null : parsed,
    dueRaw: due,
    schoolStatus: str(r.school_status),
  };
}

export type DueBucket = "expired" | "overdue" | "today" | "soon" | "later" | "none";

/**
 * Overdue for more than two weeks is "expired": the server keeps every task it
 * has ever seen, so last term's deadlines must not pile up at the top.
 */
const EXPIRED_AFTER = 14 * 86_400_000;

export function dueBucket(task: Task, now = nowMs()): DueBucket {
  if (task.dueMs === null) return "none";
  if (task.dueMs < now - EXPIRED_AFTER) return "expired";
  if (task.dueMs < now) return "overdue";
  const today = moment(now).date;
  if (moment(task.dueMs).date === today) return "today";
  if (task.dueMs - dayStartMs(today) < 4 * 86_400_000) return "soon";
  return "later";
}

/* ------------------------------------------------------------------ exams */

export interface Exam {
  id: string;
  title: string;
  /** school text, verbatim */
  timeRaw: string;
  location: string;
  seat: string;
  campus: string;
  notes: string;
  /** best-effort read of timeRaw, for sorting and a countdown only */
  date: string | null;
  startMs: number | null;
}

export function normalizeExam(raw: unknown): Exam {
  const r = rec(raw);
  const timeRaw = str(r.exam_time);
  const m = timeRaw.match(/(\d{4})[-/.年](\d{1,2})[-/.月](\d{1,2})日?(?:\D+(\d{1,2}):(\d{2}))?/);
  const date = m ? `${m[1]}-${m[2].padStart(2, "0")}-${m[3].padStart(2, "0")}` : null;
  const startMs = m && m[4] ? Date.parse(`${date}T${m[4].padStart(2, "0")}:${m[5]}:00+08:00`) : date ? dayStartMs(date) : null;
  return {
    // the backend strips id/external_id from rows; fall back to the identity fields
    id: str(r.id ?? r.external_id) || `exam|${str(r.course_code)}|${str(r.title)}|${timeRaw}`,
    title: str(r.title),
    timeRaw,
    location: str(r.location),
    seat: str(r.seat_number),
    campus: str(r.exam_campus ?? r.campus),
    notes: str(r.notes),
    date,
    startMs,
  };
}

/** The five official exam sessions printed on the 2026-27 calendar. */
export const EXAM_SESSIONS = ["08:00–10:00", "10:30–12:30", "13:30–15:30", "16:00–18:00", "19:00–21:30"];

/* ----------------------------------------------------------------- grades */

export interface Grade {
  id: string;
  semester: string;
  title: string;
  /** verbatim: "90" or "优秀" */
  score: string;
  credits: number | null;
  gpa: number | null;
  examType: string;
  marker: string;
  method: string;
}

export function normalizeGrade(raw: unknown): Grade {
  const r = rec(raw);
  return {
    id: str(r.id ?? r.external_id) || `grade|${str(r.semester)}|${str(r.course_code)}|${str(r.title)}|${str(r.exam_type)}`,
    semester: str(r.semester),
    title: str(r.title),
    score: str(r.score),
    credits: num(r.credits),
    gpa: num(r.gpa),
    examType: str(r.exam_type),
    marker: str(r.score_marker),
    method: str(r.exam_method),
  };
}

export interface GradeSummary {
  weightedScore: number | null;
  weightedGpa: number | null;
  credits: number;
  /** credits behind 加权均分 — the weight base, not the same as `credits` */
  countedCredits: number;
  counted: number;
  total: number;
}

/**
 * Same method stu-mcp's academic_summary uses: credit-weighted, non-numeric
 * scores excluded, retakes counted row by row. Not an official figure.
 */
export function summarize(grades: Grade[]): GradeSummary {
  let sw = 0;
  let sc = 0;
  let gw = 0;
  let gc = 0;
  let counted = 0;
  for (const g of grades) {
    const s = num(g.score);
    if (g.credits && s !== null) {
      sw += s * g.credits;
      sc += g.credits;
      counted++;
    }
    if (g.credits && g.gpa !== null) {
      gw += g.gpa * g.credits;
      gc += g.credits;
    }
  }
  return {
    weightedScore: sc ? sw / sc : null,
    weightedGpa: gc ? gw / gc : null,
    credits: grades.reduce((s, g) => s + (g.credits ?? 0), 0),
    countedCredits: sc,
    counted,
    total: grades.length,
  };
}

/* ---------------------------------------------------------------- notices */

export interface Notice {
  id: string;
  title: string;
  url: string | null;
  source: "public" | "oa" | "yuketang" | string;
  /** 学校要闻 / 学生通知 / … for public; department for OA */
  section: string;
  /** verbatim date text from the listing; no time of day exists */
  publishedRaw: string;
  /** day-precision value for sorting */
  publishedMs: number | null;
  /** when it was collected, epoch ms */
  seenMs: number | null;
}

export function normalizeNotice(raw: unknown, seenMs: number | null = null): Notice {
  const r = rec(raw);
  const d = rec(r.detail);
  const pick = (k: string) => r[k] ?? d[k];
  const published = str(pick("published_at"));
  const m = published.match(/(\d{4})\D(\d{1,2})\D(\d{1,2})/);
  return {
    id: str(r.id),
    title: str(pick("title")),
    url: str(pick("url")) || null,
    source: str(r.source) || "public",
    section: str(pick("category")) || str(pick("department")),
    publishedRaw: published,
    publishedMs: m ? dayStartMs(`${m[1]}-${m[2].padStart(2, "0")}-${m[3].padStart(2, "0")}`) : null,
    seenMs,
  };
}

export const SOURCE_NAME: Record<string, string> = {
  public: "学校官网",
  oa: "OA",
  jw: "教务",
  mystu: "MySTU",
  yuketang: "雨课堂",
  charging: "充电平台",
  smartcard: "一卡通",
  repair: "宿舍报修",
};
