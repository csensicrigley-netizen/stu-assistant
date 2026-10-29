/**
 * Timetable domain.
 *
 * Input is stu-mcp's `get_academic_timetable` document as-is: courses with
 * meetings, each meeting carrying `weekday`, `weeks: number[]` and
 * `periods: number[]` (integers, see sections.ts). See
 * docs/phase0/01-data-contract.md §2.1.
 *
 * Rules this file keeps:
 *   - location strings are shown verbatim; the school's room naming is not
 *     documented anywhere, so nothing splits "E座 9-301" into parts;
 *   - a visible block never spans lunch or dinner, so free time stays visible;
 *   - adjacent meetings of the same course merge only across the official
 *     20-minute big-section break (stu-mcp returns 6-7 and 8-9 separately).
 */
import {
  BIG_BY_ID,
  GRID,
  PERIOD_BY_INDEX,
  joinable,
  knownPeriods,
  toClock,
  type BigId,
} from "./sections";

/* ------------------------------------------------------------------ input */

export interface Meeting {
  id: string;
  courseId: string;
  title: string;
  className: string;
  weekday: number;
  weeks: number[];
  periods: number[];
  teacher: string;
  location: string;
  rawTime: string;
  notes: string;
  onlineInfo: string;
}

export interface Course {
  id: string;
  title: string;
  code: string;
  className: string;
  teacher: string;
  meetings: Meeting[];
  hue: number;
}

export interface Unarranged {
  id: string;
  title: string;
  className: string;
  teachers: string[];
}

export interface TimetableDoc {
  semester: string;
  checkedAt: string | null;
  status: "ok" | "partial";
  courses: Course[];
  unarranged: Unarranged[];
  pending: string[];
  complete: boolean;
}

const str = (v: unknown): string => (typeof v === "string" ? v : v == null ? "" : String(v));

/** "3-18", "1,3,5-9", [3,4,5] -> sorted unique ints */
function parseWeeks(v: unknown): number[] {
  if (Array.isArray(v)) return [...new Set(v.map(Number).filter((n) => Number.isInteger(n) && n > 0))].sort((a, b) => a - b);
  const text = str(v).replace(/[（(].*?[)）]/g, "");
  const out = new Set<number>();
  for (const part of text.split(/[,，、]/)) {
    const m = part.trim().match(/^(\d+)(?:\s*[-–~]\s*(\d+))?$/);
    if (!m) continue;
    const a = Number(m[1]);
    const b = m[2] ? Number(m[2]) : a;
    for (let w = Math.min(a, b); w <= Math.max(a, b) && w - a < 60; w++) out.add(w);
  }
  return [...out].sort((x, y) => x - y);
}

/**
 * Seven course hues between green (145°) and magenta (340°), about 33° apart.
 * The arc 345°–140° is left out on purpose: cinnabar (32°) means "now / due",
 * amber (70°) means "warning", and the olive greens below 140° turn muddy at
 * the lightness course blocks use. Listed in order of preference.
 */
const HUES = [250, 145, 312, 215, 282, 180, 340];

function hueGap(a: number, b: number): number {
  const d = Math.abs(a - b) % 360;
  return Math.min(d, 360 - d);
}

/**
 * Colour courses like a map: two courses that meet on the same weekday get
 * hues as far apart as the palette allows, so a day never shows two
 * look-alike blocks. Busiest courses are coloured first.
 */
function assignHues(rawCourses: unknown[]): Map<string, number> {
  const days = new Map<string, Set<number>>();
  const load = new Map<string, number>();
  for (const c of rawCourses) {
    const course = c as Record<string, unknown>;
    const title = str(course.title);
    const set = days.get(title) ?? new Set<number>();
    const meetings = Array.isArray(course.meetings) ? course.meetings : [];
    for (const m of meetings) {
      const wd = Number((m as Record<string, unknown>).weekday);
      if (wd >= 1 && wd <= 7) set.add(wd);
    }
    days.set(title, set);
    load.set(title, (load.get(title) ?? 0) + meetings.length);
  }

  const titles = [...days.keys()].sort(
    (a, b) => days.get(b)!.size - days.get(a)!.size || load.get(b)! - load.get(a)! || a.localeCompare(b, "zh-CN"),
  );
  const hue = new Map<string, number>();
  const uses = new Map<number, number>();
  for (const title of titles) {
    const mine = days.get(title)!;
    const neighbours = titles.filter(
      (t) => t !== title && hue.has(t) && [...days.get(t)!].some((d) => mine.has(d)),
    );
    let best = HUES[0];
    let bestScore = -Infinity;
    HUES.forEach((h, i) => {
      const gap = neighbours.length ? Math.min(...neighbours.map((t) => hueGap(h, hue.get(t)!))) : 180;
      // nearest same-day colour first, then palette spread, then preference
      const score = gap * 100 - (uses.get(h) ?? 0) * 10 - i;
      if (score > bestScore) {
        bestScore = score;
        best = h;
      }
    });
    hue.set(title, best);
    uses.set(best, (uses.get(best) ?? 0) + 1);
  }
  return hue;
}

/** Accepts stu-mcp's document or the backend's {semester, courses, …} wrapper. */
export function normalizeTimetable(raw: unknown): TimetableDoc | null {
  if (!raw || typeof raw !== "object") return null;
  const doc = raw as Record<string, unknown>;
  const rawCourses = Array.isArray(doc.courses) ? doc.courses : [];

  const used = assignHues(rawCourses);

  const courses: Course[] = rawCourses.map((c, ci) => {
    const course = c as Record<string, unknown>;
    const id = str(course.id) || `course-${ci}`;
    const title = str(course.title) || "未命名课程";
    const className = str(course.class_name);
    const teachers = Array.isArray(course.teachers) ? course.teachers.map(str).filter(Boolean) : [];
    const teacher = str(course.teacher) || teachers.join("、");
    const meetings: Meeting[] = (Array.isArray(course.meetings) ? course.meetings : []).map((m, mi) => {
      const meet = m as Record<string, unknown>;
      const location = str(meet.location) || [str(meet.building), str(meet.room)].filter(Boolean).join(" ");
      return {
        id: str(meet.id) || `${id}-m${mi}`,
        courseId: id,
        title,
        className,
        weekday: Number(meet.weekday) || 0,
        weeks: parseWeeks(meet.weeks),
        periods: knownPeriods(Array.isArray(meet.periods) ? meet.periods.map(Number) : []),
        teacher: str(meet.teacher) || teacher,
        location,
        rawTime: str(meet.raw_time),
        notes: str(meet.notes),
        onlineInfo: str(meet.online_info),
      };
    }).filter((m) => m.weekday >= 1 && m.weekday <= 7 && m.periods.length > 0);
    return { id, title, code: str(course.course_code), className, teacher, meetings, hue: used.get(title) ?? HUES[0] };
  });

  const unarranged: Unarranged[] = (Array.isArray(doc.unarranged) ? doc.unarranged : []).map((u, i) => {
    const row = u as Record<string, unknown>;
    return {
      id: str(row.id) || `un-${i}`,
      title: str(row.title),
      className: str(row.class_name),
      teachers: Array.isArray(row.teachers) ? row.teachers.map(str) : [],
    };
  });

  const coverage = (doc.coverage ?? {}) as Record<string, unknown>;
  const pending = Array.isArray(doc.pending) ? doc.pending.map(str).filter(Boolean) : [];
  return {
    semester: str(doc.semester),
    checkedAt: str(doc.checked_at) || null,
    status: doc.status === "partial" || pending.length > 0 || unarranged.length > 0 ? "partial" : "ok",
    courses,
    unarranged,
    pending,
    complete: coverage.complete !== false,
  };
}

/* --------------------------------------------------------------- blocks */

export interface Block {
  key: string;
  course: Course;
  meetings: Meeting[];
  weekday: number;
  periods: number[];
  start: string;
  end: string;
  startMin: number;
  endMin: number;
  /** wall-clock span, breaks inside included */
  span: number;
  location: string;
  teacher: string;
  bigIds: BigId[];
  /** some period's printed code (0/A/B/C) is inferred, not confirmed */
  inferred: boolean;
  /** overlaps another block on the same day */
  conflict: boolean;
}

function runsOf(periods: number[]): number[][] {
  const runs: number[][] = [];
  for (const p of periods) {
    const last = runs[runs.length - 1];
    if (last && joinable(last[last.length - 1], p)) last.push(p);
    else runs.push([p]);
  }
  return runs;
}

function blockFrom(course: Course, meetings: Meeting[], weekday: number, periods: number[]): Block {
  const first = PERIOD_BY_INDEX.get(periods[0])!;
  const last = PERIOD_BY_INDEX.get(periods[periods.length - 1])!;
  const bigIds: BigId[] = [];
  for (const p of periods) {
    const b = PERIOD_BY_INDEX.get(p)!.big;
    if (!bigIds.includes(b)) bigIds.push(b);
  }
  const locations = [...new Set(meetings.map((m) => m.location).filter(Boolean))];
  const teachers = [...new Set(meetings.map((m) => m.teacher).filter(Boolean))];
  return {
    key: `${course.id}|${weekday}|${periods.join(",")}`,
    course,
    meetings,
    weekday,
    periods,
    start: first.start,
    end: last.end,
    startMin: first.startMin,
    endMin: last.endMin,
    span: last.endMin - first.startMin,
    location: locations.join(" / "),
    teacher: teachers.join("、"),
    bigIds,
    inferred: periods.some((p) => PERIOD_BY_INDEX.get(p)!.codeInferred),
    conflict: false,
  };
}

/** Blocks for one weekday of one 校历 week, ordered by start. */
export function blocksOn(doc: TimetableDoc | null, week: number, weekday: number): Block[] {
  if (!doc) return [];
  const blocks: Block[] = [];

  for (const course of doc.courses) {
    const todays = course.meetings.filter((m) => m.weekday === weekday && m.weeks.includes(week));
    if (todays.length === 0) continue;

    // group meetings by location; same course in two rooms is two blocks
    const byPlace = new Map<string, Meeting[]>();
    for (const m of todays) {
      const k = m.location;
      byPlace.set(k, [...(byPlace.get(k) ?? []), m]);
    }
    for (const group of byPlace.values()) {
      const periods = knownPeriods(group.flatMap((m) => m.periods));
      for (const run of runsOf(periods)) {
        const owners = group.filter((m) => m.periods.some((p) => run.includes(p)));
        blocks.push(blockFrom(course, owners, weekday, run));
      }
    }
  }

  blocks.sort((a, b) => a.startMin - b.startMin || a.endMin - b.endMin);
  for (let i = 0; i < blocks.length; i++) {
    for (let j = i + 1; j < blocks.length; j++) {
      if (blocks[j].startMin < blocks[i].endMin) {
        blocks[i].conflict = true;
        blocks[j].conflict = true;
      }
    }
  }
  return blocks;
}

/* ------------------------------------------------------------------ gaps */

export interface Gap {
  start: string;
  end: string;
  startMin: number;
  endMin: number;
  span: number;
  label: string;
}

function gapLabel(startMin: number, endMin: number): string {
  const mid = (startMin + endMin) / 2;
  if (mid < 12 * 60) return "上午空闲";
  if (mid < 14 * 60) return "午间";
  if (mid < 18 * 60) return "下午空闲";
  if (mid < 19.5 * 60) return "傍晚";
  return "晚上空闲";
}

/**
 * Free stretches inside the teaching day (08:00–21:55) of at least `min`
 * minutes. Only meaningful on a day that has classes.
 */
export function gapsBetween(blocks: Block[], min = 40): Gap[] {
  if (blocks.length === 0) return [];
  const gaps: Gap[] = [];
  let cursor = GRID.startMin;
  const push = (a: number, b: number) => {
    if (b - a >= min) gaps.push({ start: toClock(a), end: toClock(b), startMin: a, endMin: b, span: b - a, label: gapLabel(a, b) });
  };
  for (const b of blocks) {
    push(cursor, b.startMin);
    cursor = Math.max(cursor, b.endMin);
  }
  push(cursor, GRID.endMin);
  return gaps;
}

/* ------------------------------------------------------------- summaries */

export function minutesOf(blocks: Block[]): number {
  return blocks.reduce((s, b) => s + b.span, 0);
}

export interface Hop {
  from: string;
  to: string;
  /** minutes between the end of one class and the start of the next */
  window: number;
}

/** Room changes during a day, in order; same room twice in a row is not a hop. */
export function roomHops(blocks: Block[]): Hop[] {
  const hops: Hop[] = [];
  for (let i = 1; i < blocks.length; i++) {
    const a = blocks[i - 1];
    const b = blocks[i];
    if (a.location && b.location && a.location !== b.location) {
      hops.push({ from: a.location, to: b.location, window: b.startMin - a.endMin });
    }
  }
  return hops;
}

/** Which periods of which day a course occupies across the term, for the detail sheet. */
export function courseSlots(course: Course) {
  const slots = new Map<string, { weekday: number; periods: number[]; location: string; teacher: string; weeks: number[]; rawTimes: string[]; notes: string[]; online: string[] }>();
  for (const m of course.meetings) {
    const key = `${m.weekday}|${m.location}|${m.teacher}|${m.weeks.join(",")}`;
    const cur = slots.get(key) ?? { weekday: m.weekday, periods: [], location: m.location, teacher: m.teacher, weeks: m.weeks, rawTimes: [], notes: [], online: [] };
    cur.periods = knownPeriods([...cur.periods, ...m.periods]);
    if (m.rawTime && !cur.rawTimes.includes(m.rawTime)) cur.rawTimes.push(m.rawTime);
    if (m.notes && !cur.notes.includes(m.notes)) cur.notes.push(m.notes);
    if (m.onlineInfo && !cur.online.includes(m.onlineInfo)) cur.online.push(m.onlineInfo);
    slots.set(key, cur);
  }
  return [...slots.values()].sort((a, b) => a.weekday - b.weekday || a.periods[0] - b.periods[0]);
}

/** How many class days a course has in the term, and how many are already behind. */
export function courseProgress(course: Course, week: number, weekday: number, nowMin: number) {
  const occurrences = new Set<string>();
  const done = new Set<string>();
  for (const m of course.meetings) {
    for (const w of m.weeks) {
      const k = `${w}|${m.weekday}`;
      occurrences.add(k);
      const lastPeriod = PERIOD_BY_INDEX.get(m.periods[m.periods.length - 1]);
      const past = w < week || (w === week && (m.weekday < weekday || (m.weekday === weekday && lastPeriod && lastPeriod.endMin <= nowMin)));
      if (past) done.add(k);
    }
  }
  return { total: occurrences.size, done: done.size };
}

/** [3,4,5,6,7,8,11] -> "3–8、11 周" */
export function weeksText(weeks: number[]): string {
  if (weeks.length === 0) return "周次未标注";
  const runs: [number, number][] = [];
  for (const w of weeks) {
    const last = runs[runs.length - 1];
    if (last && w === last[1] + 1) last[1] = w;
    else runs.push([w, w]);
  }
  const odd = weeks.length > 2 && weeks.every((w, i) => i === 0 || w - weeks[i - 1] === 2);
  if (odd) return `${weeks[0]}–${weeks[weeks.length - 1]} ${weeks[0] % 2 ? "单" : "双"}周`;
  return `${runs.map(([a, b]) => (a === b ? `${a}` : `${a}–${b}`)).join("、")} 周`;
}

/** "第 3–5 节" with printed codes: periods 8,9,10 -> "8、9、0 节" */
export function periodsText(periods: number[]): string {
  const codes = periods.map((p) => PERIOD_BY_INDEX.get(p)?.code ?? String(p));
  if (periods.length >= 3 && periods.every((p, i) => i === 0 || p === periods[i - 1] + 1) && periods[periods.length - 1] < 10) {
    return `第 ${codes[0]}–${codes[codes.length - 1]} 节`;
  }
  return `第 ${codes.join("、")} 节`;
}

export function bigNames(ids: BigId[]): string {
  return ids.map((id) => BIG_BY_ID.get(id)!.name.replace("第", "").replace("大节", "")).join("、") + " 大节";
}
