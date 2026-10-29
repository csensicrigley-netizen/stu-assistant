/**
 * The school calendar, as published — not inferred from "today minus a date".
 *
 * Source: 教务处《2026-2027学年教学校历》 https://jwc.stu.edu.cn/info/1080/3307.htm
 * (published 2026-05-20, the only and latest version on 2026-10-28), plus the
 * State Council's 2026 holiday notice for dates the school calendar leaves out.
 * See docs/phase0/02-official-sources.md §1.
 *
 * What is NOT known and therefore not guessed:
 *   - which weekday's timetable runs on a 调休 working day (09-20, 10-10);
 *   - 2027 public holidays (not published yet on 2026-10-28).
 */
import { addDaysIso, dayStartMs, moment, nowMs } from "./clock";

export type PhaseKind = "practice" | "teaching" | "exam";
export type ClosureKind = "holiday" | "suspended";

export interface Term {
  id: string;
  label: string;
  /** Monday of 校历 week 1 */
  week1Monday: string;
  totalWeeks: number;
  phases: { from: number; to: number; kind: PhaseKind; label: string }[];
  closures: { from: string; to: string; kind: ClosureKind; label: string }[];
  makeupDays: { date: string; note: string }[];
  /** the break that follows the term */
  after: { label: string; from: string; to: string };
}

export const CALENDAR_SOURCE = {
  title: "2026-2027 学年教学校历",
  publisher: "教务处",
  url: "https://jwc.stu.edu.cn/info/1080/3307.htm",
  publishedOn: "2026-05-20",
  checkedOn: "2026-10-28",
};

export const TERMS: Term[] = [
  {
    id: "2026-2027-1",
    label: "2026–2027 学年 第一学期",
    week1Monday: "2026-09-07",
    totalWeeks: 20,
    phases: [
      { from: 1, to: 2, kind: "practice", label: "实践周" },
      { from: 3, to: 18, kind: "teaching", label: "教学周" },
      { from: 19, to: 20, kind: "exam", label: "考试周" },
    ],
    closures: [
      { from: "2026-09-25", to: "2026-09-27", kind: "holiday", label: "中秋节" },
      { from: "2026-10-01", to: "2026-10-07", kind: "holiday", label: "国庆节" },
      { from: "2026-12-03", to: "2026-12-04", kind: "suspended", label: "校运会停课" },
    ],
    makeupDays: [
      { date: "2026-09-20", note: "国家调休上班日，学校按哪天课表上课尚未公布" },
      { date: "2026-10-10", note: "国家调休上班日，学校按哪天课表上课尚未公布" },
    ],
    after: { label: "寒假", from: "2027-01-23", to: "2027-02-27" },
  },
  {
    id: "2026-2027-2",
    label: "2026–2027 学年 第二学期",
    week1Monday: "2027-03-01",
    totalWeeks: 18,
    phases: [
      { from: 1, to: 16, kind: "teaching", label: "教学周" },
      { from: 17, to: 18, kind: "exam", label: "考试周" },
    ],
    // 2027 public holidays were not published on 2026-10-28; add them when they are.
    closures: [],
    makeupDays: [],
    after: { label: "暑假", from: "2027-07-03", to: "2027-08-28" },
  },
];

const DAY = 86_400_000;

function dayIndex(isoDate: string): number {
  return Math.round(dayStartMs(isoDate) / DAY);
}

export interface TermPosition {
  term: Term;
  week: number;
  phase: Term["phases"][number] | null;
}

/** Where a date sits in the published calendar; null outside every term. */
export function termPosition(isoDate = moment().date): TermPosition | null {
  const today = dayIndex(isoDate);
  for (const term of TERMS) {
    const week = Math.floor((today - dayIndex(term.week1Monday)) / 7) + 1;
    if (week >= 1 && week <= term.totalWeeks) {
      const phase = term.phases.find((p) => week >= p.from && week <= p.to) ?? null;
      return { term, week, phase };
    }
  }
  return null;
}

/** The term a date belongs to, or the next one to start. */
export function currentOrNextTerm(isoDate = moment().date): Term {
  const pos = termPosition(isoDate);
  if (pos) return pos.term;
  const today = dayIndex(isoDate);
  return TERMS.find((t) => dayIndex(t.week1Monday) > today) ?? TERMS[TERMS.length - 1];
}

/** Monday of a 校历 week. */
export function mondayOf(term: Term, week: number): string {
  return addDaysIso(term.week1Monday, (week - 1) * 7);
}

/** The date of a weekday (1–7) in a 校历 week. */
export function dateOf(term: Term, week: number, weekday: number): string {
  return addDaysIso(term.week1Monday, (week - 1) * 7 + (weekday - 1));
}

export function phaseOf(term: Term, week: number) {
  return term.phases.find((p) => week >= p.from && week <= p.to) ?? null;
}

export type DayStatus =
  | { kind: "normal" }
  | { kind: "closed"; closure: ClosureKind; label: string }
  | { kind: "makeup"; note: string }
  | { kind: "outside" };

/** Is there regular teaching on this date, per the published sources? */
export function dayStatus(isoDate: string): DayStatus {
  const pos = termPosition(isoDate);
  if (!pos) return { kind: "outside" };
  const today = dayIndex(isoDate);
  const closure = pos.term.closures.find(
    (c) => today >= dayIndex(c.from) && today <= dayIndex(c.to),
  );
  if (closure) return { kind: "closed", closure: closure.kind, label: closure.label };
  const makeup = pos.term.makeupDays.find((m) => m.date === isoDate);
  if (makeup) return { kind: "makeup", note: makeup.note };
  return { kind: "normal" };
}

/** Upcoming calendar events worth a glance: closures, exam weeks, the break. */
export function upcomingMilestones(isoDate = moment().date, limit = 4) {
  const today = dayIndex(isoDate);
  const out: { label: string; from: string; to: string; days: number; kind: string }[] = [];
  for (const term of TERMS) {
    for (const c of term.closures) {
      if (dayIndex(c.to) >= today) out.push({ label: c.label, from: c.from, to: c.to, days: dayIndex(c.from) - today, kind: c.kind });
    }
    for (const p of term.phases) {
      if (p.kind !== "exam") continue;
      const from = mondayOf(term, p.from);
      const to = addDaysIso(mondayOf(term, p.to), 4);
      if (dayIndex(to) >= today) out.push({ label: `${term.id.endsWith("1") ? "秋季" : "春季"}考试周`, from, to, days: dayIndex(from) - today, kind: "exam" });
    }
    if (dayIndex(term.after.to) >= today) {
      out.push({ label: term.after.label, from: term.after.from, to: term.after.to, days: dayIndex(term.after.from) - today, kind: "break" });
    }
  }
  return out.sort((a, b) => a.days - b.days).slice(0, limit);
}

/** 0..1 through the term, for a progress line. */
export function termProgress(term: Term, ms = nowMs()): number {
  const start = dayStartMs(term.week1Monday);
  const end = dayStartMs(addDaysIso(term.week1Monday, term.totalWeeks * 7));
  return Math.min(1, Math.max(0, (ms - start) / (end - start)));
}
