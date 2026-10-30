/**
 * 每日计划：本人自己写的待办，按日期组织。
 *
 * 和「学业」页的区别：那边是 MySTU / 教务推下来的作业，只读、会自己变；
 * 这里是本人写的东西，可改可删。
 *
 * 动效纪律（和 lib/motion.ts 一致）：只动 transform / opacity / height，
 * 勾选走 strokeDashoffset，列表进出走弹簧，展开表单用 motion 的 height:auto
 * 让它自己量高度（手写 JS 算高度会在字体加载完时差几像素）。
 * 命中 prefers-reduced-motion 时全部退化为纯透明度过渡。
 */
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import { useShell, useStore } from "../lib/store";
import { addDaysIso, dayStartMs, daysUntil, moment, monthDayCn, useNow, WEEKDAY_SHORT, weekdayCn } from "../lib/clock";
import { JELLY_SOFT, OUT, PILL, SETTLE, TAP } from "../lib/motion";
import { planApi, planErrorText, type Plan, type PlanSummary } from "../lib/plan-api";
import { Icon } from "../components/Icon";
import { Empty, ModePill, Notice, PageHead, Section, rise } from "../components/Layout";

const EMPTY_SUMMARY: PlanSummary = { today_total: 0, today_done: 0, today_open: 0, overdue: 0, upcoming: [] };

/** 优先级的三个档位；0 不画点，1 黄点，2 红点。 */
const PRIORITIES: { value: 0 | 1 | 2; label: string }[] = [
  { value: 0, label: "普通" },
  { value: 1, label: "重要" },
  { value: 2, label: "紧急" },
];

/** 复用现有 due-dot 的两个色：黄 = 重要，红 = 紧急。 */
const DOT_CLASS: Record<1 | 2, string> = { 1: "due-dot due-dot--soon", 2: "due-dot due-dot--overdue" };

function weekdayOf(isoDate: string): number {
  return moment(dayStartMs(isoDate)).weekday;
}

/** 有时间的按时间排，没时间的排最后；同一时刻按手动 sort，再按创建时间。 */
function orderOf(a: Plan, b: Plan): number {
  const at = a.start ?? "99:99";
  const bt = b.start ?? "99:99";
  if (at !== bt) return at < bt ? -1 : 1;
  if (a.sort !== b.sort) return a.sort - b.sort;
  return a.created_at - b.created_at;
}

function timeText(plan: Plan): string | null {
  if (!plan.start) return null;
  return plan.end ? `${plan.start}–${plan.end}` : plan.start;
}

export function PlanScreen() {
  const store = useStore();
  const { connect } = useShell();
  const now = useNow(60_000);
  const today = now.date;
  const reduced = useReducedMotion() === true;

  const [items, setItems] = useState<Plan[]>([]);
  const [summary, setSummary] = useState<PlanSummary>(EMPTY_SUMMARY);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [draft, setDraft] = useState({
    title: "",
    note: "",
    start: "",
    end: "",
    priority: 0 as 0 | 1 | 2,
  });

  const preview = store.mode === "preview";
  const day = selected ?? today;

  /** silent：改完一条之后的静默刷新，不要闪骨架屏。 */
  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      const res = await planApi.list();
      setItems(res.items);
      setSummary(res.summary);
    } catch (exc) {
      setError(planErrorText(exc));
    } finally {
      if (!silent) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (preview) {
      setLoading(false);
      return;
    }
    void load();
  }, [load, preview]);

  const days = useMemo(
    () => Array.from({ length: 7 }, (_, i) => addDaysIso(today, i - 3)),
    [today],
  );

  const rows = useMemo(
    () => items.filter((p) => p.date === day).slice().sort(orderOf),
    [items, day],
  );

  const toggle = async (plan: Plan) => {
    const next = !plan.done;
    // 先动界面：勾一下要立刻有反馈，别等网络
    setItems((prev) => prev.map((p) => (p.id === plan.id ? { ...p, done: next } : p)));
    try {
      const saved = await planApi.toggle(plan.id, next);
      setItems((prev) => prev.map((p) => (p.id === saved.id ? saved : p)));
      void load(true);
    } catch (exc) {
      setItems((prev) => prev.map((p) => (p.id === plan.id ? { ...p, done: !next } : p)));
      setError(planErrorText(exc));
    }
  };

  const remove = async (plan: Plan) => {
    if (!window.confirm(`删掉「${plan.title}」？删了就找不回来了。`)) return;
    const before = items;
    setItems((prev) => prev.filter((p) => p.id !== plan.id));
    try {
      await planApi.remove(plan.id);
      void load(true);
    } catch (exc) {
      setItems(before);
      setError(planErrorText(exc));
    }
  };

  const move = async (plan: Plan, delta: -1 | 1) => {
    const index = rows.findIndex((p) => p.id === plan.id);
    const target = index + delta;
    if (index < 0 || target < 0 || target >= rows.length) return;
    const ordered = rows.map((p) => p.id);
    [ordered[index], ordered[target]] = [ordered[target], ordered[index]];
    // 本地先换，再让服务端把 sort 按这个顺序落下来
    const byId = new Map(rows.map((p) => [p.id, p]));
    const moved = ordered.map((id, i) => ({ ...(byId.get(id) as Plan), sort: i }));
    setItems((prev) => [...prev.filter((p) => p.date !== day), ...moved]);
    try {
      await planApi.reorder(day, ordered);
      void load(true);
    } catch (exc) {
      setError(planErrorText(exc));
      void load(true);
    }
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setFormError(null);
    try {
      const created = await planApi.create({
        date: day,
        title: draft.title,
        note: draft.note,
        // 空串交给后端当「没填」；比传 null 稳，两边都认
        start: draft.start || "",
        end: draft.end || "",
        priority: draft.priority,
      });
      setItems((prev) => [...prev, created]);
      setDraft({ title: "", note: "", start: "", end: "", priority: 0 });
      setOpen(false);
      void load(true);
    } catch (exc) {
      setFormError(planErrorText(exc));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHead
        eyebrow="自己写 · 按天放"
        title="每日计划"
        aside={<ModePill />}
        sub={
          summary.today_total > 0
            ? `今天 ${summary.today_total} 项 · 已完成 ${summary.today_done} · 还剩 ${summary.today_open}`
            : "今天还没有计划"
        }
      />

      {/*
        日期条只依赖本地日期，没登录也能用，所以放在 preview 判断之前：
        点开「计划」第一眼就看到"今天前后七天"，而不是一块"请先登录"的墙。
      */}
      <motion.div className="plan-days" {...rise(0, { y: 6 })}>
        <div className="chips chips--scroll plan-days__scroll" role="radiogroup" aria-label="选择日期">
          {days.map((iso) => {
            const isToday = iso === today;
            const on = iso === day;
            return (
              <button
                key={iso}
                type="button"
                role="radio"
                aria-checked={on}
                className={`chip chip--btn chip--day${on ? " is-on" : ""}`}
                onClick={() => setSelected(iso)}
                aria-label={`${monthDayCn(iso)} ${weekdayCn(weekdayOf(iso))}${isToday ? "，今天" : ""}`}
              >
                {/* 选中底板是一块跟着走的药丸（共享布局动画），不重画 */}
                {on && <motion.span className="chip__pill" layoutId="plan-day" transition={PILL} />}
                <span className="chip--day__w">{isToday ? "今天" : WEEKDAY_SHORT[weekdayOf(iso) - 1]}</span>
                <span className="chip--day__d num">{Number(iso.slice(8))}</span>
              </button>
            );
          })}
        </div>
        <button
          type="button"
          className="link-btn plan-days__today"
          onClick={() => setSelected(null)}
          disabled={day === today}
        >
          今天
        </button>
      </motion.div>

      {preview ? (
        <Empty icon={<Icon name="today" size={22} />} title="登录后就能写计划">
          计划只属于你自己的账号，换设备也还在。上面的日期条可以先看看。
          <div className="mt-3">
            <button type="button" className="btn btn--primary btn--sm" onClick={connect}>
              登录 / 注册
            </button>
          </div>
        </Empty>
      ) : (
        <>
          {/* 今天概览：三个数就是"多少 / 做完 / 还剩"，用积分板而不是一句话 */}
          <motion.div className="stack-2" {...rise(1)}>
            <div className="scoreboard">
              <div className="scoreboard__cell">
                <p className="scoreboard__k">今天</p>
                <p className="scoreboard__v num">{summary.today_total}</p>
              </div>
              <div className="scoreboard__cell">
                <p className="scoreboard__k">已完成</p>
                <p className="scoreboard__v num">{summary.today_done}</p>
              </div>
              <div className="scoreboard__cell">
                <p className="scoreboard__k">还剩</p>
                <p className="scoreboard__v num">{summary.today_open}</p>
              </div>
            </div>
            {(summary.overdue > 0 || summary.upcoming.length > 0) && (
              <div className="card card--pad stack-2">
                {summary.overdue > 0 && (
                  <p className="warn-line">
                    <Icon name="alert" size={16} />
                    <span>
                      还有 <b className="num">{summary.overdue}</b> 项是之前几天没做完的，翻回去补一下。
                    </span>
                  </p>
                )}
                {summary.upcoming.length > 0 && (
                  <p className="muted">
                    接下来：
                    {summary.upcoming
                      .slice(0, 3)
                      .map((u) => `${monthDayCn(u.date)} ${u.count} 项`)
                      .join(" · ")}
                  </p>
                )}
              </div>
            )}
          </motion.div>

          {error && <Notice action={{ label: "重试", onClick: () => void load() }}>{error}</Notice>}

          <Section
            title={day === today ? "今天的安排" : `${monthDayCn(day)} ${weekdayCn(weekdayOf(day))}`}
            note={loading ? undefined : `${rows.length} 项`}
          >
            {loading ? (
              <div className="sk-list" aria-hidden="true">
                <span className="sk sk--row" />
                <span className="sk sk--row" />
                <span className="sk sk--row" />
              </div>
            ) : rows.length === 0 ? (
              <Empty icon={<Icon name="spark" size={22} />} title={day === today ? "今天还空着" : "这一天还空着"}>
                {day === today ? "把想做的事写下来，完成一件勾一件。" : "想给这天加点什么？"}
                <div className="mt-3">
                  <button type="button" className="btn btn--soft btn--sm" onClick={() => setOpen(true)}>
                    加一条
                  </button>
                </div>
              </Empty>
            ) : (
              <ul className="list">
                <AnimatePresence initial={false}>
                  {rows.map((plan, i) => (
                    <PlanRow
                      key={plan.id}
                      plan={plan}
                      index={i}
                      reduced={reduced}
                      onToggle={() => void toggle(plan)}
                      onDelete={() => void remove(plan)}
                      onMove={(delta) => void move(plan, delta)}
                      canUp={rows[0]?.id !== plan.id}
                      canDown={rows[rows.length - 1]?.id !== plan.id}
                    />
                  ))}
                </AnimatePresence>
              </ul>
            )}
          </Section>

          {/* 新增：内联表单，不是弹窗。
              只做 opacity + 位移，不做 height 动画 —— 高度动画每帧都要重新布局，
              表单里还有两个 time 输入，一展开整页都在抖。 */}
          <AnimatePresence initial={false}>
            {open && (
              <motion.div
                key="plan-form"
                initial={reduced ? { opacity: 0 } : { opacity: 0, y: 8, scale: 0.995 }}
                animate={reduced ? { opacity: 1 } : { opacity: 1, y: 0, scale: 1 }}
                exit={reduced ? { opacity: 0 } : { opacity: 0, y: -6, scale: 0.995 }}
                transition={reduced ? { duration: 0.12 } : { ...JELLY_SOFT }}
              >
                <form className="card card--pad stack-3" onSubmit={submit}>
                  <label className="field">
                    <span className="field__label">要做什么</span>
                    <input
                      className="input"
                      value={draft.title}
                      maxLength={200}
                      autoFocus
                      placeholder="例如：复习细胞生物学"
                      onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))}
                    />
                    <span className="field__hint">1–200 个字，写清楚一点，回头一眼能认出来。</span>
                  </label>

                  <label className="field">
                    <span className="field__label">备注（可不填）</span>
                    <textarea
                      className="input input--area"
                      value={draft.note}
                      maxLength={1000}
                      rows={3}
                      placeholder="第几章、要带什么、在哪做…"
                      onChange={(e) => setDraft((d) => ({ ...d, note: e.target.value }))}
                    />
                  </label>

                  <div className="row-2">
                    <label className="field">
                      <span className="field__label">开始</span>
                      <input
                        className="input"
                        type="time"
                        value={draft.start}
                        onChange={(e) => setDraft((d) => ({ ...d, start: e.target.value }))}
                      />
                    </label>
                    <label className="field">
                      <span className="field__label">结束</span>
                      <input
                        className="input"
                        type="time"
                        value={draft.end}
                        onChange={(e) => setDraft((d) => ({ ...d, end: e.target.value }))}
                      />
                    </label>
                  </div>
                  <span className="field__hint">不写时间就只是一条待办；写了就要晚于开始时间。</span>

                  <div className="field">
                    <span className="field__label">优先级</span>
                    <div className="chips" role="radiogroup" aria-label="优先级">
                      {PRIORITIES.map((p) => (
                        <button
                          key={p.value}
                          type="button"
                          role="radio"
                          aria-checked={draft.priority === p.value}
                          className={`chip chip--btn${draft.priority === p.value ? " is-on" : ""}`}
                          onClick={() => setDraft((d) => ({ ...d, priority: p.value }))}
                        >
                          {p.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  {formError && (
                    <p className="field__error" role="alert">
                      {formError}
                    </p>
                  )}

                  <div className="row-2">
                    <button type="submit" className="btn btn--primary" disabled={busy} >
                      {busy ? "保存中…" : "保存"}
                    </button>
                    <button
                      type="button"
                      className="btn btn--ghost"
                      disabled={busy}
                      onClick={() => {
                        setOpen(false);
                        setFormError(null);
                      }}
                    >
                      取消
                    </button>
                  </div>
                </form>
              </motion.div>
            )}
          </AnimatePresence>

          {!open && (
            <button type="button" className="btn btn--soft btn--block" onClick={() => setOpen(true)}>
              <span className="btn__plus" aria-hidden="true">
                ＋
              </span>
              加一条
            </button>
          )}

          <p className="footnote">
            <Icon name="info" size={14} />
            <span>
              计划是你自己写的，和 MySTU 的作业分开存，同步学校数据不会动它。日期按北京时间算。
              {day !== today && ` 现在看的是 ${monthDayCn(day)}，距今天 ${daysUntil(day, now.ms)} 天。`}
            </span>
          </p>
        </>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ row */

function PlanRow({
  plan,
  index,
  reduced,
  onToggle,
  onDelete,
  onMove,
  canUp,
  canDown,
}: {
  plan: Plan;
  index: number;
  reduced: boolean;
  onToggle: () => void;
  onDelete: () => void;
  onMove: (delta: -1 | 1) => void;
  canUp: boolean;
  canDown: boolean;
}) {
  const [armed, setArmed] = useState(false);
  const timer = useRef<number | undefined>(undefined);
  const time = timeText(plan);

  const clear = () => {
    if (timer.current !== undefined) {
      window.clearTimeout(timer.current);
      timer.current = undefined;
    }
  };
  useEffect(() => clear, []);

  // 长按 550ms 展开操作区（和点「⋯」同一条路）；真正删除还要过一次 confirm
  const press = () => {
    clear();
    timer.current = window.setTimeout(() => {
      timer.current = undefined;
      setArmed(true);
    }, 550);
  };

  return (
    <motion.li
      /*
       * layout="position" 而不是 layout：这些条目只会「换位置」，不会变尺寸。
       * 默认的 layout 会为每一项做尺寸与缩放的实时校正，一条列表几十项时每秒
       * 都要量一遍所有盒子；只要位置的话，motion 只需记住 translate。
       */
      layout={reduced ? false : "position"}
      initial={reduced ? false : { opacity: 0, y: 12, scale: 0.985 }}
      animate={{ opacity: 1, y: 0, scale: 1 }}
      exit={reduced ? { opacity: 0 } : { opacity: 0, y: -8, scale: 0.985, transition: OUT }}
      transition={reduced ? { duration: 0.12 } : { ...SETTLE, delay: Math.min(index, 8) * 0.032 }}
      className={`task${plan.done ? " is-done" : ""}`}
    >
      <motion.button
        type="button"
        className="task__check"
        role="checkbox"
        aria-checked={plan.done}
        aria-label={`${plan.done ? "取消完成" : "标记完成"}：${plan.title}`}
        onClick={onToggle}
        whileTap={reduced ? undefined : { scale: 0.9 }}
        transition={TAP}
      >
        <span className="task__box">
          {/* pathLength=1 把路径长度归一化，于是 strokeDasharray=1 正好等于整条路径，
              strokeDashoffset 从 1 收到 0 就是「一笔画出来」 */}
          <svg viewBox="0 0 24 24" width={14} height={14} fill="none" stroke="currentColor" strokeWidth={2.4} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <motion.path
              d="M5 12.5l4.5 4.5L19 7.5"
              pathLength={1}
              strokeDasharray={1}
              initial={false}
              animate={{ strokeDashoffset: plan.done ? 0 : 1 }}
              transition={reduced ? { duration: 0 } : { duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
            />
          </svg>
        </span>
      </motion.button>

      <div className="task__main" onPointerDown={press} onPointerUp={clear} onPointerLeave={clear} onPointerCancel={clear}>
        <p className="task__title">{plan.title}</p>
        {plan.note && (
          <p className="task__meta">
            <span>{plan.note}</span>
          </p>
        )}
      </div>

      <div className="task__side">
        {time && <span className="task__when">{time}</span>}
        {plan.priority > 0 && (
          <span
            className={DOT_CLASS[plan.priority as 1 | 2]}
            role="img"
            aria-label={plan.priority === 2 ? "紧急" : "重要"}
          />
        )}
        {armed ? (
          <div className="row-actions">
            <button
              type="button"
              className="icon-btn"
              aria-label="上移"
              disabled={!canUp}
              onClick={() => onMove(-1)}
            >
              <Icon name="chevron-left" size={16} className="rot-up" />
            </button>
            <button
              type="button"
              className="icon-btn"
              aria-label="下移"
              disabled={!canDown}
              onClick={() => onMove(1)}
            >
              <Icon name="chevron-right" size={16} className="rot-down" />
            </button>
            <button type="button" className="icon-btn btn--danger-text" aria-label="删除" onClick={onDelete}>
              <Icon name="trash" size={18} />
            </button>
            <button type="button" className="icon-btn" aria-label="收起" onClick={() => setArmed(false)}>
              <Icon name="close" size={16} />
            </button>
          </div>
        ) : (
          <button type="button" className="icon-btn" aria-label={`更多操作：${plan.title}`} onClick={() => setArmed(true)}>
            <span className="dots" aria-hidden="true">
              ⋯
            </span>
          </button>
        )}
      </div>
    </motion.li>
  );
}
