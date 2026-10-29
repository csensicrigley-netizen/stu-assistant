import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useShell, useStore } from "../lib/store";
import { daysUntil, monthDayCn, moment, spanText, useNow, weekdayCn } from "../lib/clock";
import { phaseOf, termPosition, upcomingMilestones } from "../lib/calendar";
import { nextClass, planFor } from "../lib/plan";
import { gapsBetween, minutesOf, periodsText, roomHops, type Block } from "../lib/timetable";
import { dueBucket, SOURCE_NAME } from "../lib/records";
import { HeroOverlay, useHeroEffect } from "../components/HeroFx";
import { DayRibbon } from "../components/DayRibbon";
import { CourseSheet } from "../components/CourseSheet";
import { Icon } from "../components/Icon";
import { Magnetic } from "../components/Magnetic";
import { ModePill, Notice, PageHead, Section, rise } from "../components/Layout";
import { GLIDE, JELLY_SOFT, TAP, stagger } from "../lib/motion";

export function TodayScreen() {
  const store = useStore();
  const { go, connect } = useShell();
  const now = useNow(20_000);
  const [open, setOpen] = useState<Block | null>(null);

  const plan = useMemo(() => planFor(store.timetable, now.date), [store.timetable, now.date]);
  const pos = termPosition(now.date);
  const phase = pos ? phaseOf(pos.term, pos.week) : null;
  const blocks = plan.blocks;
  const current = blocks.find((b) => now.minutes >= b.startMin && now.minutes < b.endMin) ?? null;
  const upcoming = blocks.find((b) => b.startMin > now.minutes) ?? null;
  const later = useMemo(
    () => (current || upcoming ? null : nextClass(store.timetable, now.date, 24 * 60)),
    [current, upcoming, store.timetable, now.date],
  );

  const hops = roomHops(blocks);
  const total = minutesOf(blocks);
  const gaps = gapsBetween(blocks);
  const waiting = !store.sample && !store.timetable;

  const tasks = store.tasks
    .filter((t) => !store.done.has(t.id))
    .map((t) => ({ t, bucket: dueBucket(t, now.ms) }))
    .filter((x) => x.bucket === "overdue" || x.bucket === "today" || x.bucket === "soon")
    .sort((a, b) => (a.t.dueMs ?? 0) - (b.t.dueMs ?? 0))
    .slice(0, 4);

  const milestones = upcomingMilestones(now.date, 2);
  const notices = store.notices.slice(0, 4);

  return (
    <>
      <PageHead
        eyebrow={pos ? `第 ${pos.week} 周 · ${phase?.label ?? ""} · ${weekdayCn(now.weekday)}` : weekdayCn(now.weekday)}
        title={monthDayCn(now.date)}
        aside={<ModePill />}
        sub={
          waiting
            ? "正在准备你的课表"
            : plan.status.kind === "closed"
              ? `${plan.status.label}，今天不上课`
              : blocks.length
                ? `${blocks.length} 节课 · 在教室 ${spanText(total)}${hops.length ? ` · 换 ${hops.length} 次教室` : ""}`
                : pos
                  ? "今天没有理论课"
                  : "不在学期内"
        }
      />

      {store.needsReconnect.length > 0 && (
        <Notice action={{ label: "重新连接", onClick: connect }}>
          {store.needsReconnect.map((s) => SOURCE_NAME[s] ?? s).join("、")}的登录已过期，课表和作业暂时停在上次同步。
        </Notice>
      )}

      <div className="split">
        <div className="split__main">
          <motion.div {...rise(0)}>
            {waiting ? (
              <FirstSync />
            ) : (
              <NowCard
                current={current}
                upcoming={upcoming}
                later={later}
                blocks={blocks}
                nowMin={now.minutes}
                closedLabel={plan.status.kind === "closed" ? plan.status.label : null}
                makeup={plan.status.kind === "makeup" ? plan.status.note : null}
                onOpen={setOpen}
              />
            )}
          </motion.div>

          {store.sample && (
            <motion.div {...rise(1)}>
              {/* 引力吸附：鼠标靠近时整块向指针偏 3–4px，离开弹回。
                  触屏与"减少动态效果"下自动关掉（见 components/Magnetic.tsx）。 */}
              <Magnetic pull={0.55}>
                <button type="button" className="cta" onClick={connect}>
                  <span className="cta__icon" aria-hidden="true">
                    <Icon name="shield" size={20} />
                  </span>
                  <span className="cta__text">
                    <b>这是示例课表</b>
                    <small>连接校园账号后，换成你自己的课表、作业和成绩</small>
                  </span>
                  <Icon name="chevron-right" size={18} />
                </button>
              </Magnetic>
            </motion.div>
          )}

          {blocks.length > 0 && (
            <motion.div {...rise(2)}>
              <Section title="今天的时间">
                <div className="card card--pad">
                  <DayRibbon blocks={blocks} nowMin={now.minutes} />
                </div>
                <ol className="agenda" aria-label="今天的课和空档">
                  {[...blocks.map((b) => ({ at: b.startMin, b })), ...gaps.map((g) => ({ at: g.startMin, g }))]
                    .sort((x, y) => x.at - y.at)
                    .map((row, i) =>
                      "b" in row && row.b ? (
                        <motion.li key={row.b.key} {...rise(i, { y: 8, scale: 0.995 })}>
                          <AgendaBlock block={row.b} nowMin={now.minutes} onOpen={setOpen} />
                        </motion.li>
                      ) : "g" in row && row.g ? (
                        <motion.li
                          key={`g${row.g.start}`}
                          className="agenda__gap"
                          {...rise(i, { y: 6, scale: 1 })}
                        >
                          <span className="agenda__time num">{row.g.start}</span>
                          <span className="agenda__gaptext">
                            {row.g.label} <b>{spanText(row.g.span)}</b>
                          </span>
                        </motion.li>
                      ) : null,
                    )}
                </ol>
              </Section>
            </motion.div>
          )}
        </div>

        <div className="split__side">
          <motion.div {...rise(3)}>
            <Section title="快到期" action={tasks.length > 0 ? { label: "全部作业", onClick: () => go("study") } : undefined}>
              {waiting ? (
                <div className="sk-list" aria-hidden="true">
                  <span className="sk sk--row" />
                  <span className="sk sk--row" />
                </div>
              ) : tasks.length === 0 ? (
                <p className="quiet-line">
                  <Icon name="check" size={16} /> 未来 4 天没有到期的作业
                </p>
              ) : (
                <ul className="list">
                  {tasks.map(({ t, bucket }, i) => (
                    <motion.li key={t.id} className="row-item" {...rise(i, { y: 6, scale: 1 })}>
                      <span className={`due-dot due-dot--${bucket}`} aria-hidden="true" />
                      <span className="row-item__main">
                        <span className="row-item__title">{t.title}</span>
                        <span className="row-item__meta">
                          {t.course} · {SOURCE_NAME[t.source] ?? t.source}
                        </span>
                      </span>
                      <span className={`row-item__side due due--${bucket}`}>{dueText(t.dueMs!, now.ms)}</span>
                    </motion.li>
                  ))}
                </ul>
              )}
            </Section>
          </motion.div>

          {milestones.length > 0 && (
            <motion.div {...rise(4)}>
              <Section title="校历" action={{ label: "查看校历", onClick: () => go("campus") }}>
                <div className="milestones">
                  {milestones.map((m, i) => (
                    <motion.div
                      key={m.label + m.from}
                      className={`milestone milestone--${m.kind}${m.days <= 0 ? " is-now" : ""}`}
                      {...rise(i, { y: 6, scale: 0.99 })}
                    >
                      <span className="milestone__days num">{m.days <= 0 ? "进行中" : m.days}</span>
                      <span className="milestone__unit">{m.days <= 0 ? "" : "天后"}</span>
                      <span className="milestone__label">{m.label}</span>
                      <span className="milestone__date num">
                        {monthDayCn(m.from)}
                        {m.to !== m.from && ` – ${monthDayCn(m.to)}`}
                      </span>
                    </motion.div>
                  ))}
                </div>
              </Section>
            </motion.div>
          )}

          {notices.length > 0 && (
            <motion.div {...rise(5)}>
              <Section title="新通知" action={{ label: "全部通知", onClick: () => go("campus") }}>
                <ul className="list">
                  {notices.map((n, i) => (
                    <motion.li key={n.id} {...rise(i, { y: 6, scale: 1 })}>
                      <a className="row-item row-item--link" href={n.url ?? undefined} target="_blank" rel="noreferrer noopener">
                        <span className="row-item__main">
                          <span className="row-item__title row-item__title--2">{n.title}</span>
                          <span className="row-item__meta">
                            {SOURCE_NAME[n.source] ?? n.source}
                            {n.section && ` · ${n.section}`}
                            {n.publishedRaw && ` · ${n.publishedRaw}`}
                          </span>
                        </span>
                        <Icon name="external" size={16} className="row-item__chev" />
                      </a>
                    </motion.li>
                  ))}
                </ul>
              </Section>
            </motion.div>
          )}
        </div>
      </div>

      <AnimatePresence>
        {open && pos && (
          <CourseSheet key={open.key} block={open} week={pos.week} weekday={now.weekday} nowMin={now.minutes} onClose={() => setOpen(null)} />
        )}
      </AnimatePresence>
    </>
  );
}

function dueText(dueMs: number, nowMs: number): string {
  const m = moment(dueMs);
  const clock = `${String(Math.floor(m.minutes / 60)).padStart(2, "0")}:${String(m.minutes % 60).padStart(2, "0")}`;
  const d = daysUntil(m.date, nowMs);
  if (dueMs < nowMs) return d === 0 ? `今天 ${clock} 已过` : `已过 ${-d} 天`;
  if (d === 0) {
    const left = Math.round((dueMs - nowMs) / 60000);
    return left < 180 ? `${spanText(left)}后` : `今天 ${clock}`;
  }
  if (d === 1) return `明天 ${clock}`;
  return `${d} 天后`;
}

/* ------------------------------------------------------------ first sync */

function FirstSync() {
  const store = useStore();
  const shell = useShell();
  const busy = store.syncing || store.loading;
  return (
    <section className="now now--rest now--wait" aria-live="polite">
      <p className="now__state">
        {busy ? <span className="spinner" aria-hidden="true" /> : <Icon name="info" size={16} />}
        {busy ? "正在从教务读取课表" : "还没有读到课表"}
      </p>
      <h2 className="now__title now__title--sm">
        {busy ? "第一次同步通常要半分钟左右" : store.error ? "这次同步没有完成" : "学校系统还没有返回课表"}
      </h2>
      <p className="now__meta">
        {busy
          ? "读完后这里会显示你现在该去的教室。可以先切到别的页面。"
          : "可能是学校系统在维护（夜间常见），也可能是网络问题。稍后再试一次。"}
      </p>
      {!busy && (
        <button type="button" className="btn btn--soft btn--sm now__retry" onClick={() => void store.refresh("force")}>
          <Icon name="refresh" size={16} /> 再同步一次
        </button>
      )}
              {!busy && (
          <>
            <p className="now__meta" style={{ marginTop: 14 }}>
              医学院的同学：教务系统和主校区不是一套，主同步拿不到课表，走下面这条路。
            </p>
            <div className="row-actions" style={{ marginTop: 8 }}>
              <button type="button" className="btn btn--primary btn--sm" onClick={() => shell.go("medbind")}>
                <Icon name="shield" size={16} /> 连医学院教务
              </button>
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => shell.go("import")}>
                <Icon name="spark" size={16} /> 手动导入课表
              </button>
            </div>
          </>
        )}
{busy && (
        <span className="now__bar now__bar--indeterminate" aria-hidden="true">
          <span />
        </span>
      )}
    </section>
  );
}

/* ------------------------------------------------------------------ now */

function NowCard({
  current,
  upcoming,
  later,
  blocks,
  nowMin,
  closedLabel,
  makeup,
  onOpen,
}: {
  current: Block | null;
  upcoming: Block | null;
  later: ReturnType<typeof nextClass>;
  blocks: Block[];
  nowMin: number;
  closedLabel: string | null;
  makeup: string | null;
  onOpen: (b: Block) => void;
}) {
  // 开场动画：一次会话随机一种，放完就切到「只扫彩虹光」
  const { effect, done: fxDone } = useHeroEffect();

  if (closedLabel) {
    return (
      <section className="now now--rest" aria-label="现在">
        <p className="now__state">
          <Icon name="calendar" size={16} /> 停课
        </p>
        <h2 className="now__title">{closedLabel}</h2>
        <p className="now__meta">按教务处校历，今天没有课。</p>
      </section>
    );
  }

  const block = current ?? upcoming;
  if (block) {
    const idx = blocks.indexOf(block);
    const prev = idx > 0 ? blocks[idx - 1] : null;
    // only worth saying when the walk has to fit inside a short break
    const moved =
      prev && prev.location && block.location && prev.location !== block.location && block.startMin - prev.endMin <= 30;
    const left = current ? block.endMin - nowMin : block.startMin - nowMin;
    const progress = current ? (nowMin - block.startMin) / block.span : 0;
    /*
     * 大卡片自己的进场也按层排：状态 → 时间 → 课名 → 地点 → 备注。
     * 位移只有 6px：这块东西够大，动多了就是在晃。
     */
    const line = (i: number) => ({
      initial: { opacity: 0, y: 6 },
      animate: { opacity: 1, y: 0 },
      transition: { ...JELLY_SOFT, delay: stagger(i) },
    });
    return (
      <motion.button
        type="button"
        className={`now${current ? " now--live" : ""}${fxDone ? " now--sheen" : ""}`}
        style={{ "--h": block.course.hue } as React.CSSProperties}
        onClick={() => onOpen(block)}
        whileTap={{ scale: 0.955 }}
        whileHover={{ scale: 1.006 }}
        transition={TAP}
        aria-label={`${current ? "正在上课" : "下一节"}：${block.course.title}，${block.start} 到 ${block.end}，${block.location || "地点未标注"}`}
      >
        <HeroOverlay effect={effect} time={`${block.start}–${block.end}`} title={block.course.title} />
        <motion.span className="now__state" {...line(0)}>
          <span className={`now__pulse${current ? " is-live" : ""}`} aria-hidden="true" />
          {current ? `正在上课 · 还剩 ${spanText(left)}` : `下一节 · ${left <= 0 ? "马上开始" : `还有 ${spanText(left)}`}`}
        </motion.span>
        <motion.span className="now__time num" {...line(1)}>
          {block.start}
          <span className="now__dash">–</span>
          {block.end}
        </motion.span>
        <motion.span className="now__title" {...line(2)}>
          {block.course.title}
        </motion.span>
        <motion.span className="now__place" {...line(3)}>
          <Icon name="pin" size={18} />
          <span>{block.location || "教务未标注地点"}</span>
        </motion.span>
        <motion.span className="now__meta" {...line(4)}>
          {block.teacher && `${block.teacher} · `}
          {periodsText(block.periods)} · {spanText(block.span)}
        </motion.span>
        {moved && !current && (
          <motion.span className="now__hop" {...line(5)}>
            <Icon name="walk" size={16} />
            换教室：上一节在 {prev!.location}，课间 {spanText(block.startMin - prev!.endMin)}
          </motion.span>
        )}
        {makeup && <span className="now__hop">{makeup}</span>}
        {current && (
          <span className="now__bar" aria-hidden="true">
            <motion.span
              initial={{ scaleX: 0 }}
              animate={{ scaleX: Math.min(1, Math.max(0, progress)) }}
              transition={{ ...GLIDE, delay: 0.18 }}
            />
          </span>
        )}
      </motion.button>
    );
  }

  const done = blocks.length > 0;
  return (
    <section className="now now--rest" aria-label="现在">
      <p className="now__state">
        <Icon name={done ? "check" : "spark"} size={16} />
        {done ? "今天的课上完了" : "今天没有课"}
      </p>
      {later ? (
        <>
          <h2 className="now__title now__title--sm">
            {later.offset === 1 ? "明天" : `${monthDayCn(later.date)} ${weekdayCn(later.plan.weekday)}`}{" "}
            <span className="num">{later.block.start}</span> {later.block.course.title}
          </h2>
          <p className="now__meta">
            <Icon name="pin" size={14} /> {later.block.location || "地点未标注"}
            {later.offset === 1 && later.block.startMin <= 8 * 60 && <span className="tag tag--warn">早八</span>}
          </p>
        </>
      ) : (
        <h2 className="now__title now__title--sm">接下来两周没有排课</h2>
      )}
    </section>
  );
}

function AgendaBlock({ block, nowMin, onOpen }: { block: Block; nowMin: number; onOpen: (b: Block) => void }) {
  const state = nowMin >= block.endMin ? "past" : nowMin >= block.startMin ? "live" : "next";
  return (
    <button
      type="button"
      className={`agenda__block is-${state}`}
      style={{ "--h": block.course.hue } as React.CSSProperties}
      onClick={() => onOpen(block)}
    >
      <span className="agenda__time num">
        {block.start}
        <small>{block.end}</small>
      </span>
      <span className="agenda__card">
        <span className="agenda__title">{block.course.title}</span>
        <span className="agenda__meta">
          {block.location || "地点未标注"}
          {block.teacher && ` · ${block.teacher}`}
        </span>
        {block.conflict && <span className="tag tag--warn">与其他课时间重叠</span>}
      </span>
    </button>
  );
}
