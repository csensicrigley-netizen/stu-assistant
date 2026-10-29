import { GRID } from "../lib/sections";
import type { Block } from "../lib/timetable";

/**
 * The day as one horizontal strip, 08:00 → 21:55, to scale.
 *
 * Width is real minutes, so a 155-minute block is visibly longer than a
 * 100-minute one, and the two meal breaks the school builds into the day are
 * shaded so the eye finds them without reading a single number.
 */
const SPAN = GRID.endMin - GRID.startMin;
const pct = (min: number) => `${((min - GRID.startMin) / SPAN) * 100}%`;
const width = (a: number, b: number) => `${((b - a) / SPAN) * 100}%`;

/** lunch 12:35–14:00, dinner 18:35–19:20 (official timetable) */
const MEALS: [number, number][] = [
  [12 * 60 + 35, 14 * 60],
  [18 * 60 + 35, 19 * 60 + 20],
];

const TICKS = [8, 10, 12, 14, 16, 18, 20, 22];

export function DayRibbon({
  blocks,
  nowMin,
  compact = false,
  onSelect,
  labelled = !compact,
  selectedKey,
}: {
  blocks: Block[];
  nowMin?: number | null;
  compact?: boolean;
  onSelect?: (b: Block) => void;
  labelled?: boolean;
  selectedKey?: string | null;
}) {
  const showNow = nowMin != null && nowMin >= GRID.startMin && nowMin <= GRID.endMin;
  return (
    <div className={`ribbon${compact ? " ribbon--compact" : ""}`}>
      <div className="ribbon__track">
        {MEALS.map(([a, b]) => (
          <span key={a} className="ribbon__meal" style={{ left: pct(a), width: width(a, b) }} aria-hidden="true" />
        ))}
        {blocks.map((b) => {
          const style = { left: pct(b.startMin), width: width(b.startMin, b.endMin), "--h": b.course.hue } as React.CSSProperties;
          const past = nowMin != null && b.endMin <= nowMin;
          const cls = `ribbon__seg${past ? " is-past" : ""}${selectedKey === b.key ? " is-sel" : ""}${b.conflict ? " is-conflict" : ""}`;
          return onSelect ? (
            <button
              key={b.key}
              type="button"
              className={cls}
              style={style}
              onClick={() => onSelect(b)}
              aria-label={`${b.start} 到 ${b.end}，${b.course.title}，${b.location || "地点未标注"}`}
            />
          ) : (
            <span key={b.key} className={cls} style={style} aria-hidden="true" />
          );
        })}
        {showNow && <span className="ribbon__now" style={{ left: pct(nowMin!) }} aria-hidden="true" />}
      </div>
      {labelled && (
        <div className="ribbon__ticks" aria-hidden="true">
          {TICKS.map((h) => (
            <span key={h} style={{ left: pct(Math.min(h * 60, GRID.endMin)) }}>
              {h}
            </span>
          ))}
        </div>
      )}
    </div>
  );
}
