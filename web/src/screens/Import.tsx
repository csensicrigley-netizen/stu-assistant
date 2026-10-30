/**
 * 课表导入（手动录入）。
 *
 * 谁需要它：医学院。那边教务系统和主校区不是一套，自动同步拿不到课表，
 * 只能学生自己把课录进来。录完之后**必须和自动同步的课表一模一样地显示**，
 * 所以这一页不自己算时间、不自己配色：
 *   - 文本解析交给服务端（timetable_import.py），一次解析三种格式都走同一条路；
 *   - 「一条一条加」也先拼成同样的文本再交给服务端，避免两条解析路径对不上；
 *   - 预览里的课用 normalizeTimetable() 过一遍，拿到的是 Week / Today
 *     正在用的同一份 Course / Meeting，色块和导入后完全一致。
 *
 * 四步：选学校 → 选录入方式 → 预览与修正 → 完成。
 */
import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion, useReducedMotion } from "motion/react";
import {
  EMPTY_PREVIEW,
  MED_NOTE,
  SCHOOLS,
  SCHOOL_NAME,
  docFromPreview,
  importApi,
  weeksRangeText,
  type ImportFormat,
  type ImportPreview,
  type SchoolId,
} from "../lib/timetable-import";
import { currentOrNextTerm, termPosition } from "../lib/calendar";
import { PERIODS, WEEKDAY_LABELS } from "../lib/sections";
import { useShell, useStore } from "../lib/store";
import { Icon } from "../components/Icon";
import { OUT, PILL, STEP_IN, rise } from "../lib/motion";
import { PageHead } from "../components/Layout";

/* ------------------------------------------------------------------ 类型 */

type Step = "school" | "method" | "preview" | "done";
type Method = "paste" | "csv" | "manual";

const STEP_TITLE: Record<Step, string> = {
  school: "先确认你在哪个校区",
  method: "怎么把课录进来",
  preview: "对一下，别录错了",
  done: "录好了",
};

/** 步骤条上的短标签 + 顺序。四个字以内，手机上四格放得下。 */
const STEP_LABEL: Record<Step, string> = {
  school: "选校区",
  method: "录入",
  preview: "核对",
  done: "完成",
};

const STEP_ORDER: Step[] = ["school", "method", "preview", "done"];

const METHODS: { key: Method; label: string; hint: string }[] = [
  { key: "paste", label: "粘贴文字", hint: "一行一课，最省事" },
  { key: "csv", label: "CSV 表格", hint: "从 Excel 导出来" },
  { key: "manual", label: "一条一条加", hint: "课少的时候用" },
];

/** 手工表单里攒下来的一门课，最后拼成一行粘贴文本。 */
interface Draft {
  key: string;
  title: string;
  weekdays: number[];
  periods: number[];
  from: number;
  to: number;
  teacher: string;
  location: string;
}

/* ------------------------------------------------------------- 小工具 */

/** [1,2,3,6] → "1-3,6" */
function runs(values: number[]): string {
  const sorted = [...new Set(values)].sort((a, b) => a - b);
  const out: [number, number][] = [];
  for (const v of sorted) {
    const last = out[out.length - 1];
    if (last && v === last[1] + 1) last[1] = v;
    else out.push([v, v]);
  }
  return out.map(([a, b]) => (a === b ? `${a}` : `${a}-${b}`)).join(",");
}

/** 一门草稿 → 一行「课程名 | 周一 1-2节 | 1-16周 | 教师 | 地点」。 */
function draftLine(d: Draft): string {
  const when = d.weekdays
    .map((wd) => `${WEEKDAY_LABELS[wd - 1]} ${runs(d.periods)}节`)
    .join(",");
  const weeks = d.from === d.to ? `${d.from}周` : `${d.from}-${d.to}周`;
  return [d.title, when, weeks, d.teacher, d.location].join(" | ");
}

/** 13 节印出来的样子：1…9、0、A、B、C。 */
const PERIOD_CODE = new Map(PERIODS.map((p) => [p.index, p.code]));

/**
 * 说明里的示例代码块。样式在 app.css 的 `.pre` 里（以前是内联对象，
 * 三处 copy 一遍；现在一个类，主题切换也跟着走）。
 */

/* --------------------------------------------------------------- 组件 */

export function ImportScreen() {
  const shell = useShell();
  const store = useStore();
  const reduce = useReducedMotion() === true;

  const semester = useMemo(() => {
    const pos = termPosition();
    return (pos?.term ?? currentOrNextTerm()).id;
  }, []);

  const [step, setStep] = useState<Step>("school");
  const [dir, setDir] = useState(1);
  const [school, setSchool] = useState<SchoolId | null>(null);
  const [method, setMethod] = useState<Method>("paste");
  const [text, setText] = useState("");
  const [note, setNote] = useState("");
  const [preview, setPreview] = useState<ImportPreview>(EMPTY_PREVIEW);
  const [busy, setBusy] = useState<"parse" | "save" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [existing, setExisting] = useState<{ school: string } | null>(null);
  const [savedAt, setSavedAt] = useState<string | null>(null);

  // 这个学期已经录过？先说清楚「再导入会覆盖」，别让人白干一遍。
  useEffect(() => {
    let alive = true;
    void importApi
      .load(semester)
      .then((res) => {
        if (alive && res.doc) setExisting({ school: res.school });
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [semester]);

  const go = (next: Step, forward = true) => {
    setDir(forward ? 1 : -1);
    setProblem(null);
    setStep(next);
  };

  const parse = async (fmt: ImportFormat, body: string) => {
    setBusy("parse");
    setProblem(null);
    try {
      const res = await importApi.parse(body, fmt, semester);
      setPreview(res);
      go("preview");
    } catch {
      setProblem("服务器没有回应，检查一下网络再试一次。");
    } finally {
      setBusy(null);
    }
  };

  const save = async () => {
    if (!school) return;
    setBusy("save");
    setProblem(null);
    try {
      const res = await importApi.save(docFromPreview(preview, semester), { school, note });
      if (!res.ok) {
        setPreview((prev) => ({ ...prev, errors: res.errors ?? ["导入没有成功，再试一次"] }));
        setProblem("这份课表没通过服务端校验，看看下面红字。");
        return;
      }
      setSavedAt(res.semester);
      go("done");
      // 课表已经镜像进 items 了，把页面上的数据读一遍，点「去看课表」就是新的
      void store.refresh("read").catch(() => undefined);
    } catch {
      setProblem("导入没有送出去，检查一下网络再试一次。");
    } finally {
      setBusy(null);
    }
  };

  const canSave = preview.courses.length > 0 && !busy;

  return (
    <>
      <PageHead
        eyebrow={`手动录入 · ${semester}`}
        title="导入课表"
        sub="医学院的教务系统和主校区不是一套，没法自动同步，只能自己录。录完和自动同步的课表一样显示。"
        aside={
          // 手机上没地方放「返回」，挂在标题右侧；Android 物理返回键走同一路径。
          <button type="button" className="btn btn--ghost btn--sm" onClick={() => shell.go("week")}>
            返回课表
          </button>
        }
      />

      {/*
        步骤条：底轨 + 一格跟着走的药丸（layoutId 共享布局）。
        之前是四条各自换 background 的小竖条，看不出"第几步"也看不出方向。
        aria-hidden：真正的"第几步"由下面那块 section 的 aria-label 念出来，
        屏幕阅读器不需要再听一遍装饰性的进度条。
      */}
      <ol className="steps" aria-hidden="true">
        {STEP_ORDER.map((s, i) => {
          const index = STEP_ORDER.indexOf(step);
          const on = i === index;
          const past = i < index;
          return (
            <li key={s} className={`steps__item${on ? " is-on" : ""}${past ? " is-done" : ""}`}>
              {on && <motion.span className="steps__pill" layoutId="import-step" transition={PILL} />}
              <span className="steps__num">{past ? <Icon name="check" size={10} strokeWidth={3} /> : i + 1}</span>
              <span>{STEP_LABEL[s]}</span>
            </li>
          );
        })}
      </ol>

      <AnimatePresence mode="wait" initial={false} custom={dir}>
        <motion.section
          key={step}
          custom={dir}
          initial={{ opacity: 0, x: reduce ? 0 : dir * 44 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: reduce ? 0 : dir * -32, transition: OUT }}
          transition={reduce ? { duration: 0.12 } : STEP_IN}
          className="stack-4"
          aria-label={STEP_TITLE[step]}
        >
          {step === "school" && (
            <StepSchool
              value={school}
              existing={existing}
              onPick={(id) => {
                setSchool(id);
                go("method");
              }}
            />
          )}

          {step === "method" && (
            <StepMethod
              method={method}
              onMethod={setMethod}
              text={text}
              onText={setText}
              busy={busy === "parse"}
              problem={problem}
              onBack={() => go("school", false)}
              onParse={(fmt) => void parse(fmt, text)}
              onFile={async (file) => {
                const body = await file.text();
                setText(body);
                setMethod("csv");
                await parse("csv", body);
              }}
              onTemplate={async () => {
                try {
                  const csv = await importApi.template();
                  const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = `课表模板-${semester}.csv`;
                  a.click();
                  URL.revokeObjectURL(url);
                } catch {
                  setProblem("模板没下载下来，检查一下网络再试一次。");
                }
              }}
              onManual={(drafts) => {
                if (drafts.length === 0) {
                  setProblem("还没加任何一门课，先把课程名、星期和节次填上。");
                  return;
                }
                void parse("paste", drafts.map(draftLine).join("\n"));
              }}
            />
          )}

          {step === "preview" && (
            <StepPreview
              preview={preview}
              problem={problem}
              busy={busy === "save"}
              canSave={canSave}
              note={note}
              onNote={setNote}
              onBack={() => go("method", false)}
              onSave={() => void save()}
            />
          )}

          {step === "done" && (
            <StepDone
              semester={savedAt ?? semester}
              school={school}
              count={preview.stats.courses}
              onWeek={() => shell.go("week")}
            />
          )}
        </motion.section>
      </AnimatePresence>

      <p className="footnote">
        <Icon name="info" size={14} />
        <span>
          节次时刻按教务处作息表算：13 节、5 个大节，第二、四、五大节各 155 分钟，晚课 19:20 开始。
          录进去的课只存在你自己的账号里。
        </span>
      </p>
    </>
  );
}

/* ---------------------------------------------------------- 第 1 步 */

function StepSchool({
  value,
  existing,
  onPick,
}: {
  value: SchoolId | null;
  existing: { school: string } | null;
  onPick: (id: SchoolId) => void;
}) {
  return (
    <>
      <div className="stack-2">
        {SCHOOLS.map((s, i) => (
          <motion.button
            key={s.id}
            type="button"
            className={`choice${value === s.id ? " is-on" : ""}`}
            {...rise(i, { y: 10, scale: 0.99 })}
            onClick={() => onPick(s.id)}
          >
            <span className="choice__head">
              <span className="choice__title">{s.name}</span>
              {existing?.school === s.id && <span className="tag tag--quiet">这个学期已录过</span>}
              <Icon name="chevron-right" size={16} className="choice__go" />
            </span>
            <span className="choice__note">{s.note}</span>
            {s.id === "med" && (
              <span className="warn-line">
                <Icon name="alert" size={16} />
                <span>{MED_NOTE}</span>
              </span>
            )}
          </motion.button>
        ))}
      </div>
      {existing && (
        <p className="muted">
          这个学期已经有一份手动课表（{SCHOOL_NAME[existing.school] ?? existing.school}）。
          再导入会整份覆盖，不会留两份。
        </p>
      )}
    </>
  );
}

/* ---------------------------------------------------------- 第 2 步 */

function StepMethod({
  method,
  onMethod,
  text,
  onText,
  busy,
  problem,
  onBack,
  onParse,
  onFile,
  onTemplate,
  onManual,
}: {
  method: Method;
  onMethod: (m: Method) => void;
  text: string;
  onText: (t: string) => void;
  busy: boolean;
  problem: string | null;
  onBack: () => void;
  onParse: (fmt: ImportFormat) => void;
  onFile: (file: File) => Promise<void>;
  onTemplate: () => Promise<void>;
  onManual: (drafts: Draft[]) => void;
}) {
  const file = useRef<HTMLInputElement>(null);
  const reduce = useReducedMotion() === true;

  return (
    <>
      <button type="button" className="back" onClick={onBack}>
        <Icon name="chevron-left" size={18} />
        换校区
      </button>

      <div className="seg" role="radiogroup" aria-label="录入方式">
        {METHODS.map((m) => {
          const on = m.key === method;
          return (
            <button
              key={m.key}
              type="button"
              role="radio"
              aria-checked={on}
              className={`seg__btn${on ? " is-on" : ""}`}
              onClick={() => onMethod(m.key)}
            >
              {on && <motion.span className="seg__pill" layoutId="import-method" transition={PILL} />}
              <span className="seg__label">{m.label}</span>
            </button>
          );
        })}
      </div>

      <AnimatePresence mode="wait" initial={false}>
        <motion.div
          key={method}
          initial={{ opacity: 0, y: reduce ? 0 : 8 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: reduce ? 0 : -6, transition: OUT }}
          transition={reduce ? { duration: 0.12 } : STEP_IN}
          className="stack-4"
        >
          {method === "paste" && (
            <>
              <label className="field">
                <span className="field__label">把课表粘在这里</span>
                <textarea
                  className="input input--area input--tall"
                  value={text}
                  onChange={(e) => onText(e.target.value)}
                  spellCheck={false}
                  placeholder={"细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 桑浦山-细胞生物学实验室-理433\n高等数学 | 周一 1-2节,周三 3-4节 | 1-16周 | 张三 | 桑浦山-E305"}
                />
                <span className="field__hint">一行一门课，竖线或制表符分隔。从 Excel 直接复制也行。</span>
              </label>
              <HowToFill />
            </>
          )}

          {method === "csv" && (
            <div className="card card--pad stack-3">
              <p className="muted">
                Excel 里按「课程名称 / 星期 / 节次 / 周次 / 教师 / 地点」六列填，存成 CSV UTF-8 再选进来。
                先下模板最省事。
              </p>
              <div className="row-actions">
                <button type="button" className="btn btn--soft" onClick={() => void onTemplate()}>
                  下载模板
                  <Icon name="arrow-right" size={16} />
                </button>
                <button type="button" className="btn btn--soft" onClick={() => file.current?.click()}>
                  选择文件
                </button>
              </div>
              <input
                ref={file}
                type="file"
                accept=".csv,text/csv,text/plain"
                hidden
                onChange={(e) => {
                  const picked = e.target.files?.[0];
                  e.target.value = "";
                  if (picked) void onFile(picked);
                }}
              />
              {text && (
                <p className="muted">
                  已读到 {text.split(/\r?\n/).filter((l) => l.trim()).length} 行，点下面「解析」看看对不对。
                </p>
              )}
            </div>
          )}

          {method === "manual" && <ManualForm onSubmit={onManual} />}
        </motion.div>
      </AnimatePresence>

      {problem && (
        <p className="field__error" role="alert">
          {problem}
        </p>
      )}

      {method !== "manual" && (
        <button
          type="button"
          className="btn btn--primary btn--block btn--lg"
          disabled={busy || !text.trim()}
          onClick={() => onParse(method === "csv" ? "csv" : "paste")}
        >
          {busy ? (
            <>
              <span className="spinner" aria-hidden="true" /> 正在解析…
            </>
          ) : (
            "解析"
          )}
        </button>
      )}
    </>
  );
}

/* ------------------------------------------------------- 怎么填（折叠） */

function HowToFill() {
  return (
    <details className="details">
      <summary>怎么填（三种格式，抄一种就行）</summary>
      <div className="stack-3">
        <div>
          <p className="field__label">① 一行一课（推荐）</p>
          <pre className="pre">
            {`课程名 | 时间 | 周次 | 教师 | 地点
细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 桑浦山-理433
高等数学 | 周一 1-2节,周三 3-4节 | 1-16周 | 张三 | 桑浦山-E305`}
          </pre>
          <p className="muted">竖线换成制表符（Tab）也行。时间和周次能省，但课程名必须有。</p>
        </div>
        <div>
          <p className="field__label">② 从 Excel 直接复制</p>
          <pre className="pre">
            {`课程名称\t星期\t节次\t周次\t教师\t地点
细胞生物学实验\t四\t6-9\t1-16\t何昭明\t理433`}
          </pre>
          <p className="muted">
            表头认这些写法：课程名称/课程名/课程、星期/周几、节次/节/时间、周次/周、教师/老师、地点/教室。
          </p>
        </div>
        <div>
          <p className="field__label">③ 简写也认</p>
          <pre className="pre">
            {`周四 6-9节   星期四 第6-9节   四 6-9   Thu 6-9
1-16周  第1-16周  1,3,5周  1-8,10-16周  1-16单周
节次：6-9   第6节-第9节   6,7,8,9`}
          </pre>
          <p className="muted">
            节次 1–13（对应学校的 1–9、0、A、B、C），周次 1–30，星期 一…日 或 1–7。
          </p>
        </div>
      </div>
    </details>
  );
}

/* ---------------------------------------------------- 一条一条加 */

function ManualForm({ onSubmit }: { onSubmit: (drafts: Draft[]) => void }) {
  const [drafts, setDrafts] = useState<Draft[]>([]);
  const [title, setTitle] = useState("");
  const [weekdays, setWeekdays] = useState<number[]>([]);
  const [periods, setPeriods] = useState<number[]>([]);
  const [from, setFrom] = useState(1);
  const [to, setTo] = useState(16);
  const [teacher, setTeacher] = useState("");
  const [location, setLocation] = useState("");
  const [warn, setWarn] = useState<string | null>(null);

  const toggle = (list: number[], v: number) =>
    list.includes(v) ? list.filter((x) => x !== v) : [...list, v].sort((a, b) => a - b);

  const add = () => {
    if (!title.trim()) return setWarn("先写课程名。");
    if (weekdays.length === 0) return setWarn("至少选一个星期。");
    if (periods.length === 0) return setWarn("至少选一节。");
    if (!(from >= 1 && to <= 30 && from <= to)) return setWarn("周次要在 1–30 之间，而且开始不能晚于结束。");
    setWarn(null);
    setDrafts((prev) => [
      ...prev,
      {
        key: `${title}-${Date.now()}`,
        title: title.trim(),
        weekdays,
        periods,
        from,
        to,
        teacher: teacher.trim(),
        location: location.trim(),
      },
    ]);
    setTitle("");
    setTeacher("");
    setLocation("");
    setWeekdays([]);
    setPeriods([]);
  };

  return (
    <div className="stack-4">
      <div className="card card--pad stack-4">
        <label className="field">
          <span className="field__label">课程名</span>
          <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="细胞生物学实验" />
        </label>

        <div className="field">
          <span className="field__label">星期（可多选）</span>
          <div className="chips">
            {WEEKDAY_LABELS.map((label, i) => {
              const wd = i + 1;
              const on = weekdays.includes(wd);
              return (
                <button
                  key={wd}
                  type="button"
                  aria-pressed={on}
                  className={`chip chip--btn${on ? " is-on" : ""}`}
                  onClick={() => setWeekdays((prev) => toggle(prev, wd))}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="field">
          <span className="field__label">节次（可多选，1–13）</span>
          <div className="chips">
            {PERIODS.map((p) => {
              const on = periods.includes(p.index);
              return (
                <button
                  key={p.index}
                  type="button"
                  aria-pressed={on}
                  aria-label={`第 ${p.code} 节 ${p.start}–${p.end}`}
                  className={`chip chip--btn${on ? " is-on" : ""}`}
                  onClick={() => setPeriods((prev) => toggle(prev, p.index))}
                >
                  {p.code}
                </button>
              );
            })}
          </div>
          <span className="field__hint">选中的节次：{periods.length ? `第 ${periods.map((p) => PERIOD_CODE.get(p)).join("、")} 节` : "还没选"}</span>
        </div>

        <div className="row-actions">
          <label className="field">
            <span className="field__label">从第几周</span>
            <input
              className="input"
              type="number"
              min={1}
              max={30}
              value={from}
              onChange={(e) => setFrom(Number(e.target.value) || 1)}
            />
          </label>
          <label className="field">
            <span className="field__label">到第几周</span>
            <input
              className="input"
              type="number"
              min={1}
              max={30}
              value={to}
              onChange={(e) => setTo(Number(e.target.value) || 1)}
            />
          </label>
        </div>

        <label className="field">
          <span className="field__label">教师</span>
          <input className="input" value={teacher} onChange={(e) => setTeacher(e.target.value)} placeholder="何昭明" />
        </label>
        <label className="field">
          <span className="field__label">地点</span>
          <input
            className="input"
            value={location}
            onChange={(e) => setLocation(e.target.value)}
            placeholder="桑浦山-细胞生物学实验室-理433"
          />
        </label>

        {warn && (
          <p className="field__error" role="alert">
            {warn}
          </p>
        )}
        <button type="button" className="btn btn--soft btn--block" onClick={add}>
          再加一门
        </button>
      </div>

      {drafts.length > 0 && (
        <div className="card card--pad">
          <p className="field__label">
            已经加了 {drafts.length} 门
          </p>
          <ul className="list">
            {drafts.map((d) => (
              <li key={d.key} className="draft">
                <div className="draft__main">
                  <p className="draft__title">{d.title}</p>
                  <p className="muted">
                    {d.weekdays.map((w) => WEEKDAY_LABELS[w - 1]).join("、")} ·{" "}
                    第 {d.periods.map((p) => PERIOD_CODE.get(p)).join("、")} 节 ·{" "}
                    {d.from === d.to ? `${d.from}` : `${d.from}–${d.to}`} 周
                    {d.teacher && ` · ${d.teacher}`}
                  </p>
                </div>
                <button
                  type="button"
                  className="icon-btn"
                  aria-label={`删掉 ${d.title}`}
                  onClick={() => setDrafts((prev) => prev.filter((x) => x.key !== d.key))}
                >
                  <Icon name="trash" size={18} />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <button
        type="button"
        className="btn btn--primary btn--block btn--lg"
        disabled={drafts.length === 0}
        onClick={() => onSubmit(drafts)}
      >
        用这 {drafts.length} 门课生成预览
      </button>
    </div>
  );
}

/* ---------------------------------------------------------- 第 3 步 */

function StepPreview({
  preview,
  problem,
  busy,
  canSave,
  note,
  onNote,
  onBack,
  onSave,
}: {
  preview: ImportPreview;
  problem: string | null;
  busy: boolean;
  canSave: boolean;
  note: string;
  onNote: (v: string) => void;
  onBack: () => void;
  onSave: () => void;
}) {
  const { stats, errors, warnings, unarranged } = preview;
  const reduce = useReducedMotion() === true;

  return (
    <>
      <button type="button" className="back" onClick={onBack}>
        <Icon name="chevron-left" size={18} />
        返回修改
      </button>

      <div className="scoreboard">
        <div className="scoreboard__cell">
          <p className="scoreboard__k">课程</p>
          <p className="scoreboard__v num">
            {stats.courses}
            <span className="scoreboard__u">门</span>
          </p>
        </div>
        <div className="scoreboard__cell">
          <p className="scoreboard__k">上课</p>
          <p className="scoreboard__v num">
            {stats.meetings}
            <span className="scoreboard__u">段</span>
          </p>
        </div>
        <div className="scoreboard__cell">
          <p className="scoreboard__k">周次</p>
          <p className="scoreboard__v num scoreboard__v--sm">{weeksRangeText(stats.weeks)}</p>
        </div>
      </div>

      {errors.length > 0 && (
        <div className="card card--pad stack-2">
          <p className="field__label field__label--bad">
            <Icon name="alert" size={16} /> 有 {errors.length} 处没读懂
          </p>
          <ul className="err-list">
            {errors.map((e, i) => (
              <li key={`${i}-${e}`}>{e}</li>
            ))}
          </ul>
          <p className="muted">这几行先跳过了，改好再解析一次；其余课可以照常导入。</p>
        </div>
      )}

      {warnings.length > 0 && (
        <div className="card card--pad stack-2">
          <p className="field__label">顺手提一下</p>
          <ul className="list">
            {warnings.map((w, i) => (
              <li key={`${i}-${w}`} className="muted">
                {w}
              </li>
            ))}
          </ul>
        </div>
      )}

      {preview.courses.length > 0 && <WeekGrid preview={preview} reduce={reduce} />}

      {unarranged.length > 0 && (
        <div className="card card--pad stack-2">
          <p className="field__label">没排时间的课（{unarranged.length} 门）</p>
          <div className="chips">
            {unarranged.map((u) => (
              <span key={u.id} className="chip">
                {u.title}
              </span>
            ))}
          </div>
          <p className="muted">这些会挂在课表页「需要留意」里，不会画进格子。</p>
        </div>
      )}

      <label className="field">
        <span className="field__label">备注（自己看，可留空）</span>
        <input
          className="input"
          value={note}
          onChange={(e) => onNote(e.target.value)}
          placeholder="比如：医学院教务系统导出"
          maxLength={200}
        />
      </label>

      {problem && (
        <p className="field__error" role="alert">
          {problem}
        </p>
      )}

      <button type="button" className="btn btn--primary btn--block btn--lg" disabled={!canSave} onClick={onSave}>
        {busy ? (
          <>
            <span className="spinner" aria-hidden="true" /> 正在导入…
          </>
        ) : errors.length > 0 ? (
          `先导入读懂的 ${stats.courses} 门`
        ) : (
          "确认导入"
        )}
      </button>
      {errors.length > 0 && canSave && (
        <p className="muted">
          也可以先导进去，回头把那几行改好再导入一次 —— 同一学期是整份覆盖，不会留两份。
        </p>
      )}
      {!canSave && !busy && (
        <p className="muted">一门课都没解析出来，先回去改一下。每一行至少要有课程名和「周几 第几节」。</p>
      )}
    </>
  );
}

/* ------------------------------------------------- 7 × 13 周视图预览 */

function WeekGrid({ preview, reduce }: { preview: ImportPreview; reduce: boolean }) {
  // 格子 → 最先生效的那门课；同格撞车时保留第一门，颜色提示会重复
  const cells = useMemo(() => {
    const map = new Map<string, { hue: number; title: string; order: number }>();
    let order = 0;
    for (const course of preview.courses) {
      for (const meeting of course.meetings) {
        for (const period of meeting.periods) {
          const key = `${meeting.weekday}|${period}`;
          if (!map.has(key)) map.set(key, { hue: course.hue, title: course.title, order: order++ });
        }
      }
    }
    return map;
  }, [preview]);

  return (
    <div className="card card--pad">
      <p className="field__label">周视图预览</p>
      <div
        className="grid7"
        role="img"
        aria-label={`课表预览：${preview.stats.courses} 门课，${preview.stats.meetings} 段上课时间`}
      >
        <span />
        {WEEKDAY_LABELS.map((label) => (
          <span key={label} className="grid7__head">
            {label}
          </span>
        ))}

        {PERIODS.map((p) => (
          <PeriodRow key={p.index} period={p.index} code={p.code} cells={cells} reduce={reduce} />
        ))}
      </div>
      <p className="muted">颜色和导入之后的课表一致，看到空的那格就是没录上。</p>
    </div>
  );
}

function PeriodRow({
  period,
  code,
  cells,
  reduce,
}: {
  period: number;
  code: string;
  cells: Map<string, { hue: number; title: string; order: number }>;
  reduce: boolean;
}) {
  return (
    <>
      <span className="grid7__code num">{code}</span>
      {WEEKDAY_LABELS.map((label, i) => {
        const wd = i + 1;
        const hit = cells.get(`${wd}|${period}`);
        if (!hit) return <span key={wd} className="grid7__cell" />;
        return (
          <motion.span
            key={wd}
            title={`${hit.title} · 周${label} 第 ${code} 节`}
            className="grid7__cell is-on"
            initial={reduce ? false : { opacity: 0, scale: 0.55 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={
              reduce
                ? { duration: 0.12 }
                : { type: "spring", stiffness: 420, damping: 24, delay: Math.min(hit.order * 0.012, 0.5) }
            }
            style={{ "--h": hit.hue } as React.CSSProperties}
          />
        );
      })}
    </>
  );
}

/* ---------------------------------------------------------- 第 4 步 */

function StepDone({
  semester,
  school,
  count,
  onWeek,
}: {
  semester: string;
  school: SchoolId | null;
  count: number;
  onWeek: () => void;
}) {
  const reduce = useReducedMotion() === true;
  return (
    <motion.div
      className="stack-4"
      initial={reduce ? false : { opacity: 0, scale: 0.96 }}
      animate={{ opacity: 1, scale: 1 }}
      transition={reduce ? { duration: 0.12 } : { type: "spring", stiffness: 300, damping: 22 }}
    >
      <div className="card card--pad stack-2">
        <p className="status-line status-line--ok" role="status">
          <Icon name="check" size={18} /> 课表已经存好了
        </p>
        <p className="muted">
          {semester}
          {school && ` · ${SCHOOL_NAME[school]}`} · {count} 门课。
          从这一秒起它和自动同步的课表走同一套页面、同一套作息时间，不用再做什么。
        </p>
      </div>
      <button type="button" className="btn btn--primary btn--block btn--lg" onClick={onWeek}>
        去看课表
        <Icon name="arrow-right" size={18} />
      </button>
      <p className="muted">录错了随时回来重录：同一学期再导入一次就是整份覆盖，不会留两份。</p>
    </motion.div>
  );
}
