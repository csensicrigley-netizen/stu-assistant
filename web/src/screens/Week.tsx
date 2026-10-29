import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useStore } from "../lib/store";
import { addDaysIso, monthDaySlash, spanText, useNow, WEEKDAY_SHORT, weekdayCn } from "../lib/clock";
import { currentOrNextTerm, dateOf, mondayOf, phaseOf, termPosition } from "../lib/calendar";
import { planFor } from "../lib/plan";
import { gapsBetween, minutesOf, periodsText, type Block } from "../lib/timetable";
import { DayRibbon } from "../components/DayRibbon";
import { CourseSheet } from "../components/CourseSheet";
import { Icon } from "../components/Icon";
import { Empty, ModePill, PageHead, Section } from "../components/Layout";

export function WeekScreen() {
  const store = useStore();
  const now = useNow(30_000);
  const pos = termPosition(now.date);
  const term = pos?.term ?? currentOrNextTerm(now.date);
  const thisWeek = pos?.week ?? 1;

  const [week, setWeek] = useState(thisWeek);
  const [day, setDay] = useState(pos ? now.weekday : 1);
  const [dir, setDir] = useState(0);
  const [open, setOpen] = useState<Block | null>(null);

  const plans = useMemo(
    () => Array.from({ length: 7 }, (_, i) => planFor(store.timetable, dateOf(term, week, i + 1))),
    [store.timetable, term, week],
  );
  const maxMin = Math.max(1, ...plans.map((p) => minutesOf(p.blocks)));
  const weekTotal = plans.reduce((s, p) => s + minutesOf(p.blocks), 0);
  const phase = phaseOf(term, week);
  const sel = plans[day - 1];
  const gaps = gapsBetween(sel.blocks);
  const isThisWeek = pos && week === pos.week;
  const doc = store.timetable;

  const step = (d: number) => {
    const next = Math.min(term.totalWeeks, Math.max(1, week + d));
    if (next === week) return;
    setDir(d);
    setWeek(next);
  };

  return (
    <>
      <PageHead eyebrow={term.label} title="课表" aside={<ModePill />} />

      {!store.sample && !doc && (
        <Empty icon={<Icon name="week" size={22} />} title={store.syncing || store.loading ? "正在读取课表…" : "还没有课表"}>
          {store.syncing || store.loading
            ? "第一次同步通常要半分钟左右。"
            : "连接校园账号并同步后，这里会显示你每周的理论课。"}
        </Empty>
      )}

      <div className="split split--week">
      <div className="split__main">
      <div className="weekbar">
        <button type="button" className="icon-btn icon-btn--soft" onClick={() => step(-1)} disabled={week <= 1} aria-label="上一周">
          <Icon name="chevron-left" />
        </button>
        <div className="weekbar__mid" aria-live="polite">
          <p className="weekbar__title">
            第 <span className="num">{week}</span> 周{phase && <span className="weekbar__phase">{phase.label}</span>}
          </p>
          <p className="weekbar__range num">
            {monthDaySlash(mondayOf(term, week))} – {monthDaySlash(addDaysIso(mondayOf(term, week), 6))}
            {weekTotal > 0 && <span> · {spanText(weekTotal)}</span>}
          </p>
        </div>
        <button
          type="button"
          className="icon-btn icon-btn--soft"
          onClick={() => step(1)}
          disabled={week >= term.totalWeeks}
          aria-label="下一周"
        >
          <Icon name="chevron-right" />
        </button>
      </div>
      {pos && !isThisWeek && (
        <button
          type="button"
          className="back-now"
          onClick={() => {
            setDir(pos.week > week ? 1 : -1);
            setWeek(pos.week);
            setDay(now.weekday);
          }}
        >
          回到本周
        </button>
      )}

      <AnimatePresence mode="popLayout" initial={false} custom={dir}>
        <motion.div
          key={week}
          className="card spectrum spectrum--idle"
          custom={dir}
          initial={{ opacity: 0, x: dir * 24 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: dir * -24 }}
          transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
          role="radiogroup"
          aria-label={`第 ${week} 周每天的课`}
        >
          <div className="spectrum__scale" aria-hidden="true">
            <span />
            <DayRibbon blocks={[]} labelled />
            <span />
          </div>
          {plans.map((p, i) => {
            const wd = i + 1;
            const mins = minutesOf(p.blocks);
            const on = wd === day;
            const today = isThisWeek && wd === now.weekday;
            const closed = p.status.kind === "closed";
            return (
              <button
                key={wd}
                type="button"
                role="radio"
                aria-checked={on}
                className={`spectrum__row${on ? " is-on" : ""}${today ? " is-today" : ""}`}
                onClick={() => setDay(wd)}
                aria-label={`${weekdayCn(wd)} ${monthDaySlash(p.date)}，${closed ? "停课" : mins ? `${p.blocks.length} 节，${spanText(mins)}` : "没有课"}`}
              >
                <span className="spectrum__day">
                  <b>{WEEKDAY_SHORT[i]}</b>
                  <span className="num">{Number(p.date.slice(8))}</span>
                </span>
                {closed ? (
                  <span className="spectrum__closed">{p.status.kind === "closed" && p.status.label}</span>
                ) : (
                  <DayRibbon blocks={p.blocks} compact nowMin={today ? now.minutes : null} />
                )}
                <span className="spectrum__load" aria-hidden="true">
                  <span style={{ height: `${(mins / maxMin) * 100}%` }} />
                </span>
              </button>
            );
          })}
        </motion.div>
      </AnimatePresence>
      </div>

      <div className="split__side">
      <Section
        title={
          <>
            {weekdayCn(day)} <span className="num sec__title-num">{monthDaySlash(sel.date)}</span>
          </>
        }
        note={sel.blocks.length ? `${sel.blocks.length} 节 · ${spanText(minutesOf(sel.blocks))}` : undefined}
      >
        {sel.status.kind === "closed" ? (
          <Empty icon={<Icon name="calendar" size={22} />} title={`${sel.status.label} · 停课`}>
            按教务处校历，这一天不上课。
          </Empty>
        ) : sel.blocks.length === 0 ? (
          <Empty icon={<Icon name="spark" size={22} />} title="这天没有理论课">
            实验课和临时调课以学院通知为准。
          </Empty>
        ) : (
          <ol className="daylist">
            {[...sel.blocks.map((b) => ({ at: b.startMin, b })), ...gaps.map((g) => ({ at: g.startMin, g }))]
              .sort((a, b) => a.at - b.at)
              .map((row) =>
                "b" in row && row.b ? (
                  <li key={row.b.key}>
                    <button
                      type="button"
                      className="lesson"
                      style={{ "--h": row.b.course.hue } as React.CSSProperties}
                      onClick={() => setOpen(row.b)}
                    >
                      <span className="lesson__time num">
                        <b>{row.b.start}</b>
                        <span>{row.b.end}</span>
                      </span>
                      <span className="lesson__body">
                        <span className="lesson__title">{row.b.course.title}</span>
                        <span className="lesson__meta">
                          <Icon name="pin" size={14} />
                          {row.b.location || "地点未标注"}
                        </span>
                        <span className="lesson__meta lesson__meta--dim">
                          {row.b.teacher && `${row.b.teacher} · `}
                          {periodsText(row.b.periods)}
                        </span>
                      </span>
                      <Icon name="chevron-right" size={18} className="lesson__chev" />
                    </button>
                  </li>
                ) : "g" in row && row.g ? (
                  <li key={`g${row.g.start}`} className="daylist__gap">
                    <span className="num">{row.g.start}</span>
                    {row.g.label} {spanText(row.g.span)}
                  </li>
                ) : null,
              )}
          </ol>
        )}
      </Section>

      {doc && (doc.unarranged.length > 0 || doc.pending.length > 0) && (
        <Section title="需要留意">
          <div className="card card--pad stack-2">
            {doc.pending.map((p) => (
              <p key={p} className="warn-line">
                <Icon name="alert" size={16} />
                {p}
              </p>
            ))}
            {doc.unarranged.length > 0 && (
              <>
                <p className="muted">教务列出但没有排时间的课程：</p>
                <ul className="chips">
                  {doc.unarranged.map((u) => (
                    <li key={u.id} className="chip">
                      {u.title}
                    </li>
                  ))}
                </ul>
              </>
            )}
          </div>
        </Section>
      )}

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>只含教务系统里的理论课。节次时刻按教务处作息表：第二、四、五大节各 155 分钟，晚课 19:20 开始。</span>
      </p>
      </div>
      </div>

      <AnimatePresence>
        {open && (
          <CourseSheet
            key={open.key}
            block={open}
            week={pos?.week ?? 0}
            weekday={now.weekday}
            nowMin={now.minutes}
            onClose={() => setOpen(null)}
          />
        )}
      </AnimatePresence>
    </>
  );
}
