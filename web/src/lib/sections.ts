/**
 * The section clock — official, not reverse-engineered.
 *
 * Source: 教务处《汕头大学课堂作息时间表》 https://jwc.stu.edu.cn/info/1080/1939.htm
 * and the class-time table printed on the 2026-2027 校历; both checked
 * 2026-10-28 and identical. See docs/phase0/02-official-sources.md §2.
 *
 * 13 periods, printed as 1–9, 0, A, B, C, grouped into 5 big sections. Each
 * period is 45 minutes; 10 minutes between periods, 20 between big sections.
 * The big sections are NOT equal: 100 / 155 / 100 / 155 / 155 minutes.
 *
 * stu-mcp returns `periods` as integers. 1–9 are the printed codes; reading
 * 10–13 as 0/A/B/C is an inference no real sample has confirmed yet, so those
 * periods carry `codeInferred` and the UI must say so.
 */

export type BigId = 1 | 2 | 3 | 4 | 5;

export interface Period {
  /** the integer stu-mcp uses */
  index: number;
  /** what the school prints */
  code: string;
  big: BigId;
  start: string;
  end: string;
  startMin: number;
  endMin: number;
  codeInferred: boolean;
}

export interface BigSection {
  id: BigId;
  name: string;
  periods: number[];
  start: string;
  end: string;
  startMin: number;
  endMin: number;
  duration: number;
}

export function toMin(clock: string): number {
  const [h, m] = clock.split(":").map(Number);
  return h * 60 + m;
}

export function toClock(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

const p = (index: number, code: string, big: BigId, start: string, end: string): Period => ({
  index,
  code,
  big,
  start,
  end,
  startMin: toMin(start),
  endMin: toMin(end),
  codeInferred: index >= 10,
});

export const PERIODS: Period[] = [
  p(1, "1", 1, "08:00", "08:45"),
  p(2, "2", 1, "08:55", "09:40"),
  p(3, "3", 2, "10:00", "10:45"),
  p(4, "4", 2, "10:55", "11:40"),
  p(5, "5", 2, "11:50", "12:35"),
  p(6, "6", 3, "14:00", "14:45"),
  p(7, "7", 3, "14:55", "15:40"),
  p(8, "8", 4, "16:00", "16:45"),
  p(9, "9", 4, "16:55", "17:40"),
  p(10, "0", 4, "17:50", "18:35"),
  p(11, "A", 5, "19:20", "20:05"),
  p(12, "B", 5, "20:15", "21:00"),
  p(13, "C", 5, "21:10", "21:55"),
];

export const PERIOD_BY_INDEX = new Map<number, Period>(PERIODS.map((x) => [x.index, x]));

const BIG_NAMES: Record<BigId, string> = {
  1: "第一大节",
  2: "第二大节",
  3: "第三大节",
  4: "第四大节",
  5: "第五大节",
};

export const BIG_SECTIONS: BigSection[] = ([1, 2, 3, 4, 5] as BigId[]).map((id) => {
  const members = PERIODS.filter((x) => x.big === id);
  const first = members[0];
  const last = members[members.length - 1];
  return {
    id,
    name: BIG_NAMES[id],
    periods: members.map((x) => x.index),
    start: first.start,
    end: last.end,
    startMin: first.startMin,
    endMin: last.endMin,
    duration: last.endMin - first.startMin,
  };
});

export const BIG_BY_ID = new Map<BigId, BigSection>(BIG_SECTIONS.map((s) => [s.id, s]));

/** The school writes big-section names in Chinese; map them back to ids. */
const BIG_NAME_TO_ID: Record<string, BigId> = {
  第一大节: 1,
  第二大节: 2,
  第三大节: 3,
  第四大节: 4,
  第五大节: 5,
  晚上: 5,
  晚课: 5,
};

export function bigIdFromName(name: string): BigId | null {
  const trimmed = name.trim();
  if (BIG_NAME_TO_ID[trimmed]) return BIG_NAME_TO_ID[trimmed];
  // tolerate "第二大节(10:00-12:35)" style suffixes
  for (const [key, id] of Object.entries(BIG_NAME_TO_ID)) {
    if (trimmed.startsWith(key)) return id;
  }
  return null;
}

/** Only the periods this clock knows, sorted and de-duplicated. */
export function knownPeriods(periods: number[]): number[] {
  return [...new Set(periods)].filter((i) => PERIOD_BY_INDEX.has(i)).sort((a, b) => a - b);
}

/**
 * Two periods can sit in one visible block when nothing longer than the
 * official 20-minute big-section break separates them. Lunch (85 min) and
 * dinner (45 min) always split a block, so free time is never hidden.
 */
export const MAX_JOIN_GAP = 20;

export function joinable(a: number, b: number): boolean {
  const pa = PERIOD_BY_INDEX.get(a);
  const pb = PERIOD_BY_INDEX.get(b);
  if (!pa || !pb || b !== a + 1) return false;
  return pb.startMin - pa.endMin <= MAX_JOIN_GAP;
}

/** Big sections a run of periods touches, in order. */
export function bigIdsOf(periods: number[]): BigId[] {
  const out: BigId[] = [];
  for (const i of periods) {
    const big = PERIOD_BY_INDEX.get(i)?.big;
    if (big && !out.includes(big)) out.push(big);
  }
  return out;
}

/** Real minutes in class: each big section's span, breaks between them excluded. */
export function durationOf(periods: number[]): number {
  let total = 0;
  for (const big of bigIdsOf(periods)) {
    const inBig = periods
      .map((i) => PERIOD_BY_INDEX.get(i)!)
      .filter((x) => x.big === big);
    total += inBig[inBig.length - 1].endMin - inBig[0].startMin;
  }
  return total;
}

/** 155 -> "2 小时 35 分" */
export function humanDuration(minutes: number): string {
  if (minutes <= 0) return "0 分";
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h === 0) return `${m} 分`;
  if (m === 0) return `${h} 小时`;
  return `${h} 小时 ${m} 分`;
}

/** Weekday labels: the school timetable counts Monday as 1. */
export const WEEKDAY_LABELS = ["一", "二", "三", "四", "五", "六", "日"];

/** The teaching day the grids draw: 08:00 – 21:55. */
export const GRID = {
  startMin: PERIODS[0].startMin,
  endMin: PERIODS[PERIODS.length - 1].endMin,
};
