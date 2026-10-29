import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useStore } from "../lib/store";
import { daysUntil, monthDayCn, monthDaySlash, moment, spanText, useNow, weekdayCn } from "../lib/clock";
import { dueBucket, EXAM_SESSIONS, SOURCE_NAME, summarize, type DueBucket, type Task } from "../lib/records";
import { Segmented } from "../components/Segmented";
import { Icon } from "../components/Icon";
import { Empty, ModePill, PageHead, Section } from "../components/Layout";

type View = "tasks" | "exams" | "grades";

const BUCKETS: { key: DueBucket; label: string }[] = [
  { key: "overdue", label: "已过截止" },
  { key: "today", label: "今天截止" },
  { key: "soon", label: "4 天内" },
  { key: "later", label: "更晚" },
  { key: "none", label: "没写截止时间" },
];

export function StudyScreen() {
  const store = useStore();
  const now = useNow(60_000);
  const [view, setView] = useState<View>("tasks");

  const open = store.tasks.filter((t) => !store.done.has(t.id) && dueBucket(t, now.ms) !== "expired");
  const urgent = open.filter((t) => {
    const b = dueBucket(t, now.ms);
    return b === "today" || b === "overdue";
  }).length;
  const nextExam = store.exams
    .filter((e) => e.startMs !== null && e.startMs > now.ms)
    .sort((a, b) => a.startMs! - b.startMs!)[0];

  return (
    <>
      <PageHead
        eyebrow="作业 · 考试 · 成绩"
        title="学业"
        aside={<ModePill />}
        sub={
          <>
            {open.length} 项作业待完成
            {nextExam?.date && ` · 最近考试 ${monthDayCn(nextExam.date)}`}
          </>
        }
      />
      <Segmented
        label="学业分类"
        value={view}
        onChange={setView}
        options={[
          { key: "tasks", label: "作业", badge: urgent || undefined },
          { key: "exams", label: "考试" },
          { key: "grades", label: "成绩" },
        ]}
      />
      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={view}
          initial={{ opacity: 0, y: 6 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -4 }}
          transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
        >
          {view === "tasks" && <Tasks nowMs={now.ms} />}
          {view === "exams" && <Exams nowMs={now.ms} />}
          {view === "grades" && <Grades />}
        </motion.div>
      </AnimatePresence>
    </>
  );
}

/* ------------------------------------------------------------------ tasks */

function dueLine(t: Task, nowMs: number): string {
  if (t.dueMs === null) return t.dueRaw ? `截止 ${t.dueRaw}` : "未写截止";
  const m = moment(t.dueMs);
  const clock = `${String(Math.floor(m.minutes / 60)).padStart(2, "0")}:${String(m.minutes % 60).padStart(2, "0")}`;
  const d = daysUntil(m.date, nowMs);
  const day =
    d === 0 ? "今天" : d === 1 ? "明天" : d === -1 ? "昨天" : Math.abs(d) < 7 ? weekdayCn(m.weekday) : monthDaySlash(m.date);
  return `${day} ${clock}`;
}

function leftText(t: Task, nowMs: number): string | null {
  if (t.dueMs === null) return null;
  const mins = Math.round((t.dueMs - nowMs) / 60000);
  if (mins < 0) return `已过 ${spanText(-mins).replace(/ \d+ 分$/, "")}`;
  if (mins < 24 * 60) return `剩 ${spanText(mins)}`;
  return `剩 ${Math.floor(mins / 1440)} 天`;
}

function Tasks({ nowMs }: { nowMs: number }) {
  const store = useStore();
  const [showDone, setShowDone] = useState(false);

  const groups = useMemo(() => {
    const open = store.tasks.filter((t) => !store.done.has(t.id));
    return BUCKETS.map((b) => ({
      ...b,
      items: open
        .filter((t) => dueBucket(t, nowMs) === b.key)
        .sort((x, y) => (x.dueMs ?? Infinity) - (y.dueMs ?? Infinity)),
    })).filter((g) => g.items.length > 0);
  }, [store.tasks, store.done, nowMs]);
  const finished = store.tasks.filter((t) => store.done.has(t.id) && dueBucket(t, nowMs) !== "expired");
  const expired = store.tasks.filter((t) => !store.done.has(t.id) && dueBucket(t, nowMs) === "expired").length;

  if (store.tasks.length === 0 || (groups.length === 0 && finished.length === 0)) {
    return (
      <Empty icon={<Icon name="study" size={22} />} title={store.syncing ? "正在读取 MySTU 作业…" : "还没有作业记录"}>
        同步 MySTU 后会出现在这里。没有记录不代表学校没有布置，雨课堂作业暂时不在其中。
      </Empty>
    );
  }

  return (
    <div className="stack-6">
      {groups.map((g) => (
        <Section key={g.key} title={g.label} note={`${g.items.length} 项`}>
          <ul className="list">
            <AnimatePresence initial={false}>
              {g.items.map((t) => (
                <TaskRow key={t.id} task={t} nowMs={nowMs} bucket={g.key} />
              ))}
            </AnimatePresence>
          </ul>
        </Section>
      ))}

      {finished.length > 0 && (
        <section className="sec">
          <button type="button" className="disclosure" aria-expanded={showDone} onClick={() => setShowDone((v) => !v)}>
            <span>已完成 {finished.length} 项</span>
            <Icon name="chevron-down" size={18} className={showDone ? "rot" : ""} />
          </button>
          {showDone && (
            <ul className="list">
              {finished.map((t) => (
                <TaskRow key={t.id} task={t} nowMs={nowMs} bucket={dueBucket(t, nowMs)} done />
              ))}
            </ul>
          )}
        </section>
      )}

      {expired > 0 && <p className="muted">另有 {expired} 项两周前就已截止的作业没有列出。</p>}

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>勾选只记在这台设备上，不会向 MySTU 提交任何内容。</span>
      </p>
    </div>
  );
}

function TaskRow({ task, nowMs, bucket, done = false }: { task: Task; nowMs: number; bucket: DueBucket; done?: boolean }) {
  const { toggleDone } = useStore();
  const left = leftText(task, nowMs);
  return (
    <motion.li
      layout
      initial={{ opacity: 0, height: 0 }}
      animate={{ opacity: 1, height: "auto" }}
      exit={{ opacity: 0, height: 0 }}
      transition={{ duration: 0.24, ease: [0.22, 1, 0.36, 1] }}
      className={`task${done ? " is-done" : ""}`}
    >
      <button
        type="button"
        className="task__check"
        role="checkbox"
        aria-checked={done}
        aria-label={`${done ? "取消完成" : "标记完成"}：${task.title}`}
        onClick={() => toggleDone(task.id)}
      >
        <span className="task__box">
          <Icon name="check" size={14} strokeWidth={2.4} />
        </span>
      </button>
      <div className="task__main">
        <p className="task__title">{task.title}</p>
        <p className="task__meta">
          {task.course && <span>{task.course}</span>}
          <span className="src">{SOURCE_NAME[task.source] ?? task.source}</span>
          {task.schoolStatus && <span>学校状态：{task.schoolStatus}</span>}
        </p>
      </div>
      <div className={`task__due due--${bucket}`}>
        <span className="task__when">{dueLine(task, nowMs)}</span>
        {left && !done && <span className="task__left">{left}</span>}
      </div>
      {task.url && (
        <a className="task__open" href={task.url} target="_blank" rel="noreferrer noopener" aria-label={`在 ${SOURCE_NAME[task.source] ?? "原站"} 打开`}>
          <Icon name="external" size={16} />
        </a>
      )}
    </motion.li>
  );
}

/* ------------------------------------------------------------------ exams */

function Exams({ nowMs }: { nowMs: number }) {
  const { exams } = useStore();
  const sorted = [...exams].sort((a, b) => (a.startMs ?? Infinity) - (b.startMs ?? Infinity));
  const upcoming = sorted.filter((e) => e.startMs === null || e.startMs > nowMs - 3 * 3600_000);
  const past = sorted.filter((e) => e.startMs !== null && e.startMs <= nowMs - 3 * 3600_000);
  const first = upcoming.find((e) => e.date);

  if (exams.length === 0) {
    return (
      <Empty icon={<Icon name="calendar" size={22} />} title="还没有考试安排">
        教务公布后，同步一次就会出现在这里。
      </Empty>
    );
  }

  return (
    <div className="stack-6">
      {first?.date && (
        <div className="countdown">
          <div>
            <p className="countdown__k">距离第一场考试</p>
            <p className="countdown__title">{first.title}</p>
            <p className="countdown__meta">{first.timeRaw}</p>
          </div>
          <p className="countdown__num">
            <span className="num">{Math.max(0, daysUntil(first.date, nowMs))}</span>
            <small>天</small>
          </p>
        </div>
      )}

      <Section title="考试安排" note={`${upcoming.length} 场`}>
        <ul className="list">
          {upcoming.map((e) => (
            <li key={e.id} className="exam">
              <div className={`exam__date${e.date ? "" : " is-unknown"}`}>
                {e.date ? (
                  <>
                    <span className="exam__m">{Number(e.date.slice(5, 7))}月</span>
                    <span className="exam__d num">{Number(e.date.slice(8))}</span>
                  </>
                ) : (
                  <span className="exam__m">待定</span>
                )}
              </div>
              <div className="exam__main">
                <p className="exam__title">{e.title}</p>
                <p className="exam__meta">{e.timeRaw || "时间未公布"}</p>
                <p className="exam__meta">
                  {e.location ? (
                    <>
                      <Icon name="pin" size={13} /> {e.location}
                    </>
                  ) : (
                    "考场未公布"
                  )}
                  {e.seat && <span className="exam__seat">座位 {e.seat}</span>}
                </p>
              </div>
              {e.date && (
                <span className="exam__left num">
                  {daysUntil(e.date, nowMs) === 0 ? "今天" : `${daysUntil(e.date, nowMs)} 天`}
                </span>
              )}
            </li>
          ))}
        </ul>
      </Section>

      {past.length > 0 && <p className="muted">已结束 {past.length} 场</p>}

      <details className="details">
        <summary>学校统一考试场次</summary>
        <ul className="sessions">
          {EXAM_SESSIONS.map((s, i) => (
            <li key={s}>
              <span>第{["一", "二", "三", "四", "五"][i]}场</span>
              <span className="num">{s}</span>
            </li>
          ))}
        </ul>
        <p className="muted">来源：2026–2027 学年校历所附考试时间表。具体科目以教务通知为准。</p>
      </details>

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>考试时间按教务原文显示，倒计时只按日期估算。</span>
      </p>
    </div>
  );
}

/* ----------------------------------------------------------------- grades */

function Grades() {
  const { grades } = useStore();
  const semesters = [...new Set(grades.map((g) => g.semester).filter(Boolean))].sort().reverse();
  const [sem, setSem] = useState<string>("all");
  const rows = sem === "all" ? grades : grades.filter((g) => g.semester === sem);
  const sum = summarize(rows);

  if (grades.length === 0) {
    return (
      <Empty icon={<Icon name="study" size={22} />} title="还没有成绩记录">
        同步教务后会出现在这里。
      </Empty>
    );
  }

  return (
    <div className="stack-6">
      <div className="chips chips--scroll" role="radiogroup" aria-label="学期">
        {["all", ...semesters].map((s) => (
          <button
            key={s}
            type="button"
            role="radio"
            aria-checked={sem === s}
            className={`chip chip--btn${sem === s ? " is-on" : ""}`}
            onClick={() => setSem(s)}
          >
            {s === "all" ? "全部学期" : semesterLabel(s)}
          </button>
        ))}
      </div>

      <div className="scoreboard">
        <div className="scoreboard__cell">
          <p className="scoreboard__k">加权均分</p>
          <p className="scoreboard__v num">{sum.weightedScore !== null ? sum.weightedScore.toFixed(2) : "—"}</p>
        </div>
        <div className="scoreboard__cell">
          <p className="scoreboard__k">加权绩点</p>
          <p className="scoreboard__v num">{sum.weightedGpa !== null ? sum.weightedGpa.toFixed(2) : "—"}</p>
        </div>
        <div className="scoreboard__cell">
          <p className="scoreboard__k">总学分</p>
          <p className="scoreboard__v num">{creditText(sum.credits)}</p>
        </div>
      </div>
      <p className="footnote footnote--tight">
        <Icon name="info" size={14} />
        <span>
          按学分加权，等级制成绩不计入均分，补考和重修逐条计入。均分和绩点按 {creditText(sum.countedCredits)}{" "}
          学分加权，共 {sum.total} 条、{sum.counted} 条计入。这是估算，不是学校认定的绩点。
        </span>
      </p>

      <ul className="list">
        {rows.map((g) => {
          const numeric = Number.isFinite(Number(g.score)) && g.score !== "";
          const tone = numeric ? (Number(g.score) >= 85 ? "hi" : Number(g.score) < 60 ? "lo" : "mid") : "txt";
          return (
            <li key={g.id} className="grade">
              <div className="grade__main">
                <p className="grade__title">{g.title}</p>
                <p className="grade__meta">
                  {semesterLabel(g.semester)}
                  {g.credits !== null && ` · ${g.credits} 学分`}
                  {g.gpa !== null && ` · 绩点 ${g.gpa}`}
                  {g.examType && g.examType !== "正常考试" && <span className="tag tag--warn">{g.examType}</span>}
                </p>
              </div>
              <span className={`grade__score grade__score--${tone}${numeric ? " num" : ""}`}>{g.score || "—"}</span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function semesterLabel(s: string): string {
  const m = s.match(/^(\d{4})-(\d{4})-(\d)$/);
  if (!m) return s;
  return `${m[1].slice(2)}–${m[2].slice(2)} ${["", "秋", "春", "夏"][Number(m[3])] ?? m[3]}`;
}

/** 23 -> "23", 23.5 -> "23.5": the board never shows a trailing ".0" */
function creditText(n: number): string {
  return Number.isInteger(n) ? String(n) : n.toFixed(1);
}
