/**
 * Joins the timetable to the calendar: "what happens on this date".
 * A date outside the term, or a published closure, has no classes even if the
 * weekly grid says otherwise.
 */
import { addDaysIso, moment } from "./clock";
import { dayStatus, termPosition, type DayStatus } from "./calendar";
import { blocksOn, type Block, type TimetableDoc } from "./timetable";

export interface DayPlan {
  date: string;
  weekday: number;
  week: number | null;
  status: DayStatus;
  blocks: Block[];
}

function weekdayOf(isoDate: string): number {
  return moment(Date.parse(`${isoDate}T12:00:00+08:00`)).weekday;
}

export function planFor(doc: TimetableDoc | null, isoDate: string): DayPlan {
  const pos = termPosition(isoDate);
  const weekday = weekdayOf(isoDate);
  const status = dayStatus(isoDate);
  if (!pos || status.kind === "closed") {
    return { date: isoDate, weekday, week: pos?.week ?? null, status, blocks: [] };
  }
  return { date: isoDate, weekday, week: pos.week, status, blocks: blocksOn(doc, pos.week, weekday) };
}

/** The next class strictly after (date, minute), looking ahead up to `days`. */
export function nextClass(doc: TimetableDoc | null, isoDate: string, afterMin: number, days = 14) {
  for (let i = 0; i <= days; i++) {
    const date = addDaysIso(isoDate, i);
    const plan = planFor(doc, date);
    const hit = plan.blocks.find((b) => i > 0 || b.startMin > afterMin);
    if (hit) return { date, offset: i, block: hit, plan };
  }
  return null;
}
