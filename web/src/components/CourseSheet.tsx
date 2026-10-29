import { Sheet } from "./Sheet";
import { Icon } from "./Icon";
import { PERIOD_BY_INDEX } from "../lib/sections";
import { courseProgress, courseSlots, periodsText, weeksText, type Block } from "../lib/timetable";
import { spanText, weekdayCn } from "../lib/clock";

/**
 * Everything about one course, from one tap: where and when it meets across
 * the term, with the school's own wording kept underneath for checking.
 */
export function CourseSheet({
  block,
  week,
  weekday,
  nowMin,
  onClose,
}: {
  block: Block;
  week: number;
  weekday: number;
  nowMin: number;
  onClose: () => void;
}) {
  const { course } = block;
  const slots = courseSlots(course);
  const progress = courseProgress(course, week, weekday, nowMin);
  const inferred = slots.some((s) => s.periods.some((p) => PERIOD_BY_INDEX.get(p)?.codeInferred));

  return (
    <Sheet
      title={course.title}
      hue={course.hue}
      eyebrow={
        <>
          <span className="dot" style={{ "--h": course.hue } as React.CSSProperties} />
          {weekdayCn(block.weekday)} {block.start}–{block.end} · {spanText(block.span)}
        </>
      }
      onClose={onClose}
    >
      <div className="facts">
        <div className="fact">
          <Icon name="pin" size={18} />
          <div>
            <p className="fact__k">地点</p>
            <p className="fact__v">{block.location || "教务未标注地点"}</p>
          </div>
        </div>
        <div className="fact">
          <Icon name="person" size={18} />
          <div>
            <p className="fact__k">教师</p>
            <p className="fact__v">{block.teacher || course.teacher || "未标注"}</p>
          </div>
        </div>
      </div>

      {progress.total > 0 && (
        <div className="progress-line" aria-label={`本学期 ${progress.total} 次课，已上 ${progress.done} 次`}>
          <div className="progress-line__head">
            <span>本学期进度</span>
            <span className="num">
              {progress.done} / {progress.total} 次
            </span>
          </div>
          <div className="bar">
            <span style={{ width: `${(progress.done / progress.total) * 100}%`, "--h": course.hue } as React.CSSProperties} />
          </div>
        </div>
      )}

      <h3 className="sheet__h">每周安排</h3>
      <ul className="slots">
        {slots.map((s, i) => {
          const first = PERIOD_BY_INDEX.get(s.periods[0])!;
          const last = PERIOD_BY_INDEX.get(s.periods[s.periods.length - 1])!;
          return (
            <li key={i} className="slot">
              <div className="slot__when">
                <span className="slot__wd">{weekdayCn(s.weekday)}</span>
                <span className="num">
                  {first.start}–{last.end}
                </span>
              </div>
              <div className="slot__what">
                <p>
                  {periodsText(s.periods)} · {weeksText(s.weeks)}
                </p>
                <p className="muted">
                  {s.location || "地点未标注"}
                  {s.teacher && ` · ${s.teacher}`}
                </p>
                {s.notes.map((n) => (
                  <p key={n} className="slot__note">
                    {n}
                  </p>
                ))}
                {s.online.map((n) => (
                  <p key={n} className="slot__note">
                    线上：{n}
                  </p>
                ))}
                {s.rawTimes.length > 0 && <p className="slot__raw">教务原文 {s.rawTimes.join("；")}</p>}
              </div>
            </li>
          );
        })}
      </ul>

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>
          时刻按教务处《课堂作息时间表》换算。
          {inferred && "第 0、A–C 节的编号对应尚未用真实课表核对，"}
          实际以任课教师通知为准。
        </span>
      </p>
    </Sheet>
  );
}
