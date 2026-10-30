/**
 * 课表导入（手动录入）的前端客户端。
 *
 * 医学院的教务系统和主校区不一样，没法自动同步，只能让学生自己录。
 * 录完之后这份课表必须和自动同步的课表**一模一样地显示** —— 所以这里
 * 不发明任何新形状：解析结果先过一遍 `normalizeTimetable()`，
 * 拿到的就是 Week / Today 那两个页面正在用的 `Course` / `Meeting`。
 *
 * 服务端约定（main.py 挂 5 个路由）：
 *   POST   /v1/timetable/import/parse      {text, format, semester}
 *   POST   /v1/timetable/import/save       {doc, school, note}
 *   GET    /v1/timetable/manual?semester=
 *   DELETE /v1/timetable/manual?semester=
 *   GET    /v1/timetable/import/template   → text/csv
 */
import { request, requestText } from "./http";
import { normalizeTimetable, type Course, type TimetableDoc, type Unarranged } from "./timetable";

/* ------------------------------------------------------------------ 形状 */

export interface ImportStats {
  courses: number;
  meetings: number;
  /** [最早周, 最晚周]；一门课都没排上时间时是 [0, 0] */
  weeks: [number, number];
}

export interface ImportPreview {
  ok: boolean;
  courses: Course[];
  unarranged: Unarranged[];
  errors: string[];
  warnings: string[];
  stats: ImportStats;
}

/** 三个入口，和后端 timetable_import.SCHOOLS 一一对应。 */
export interface SchoolOption {
  id: SchoolId;
  name: string;
  note: string;
}

export type SchoolId = "main" | "med" | "other";

export const SCHOOLS: SchoolOption[] = [
  { id: "main", name: "主校区", note: "教务系统可自动同步" },
  { id: "med", name: "医学院", note: "教务系统不同，需手动录入" },
  { id: "other", name: "其他", note: "自行录入" },
];

export const SCHOOL_NAME: Record<string, string> = Object.fromEntries(
  SCHOOLS.map((s) => [s.id, s.name]),
);

/** 医学院那句额外的说明，选医学院时才出现。 */
export const MED_NOTE = "医学院教务系统和主校区不同，需要你自己录入；录完之后和自动同步的课表一样显示。";

export type ImportFormat = "paste" | "csv";

export const EMPTY_PREVIEW: ImportPreview = {
  ok: false,
  courses: [],
  unarranged: [],
  errors: [],
  warnings: [],
  stats: { courses: 0, meetings: 0, weeks: [0, 0] },
};

/* ------------------------------------------------------------- 传输层 */

function call<T>(path: string, init: RequestInit = {}, timeoutMs = 20_000): Promise<T> {
  // Preserve the import client's single-attempt behavior, including saves.
  return request<T>(path, init, timeoutMs, undefined, 0);
}

const callText = requestText;

/* ------------------------------------------------------- 服务端 → 前端 */

interface RawPreview {
  ok?: unknown;
  semester?: unknown;
  courses?: unknown;
  unarranged?: unknown;
  errors?: unknown;
  warnings?: unknown;
  stats?: { courses?: unknown; meetings?: unknown; weeks?: unknown };
}

const strList = (v: unknown): string[] =>
  Array.isArray(v) ? v.map((x) => String(x ?? "").trim()).filter(Boolean) : [];

function statsOf(raw: RawPreview, doc: TimetableDoc | null): ImportStats {
  const weeks = raw.stats?.weeks;
  const pair: [number, number] =
    Array.isArray(weeks) && weeks.length >= 2
      ? [Number(weeks[0]) || 0, Number(weeks[1]) || 0]
      : [0, 0];
  if (doc) {
    const meetings = doc.courses.reduce((n, c) => n + c.meetings.length, 0);
    return {
      courses: doc.courses.length,
      meetings,
      weeks: pair[1] > 0 ? pair : weeksRangeOf(doc),
    };
  }
  return {
    courses: Number(raw.stats?.courses) || 0,
    meetings: Number(raw.stats?.meetings) || 0,
    weeks: pair,
  };
}

function weeksRangeOf(doc: TimetableDoc): [number, number] {
  const all = doc.courses.flatMap((c) => c.meetings.flatMap((m) => m.weeks));
  return all.length ? [Math.min(...all), Math.max(...all)] : [0, 0];
}

/**
 * 服务端那份课表 → 页面正在用的形状。
 *
 * 关键一步是走 `normalizeTimetable()`：它做的正是 Week / Today 显示时做的事
 * （认 weeks / periods、按 title 分配颜色、丢掉没星期的 meeting）。
 * 于是预览里的色块和导入之后课表里的色块**长得一模一样**，
 * 学生在这一步就能看出对不对，而不是导入完才发现歪了。
 */
function toPreview(raw: RawPreview, semester: string): ImportPreview {
  const doc = normalizeTimetable({
    semester: typeof raw.semester === "string" && raw.semester ? raw.semester : semester,
    status: "ok",
    courses: raw.courses,
    unarranged: raw.unarranged,
    pending: [],
    coverage: { complete: true },
  });
  const errors = strList(raw.errors);
  const warnings = strList(raw.warnings);
  if (!doc) {
    return { ...EMPTY_PREVIEW, errors: errors.length ? errors : ["服务端返回的课表看不懂"], warnings };
  }
  return {
    ok: raw.ok === true && errors.length === 0,
    courses: doc.courses,
    unarranged: doc.unarranged,
    errors,
    warnings,
    stats: statsOf(raw, doc),
  };
}

/* --------------------------------------------------------------- 接口 */

export const importApi = {
  /** 把粘进来 / 读进来的文本交给服务端解析，拿回预览。 */
  parse: async (text: string, format: ImportFormat, semester: string): Promise<ImportPreview> => {
    const raw = await call<RawPreview>("/v1/timetable/import/parse", {
      method: "POST",
      body: JSON.stringify({ text, format, semester }),
    });
    return toPreview(raw, semester);
  },

  /** 确认导入。服务端会再校验一遍，然后同时写手动表和 items 镜像。 */
  save: (doc: TimetableDoc, opts: { school: string; note?: string }) =>
    call<{ ok: boolean; semester: string; errors?: string[]; warnings?: string[] }>(
      "/v1/timetable/import/save",
      {
        method: "POST",
        body: JSON.stringify({ doc: toServerDoc(doc), school: opts.school, note: opts.note ?? "" }),
      },
    ),

  /** 这个学期有没有手动录过。 */
  load: async (semester: string) => {
    const raw = await call<{
      doc?: unknown;
      semester?: unknown;
      school?: unknown;
      source?: unknown;
    }>(`/v1/timetable/manual?semester=${encodeURIComponent(semester)}`);
    const doc = raw?.doc && typeof raw.doc === "object" ? normalizeTimetable(raw.doc) : null;
    return {
      doc,
      school: typeof raw?.school === "string" ? raw.school : "",
      source: typeof raw?.source === "string" ? raw.source : "",
    };
  },

  remove: (semester: string) =>
    call<{ ok: boolean }>(`/v1/timetable/manual?semester=${encodeURIComponent(semester)}`, {
      method: "DELETE",
    }),

  /** CSV 模板（带 BOM，Excel 双击能认）。 */
  template: () => callText("/v1/timetable/import/template"),
};

/* ------------------------------------------------------- 本地小工具 */

/**
 * 把页面用的 `Course` 翻回服务端的 snake_case 形状。
 *
 * 预览里的课是 `normalizeTimetable()` 的产物（`className` / `rawTime` /
 * `onlineInfo` 都是驼峰），直接回传会让 `class_name`、`raw_time` 静默丢掉。
 * 这里显式翻一遍，保证「预览看到的就是存进去的」。
 */
function toServerCourse(course: Course): Record<string, unknown> {
  return {
    id: course.id,
    title: course.title,
    course_code: course.code,
    class_name: course.className,
    teacher: course.teacher,
    teachers: course.teacher ? [course.teacher] : [],
    meetings: course.meetings.map((m) => ({
      id: m.id,
      course_id: course.id,
      title: m.title || course.title,
      class_name: m.className || course.className,
      weekday: m.weekday,
      weeks: m.weeks,
      periods: m.periods,
      teacher: m.teacher,
      location: m.location,
      raw_time: m.rawTime,
      notes: m.notes,
      online_info: m.onlineInfo,
    })),
  };
}

function toServerUnarranged(row: Unarranged): Record<string, unknown> {
  return {
    id: row.id,
    title: row.title,
    class_name: row.className,
    teachers: row.teachers,
  };
}

/**
 * `TimetableDoc`（驼峰，页面用的）→ 服务端要的 snake_case 文档。
 *
 * `save()` 内部会走这一步，所以调用方只管把 `docFromPreview()` 的结果递进来。
 */
export function toServerDoc(doc: TimetableDoc): Record<string, unknown> {
  return {
    semester: doc.semester,
    status: doc.status,
    checked_at: doc.checkedAt,
    courses: doc.courses.map(toServerCourse),
    unarranged: doc.unarranged.map(toServerUnarranged),
    pending: doc.pending,
    coverage: { kind: "theory", weeks: "all", complete: doc.complete, source: "manual" },
  };
}

/** 把课程列表重新包成一份完整文档，交给 save。 */
export function docFromPreview(
  preview: ImportPreview,
  semester: string,
  unarranged: Unarranged[] = preview.unarranged,
): TimetableDoc {
  return {
    semester,
    checkedAt: null,
    status: unarranged.length > 0 ? "partial" : "ok",
    courses: preview.courses,
    unarranged,
    pending: [],
    complete: true,
  };
}

/** "1–16 周" / "1、3、5 周"：只给预览里那句统计用。 */
export function weeksRangeText(range: [number, number]): string {
  if (!range[1]) return "周次未标注";
  return range[0] === range[1] ? `第 ${range[0]} 周` : `${range[0]}–${range[1]} 周`;
}
