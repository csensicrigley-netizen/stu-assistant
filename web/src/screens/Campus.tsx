import { useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { useStore } from "../lib/store";
import { api } from "../lib/api";
import { agoText, monthDayCn, moment, useNow } from "../lib/clock";
import {
  CALENDAR_SOURCE,
  currentOrNextTerm,
  mondayOf,
  termPosition,
  termProgress,
  upcomingMilestones,
} from "../lib/calendar";
import {
  CHARGING_SOURCE,
  ENTRANCES,
  LIBRARY,
  TRANSIT,
  WEBVPN_URL,
  fetchChargingAll,
  libraryOpenNow,
  type ChargingSnapshot,
  type Entrance,
} from "../lib/live";
import { SOURCE_NAME } from "../lib/records";
import { Segmented } from "../components/Segmented";
import { Icon } from "../components/Icon";
import { BusBoard } from "../components/BusBoard";
import { Empty, ModePill, PageHead, Section } from "../components/Layout";

type View = "notices" | "life" | "calendar";

export function CampusScreen() {
  const [view, setView] = useState<View>("notices");
  return (
    <>
      <PageHead eyebrow="汕头大学" title="校园" aside={<ModePill />} />
      <Segmented
        label="校园分类"
        value={view}
        onChange={setView}
        options={[
          { key: "notices", label: "通知" },
          { key: "life", label: "生活" },
          { key: "calendar", label: "校历" },
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
          {view === "notices" && <Notices />}
          {view === "life" && <Life />}
          {view === "calendar" && <Calendar />}
        </motion.div>
      </AnimatePresence>
    </>
  );
}

/* --------------------------------------------------------------- notices */

function Notices() {
  const { notices } = useStore();
  const [q, setQ] = useState("");
  const [src, setSrc] = useState<string>("all");
  const [facts, setFacts] = useState<{ text: string; index: number }[]>([]);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const res = await api.facts();
        if (alive) setFacts(res.items ?? []);
      } catch {
        /* 拉不到就不显示，不影响官网消息 */
      }
    };
    void load();
    const id = window.setInterval(() => void load(), 30 * 60_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  const sources = [...new Set(notices.map((n) => n.source))];
  const shown = notices.filter(
    (n) => (src === "all" || n.source === src) && (!q.trim() || n.title.includes(q.trim()) || n.section.includes(q.trim())),
  );

  const groups = useMemo(() => {
    const today = moment().date;
    const out: { label: string; items: typeof shown }[] = [];
    for (const n of shown) {
      const d = n.publishedMs ? moment(n.publishedMs + 3_600_000).date : "";
      const label = !d ? "日期未标注" : d === today ? "今天" : monthDayCn(d);
      const last = out[out.length - 1];
      if (last && last.label === label) last.items.push(n);
      else out.push({ label, items: [n] });
    }
    return out;
  }, [shown]);

  return (
    <div className="stack-6">
      <Section title="今日冷知识" note={facts.length ? `${facts.length} 条` : "每天 5 条"}>
        {facts.length > 0 ? (
          <ul className="list">
            {facts.map((f) => (
              <li key={f.index} className="notice" style={{ cursor: "default" }}>
                <span className="notice__src notice__src--oa">冷知识</span>
                <span className="notice__title">{f.text}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="cite" style={{ padding: "4px 2px" }}>
            今天这 5 条还没生成，过一会儿再来看看。
          </p>
        )}
      </Section>

      <label className="search">
        <Icon name="search" size={18} />
        <input
          type="search"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="搜索通知标题，例如「竞赛」「奖学金」"
          aria-label="搜索通知"
          enterKeyHint="search"
        />
        {q && (
          <button type="button" className="search__clear" onClick={() => setQ("")} aria-label="清除搜索">
            <Icon name="close" size={16} />
          </button>
        )}
      </label>
      {sources.length > 1 && (
        <div className="chips" role="radiogroup" aria-label="通知来源">
          {["all", ...sources].map((s) => (
            <button
              key={s}
              type="button"
              role="radio"
              aria-checked={src === s}
              className={`chip chip--btn${src === s ? " is-on" : ""}`}
              onClick={() => setSrc(s)}
            >
              {s === "all" ? "全部" : SOURCE_NAME[s] ?? s}
            </button>
          ))}
        </div>
      )}

      {shown.length === 0 ? (
        <Empty icon={<Icon name="bell" size={22} />} title={q ? "没有匹配的通知" : "缓存里还没有通知"}>
          {q ? "换个关键词试试。只搜索已同步到这里的通知。" : "同步后会出现在这里。没有记录不代表学校没有发布。"}
        </Empty>
      ) : (
        groups.map((g) => (
          <Section key={g.label} title={g.label} note={`${g.items.length} 条`}>
            <ul className="list">
              {g.items.map((n) => (
                <li key={n.id}>
                  <a className="notice" href={n.url ?? undefined} target="_blank" rel="noreferrer noopener">
                    <span className={`notice__src notice__src--${n.source}`}>{SOURCE_NAME[n.source] ?? n.source}</span>
                    <span className="notice__title">{n.title}</span>
                    <span className="notice__meta">
                      {n.section || "未标注栏目"}
                      {n.publishedRaw && ` · ${n.publishedRaw}`}
                    </span>
                    <Icon name="external" size={16} className="notice__go" />
                  </a>
                </li>
              ))}
            </ul>
          </Section>
        ))
      )}
      <p className="footnote">
        <Icon name="info" size={14} />
        <span>标题和日期按学校原文显示，正式要求以原文为准。冷知识每日更新，图个乐子，别拿去写论文。</span>
      </p>
    </div>
  );
}

/* ------------------------------------------------------------------ life */

function Life() {
  const now = useNow(60_000);
  const [charging, setCharging] = useState<ChargingSnapshot[]>([]);
  const [place, setPlace] = useState<string | null>(null);

  // public data, no account needed; refreshed every two minutes while visible
  useEffect(() => {
    let alive = true;
    const load = async () => {
      if (document.visibilityState !== "visible") return;
      const list = await fetchChargingAll();
      if (alive) setCharging(list);
    };
    void load();
    const id = window.setInterval(load, 120_000);
    return () => {
      alive = false;
      window.clearInterval(id);
    };
  }, []);

  const places = useMemo(() => {
    const map = new Map<string, { name: string; free: number; total: number; live: boolean; at: number | null; missing: number }>();
    for (const c of charging) {
      const cur = map.get(c.device.location) ?? { name: c.device.location, free: 0, total: 0, live: true, at: null, missing: 0 };
      // 没拿到数据的设备不进 free/total：否则整组会显示成 0/20，看着像"全被占了"
      if (c.unavailable) {
        cur.missing += 1;
        cur.live = false;
      } else {
        cur.free += c.counts.free;
        cur.total += c.counts.total;
        cur.live = cur.live && c.live;
        cur.at = c.at && (!cur.at || c.at < cur.at) ? c.at : cur.at;
      }
      map.set(c.device.location, cur);
    }
    return [...map.values()].sort((a, b) => b.free - a.free);
  }, [charging]);
  const liveCount = charging.filter((c) => c.live).length;
  const allLive = charging.length > 0 && liveCount === charging.length;
  const newest = charging.reduce<number | null>((m, c) => (c.live && c.at && (!m || c.at > m) ? c.at : m), null);
  const devices = charging.filter((c) => c.device.location === place);

  return (
    <div className="stack-8">
      <Section
        title="宿舍水电"
        note={
          <span className="tag tag--quiet">
            <Icon name="shield" size={12} /> 需校园网
          </span>
        }
      >
        <div className="utility">
          <div className="utility__icon" aria-hidden="true">
            <Icon name="bolt" size={22} />
          </div>
          <div className="utility__body">
            <p className="utility__title">查电费、看读数、自助缴费</p>
            <p className="utility__text">
              学校的水电系统只在校园网内开放。在宿舍或教学区连上校园 Wi-Fi 可直接打开；在校外，先登录 WebVPN，再从 WebVPN 门户进入。
            </p>
            <div className="utility__actions">
              <a className="btn btn--primary btn--sm" href="http://power.stu.edu.cn/" target="_blank" rel="noreferrer noopener">
                水电查询
              </a>
              <a className="btn btn--soft btn--sm" href="http://192.168.48.49:8080/hpao/main" target="_blank" rel="noreferrer noopener">
                缴费平台
              </a>
              <a className="btn btn--ghost btn--sm" href={WEBVPN_URL} target="_blank" rel="noreferrer noopener">
                WebVPN
              </a>
            </div>
            <p className="utility__fine">
              两个页面都是学校的 HTTP 地址，登录时注意确认地址栏。助手只帮你打开入口，不读取账单，也不会代你付款。
            </p>
          </div>
        </div>
      </Section>

      <Section
        title="充电桩"
        note={
          charging.length === 0 ? undefined : allLive ? (
            <span className="tag tag--live">实时</span>
          ) : liveCount > 0 ? (
            <span className="tag tag--warn">{charging.filter((c) => c.unavailable).length} 台暂无数据</span>
          ) : (
            <span className="tag tag--warn" title="充电平台暂时连不上，下面是示意数据">
              示意数据
            </span>
          )
        }
      >
        {charging.length === 0 ? (
          <div className="sk-grid">
            {Array.from({ length: 6 }, (_, i) => (
              <span key={i} className="sk" />
            ))}
          </div>
        ) : (
          <>
            <div className="places">
              {places.map((p) => {
                const ratio = p.total ? p.free / p.total : 0;
                const on = place === p.name;
                return (
                  <button
                    key={p.name}
                    type="button"
                    className={`place${on ? " is-on" : ""}`}
                    aria-pressed={on}
                    onClick={() => setPlace(on ? null : p.name)}
                    aria-label={
                      p.total === 0
                        ? `${p.name}，暂无数据`
                        : `${p.name}，${p.total} 个口中 ${p.free} 个空闲`
                    }
                  >
                    <span className="place__name">{p.name}</span>
                    <span className="place__count">
                      {p.total === 0 ? (
                        <span className="num muted">暂无</span>
                      ) : (
                        <>
                          <b className={`num${p.free === 0 ? " is-zero" : ""}`}>{p.free}</b>
                          <span className="num">/{p.total}</span>
                        </>
                      )}
                    </span>
                    <span className="place__bar" aria-hidden="true">
                      <span style={{ width: `${ratio * 100}%` }} />
                    </span>
                  </button>
                );
              })}
            </div>
            <AnimatePresence initial={false}>
              {place && (
                <motion.div
                  key={place}
                  className="devices"
                  initial={{ opacity: 0, height: 0 }}
                  animate={{ opacity: 1, height: "auto" }}
                  exit={{ opacity: 0, height: 0 }}
                  transition={{ duration: 0.26, ease: [0.22, 1, 0.36, 1] }}
                >
                  {devices.map((d) => (
                    <div key={d.device.code} className="device">
                      <div className="device__head">
                        <span>
                          {d.device.name}
                          {d.device.verified === false ? <span className="muted">（名称待核）</span> : null}
                        </span>
                        <span className="num muted">
                          {d.unavailable ? (
                            "暂无数据"
                          ) : (
                            <>
                              空闲 {d.counts.free} · 占用 {d.counts.occupied}
                              {d.counts.fault > 0 && ` · 故障 ${d.counts.fault}`}
                            </>
                          )}
                        </span>
                      </div>
                      {d.unavailable ? (
                        <p className="muted device__empty">
                          {d.unavailable === "device_offline"
                            ? "平台说这台设备当前不在线，等它上线再刷新。"
                            : "这一轮平台没有回数据，稍后自动重试。"}
                        </p>
                      ) : (
                        <div className="ports" role="img" aria-label={`${d.device.name}：${d.counts.free} 个空闲，${d.counts.occupied} 个占用`}>
                          {d.ports.map((p) => (
                            <span key={p.num} className={`port port--${p.state === 1 ? "free" : p.state === 2 ? "used" : "bad"}`} title={`${p.num} 号 · ${p.label}`} />
                          ))}
                        </div>
                      )}
                    </div>
                  ))}
                </motion.div>
              )}
            </AnimatePresence>
          </>
        )}
        <p className="footnote">
          <Icon name="info" size={14} />
          <span>
            {CHARGING_SOURCE.provider}，{CHARGING_SOURCE.scope}。
            {CHARGING_SOURCE.method ? `${CHARGING_SOURCE.method}（${CHARGING_SOURCE.registryVerifiedOn}）。` : ""}
            {newest ? `采样于 ${agoText(newest)}，每两分钟更新。` : "充电平台暂时连不上，显示的是示意数据。"}
          </span>
        </p>
        {charging.some((c) => c.device.verified === false) ? (
          <p className="footnote">
            <Icon name="info" size={14} />
            <span>标「名称待核」的设备：编号在平台上有效，但地点名还没有二维码可对，先按原清单名称显示。</span>
          </p>
        ) : null}
        <div className="card card--list">
          {LIBRARY.halls.map((h) => {
            const s = libraryOpenNow(h.open, h.close, now);
            return (
              <div key={h.campus} className="hall">
                <span className={`hall__dot${s.open ? " is-open" : ""}`} aria-hidden="true" />
                <span className="hall__name">{h.campus}</span>
                <span className="hall__state">
                  <b>{s.open ? "开放中" : "已闭馆"}</b>
                  <span className="num">{s.text}</span>
                </span>
              </div>
            );
          })}
        </div>
        <ul className="notes">
          {LIBRARY.notes.map((n) => (
            <li key={n}>{n}</li>
          ))}
        </ul>
      </Section>

      <Section title="实时公交">
        <BusBoard />
      </Section>

      <Section title="到桑浦山校区">
        <div className="card card--pad">
          <div className="buslines" aria-label="可到达桑浦山校区的公交线路">
            {TRANSIT.sangpushan.map((l) => (
              <span key={l} className="busline num">
                {l}
              </span>
            ))}
          </div>
          <ul className="notes notes--inset">
            {TRANSIT.notes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
          <div className="row-actions">
            {TRANSIT.realtime.map((r) => (
              <a key={r.name} className="btn btn--soft btn--sm" href={r.url} target="_blank" rel="noreferrer noopener">
                {r.name}查实时
                <Icon name="external" size={14} />
              </a>
            ))}
          </div>
        </div>
        <p className="footnote">
          <Icon name="info" size={14} />
          <span>线路来自{TRANSIT.sourceLabel}。助手不提供实时到站：唯一找到的实时数据源不允许第三方转用。</span>
        </p>
      </Section>

      <Section title="常用入口">
        <Entrances />
      </Section>
    </div>
  );
}

function Entrances() {
  const groups: Entrance["group"][] = ["学习", "缴费", "办事", "生活"];
  return (
    <div className="stack-4">
      {groups.map((g) => (
        <div key={g}>
          <p className="group-label">{g}</p>
          <ul className="entries">
            {ENTRANCES.filter((e) => e.group === g).map((e) => (
              <li key={e.url}>
                <a className="entry" href={e.url} target="_blank" rel="noreferrer noopener">
                  <span className="entry__name">{e.name}</span>
                  <span className="entry__hint">{e.hint}</span>
                  {(e.campusOnly || e.insecure) && (
                    <span className="entry__flags">
                      {e.campusOnly && <span className="tag tag--quiet">校内 / WebVPN</span>}
                      {e.insecure && <span className="tag tag--quiet">HTTP</span>}
                    </span>
                  )}
                </a>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  );
}

/* -------------------------------------------------------------- calendar */

function Calendar() {
  const now = useNow(60_000);
  const pos = termPosition(now.date);
  const term = pos?.term ?? currentOrNextTerm(now.date);
  const progress = termProgress(term, now.ms);
  const milestones = upcomingMilestones(now.date, 6);

  return (
    <div className="stack-8">
      <div className="term">
        <p className="term__label">{term.label}</p>
        <p className="term__now">
          {pos ? (
            <>
              第 <b className="num">{pos.week}</b> 周<span className="term__of num"> / {term.totalWeeks}</span>
              <span className="term__phase">{pos.phase?.label}</span>
            </>
          ) : (
            "假期中"
          )}
        </p>
        <div className="term__track" aria-label={`学期已过 ${Math.round(progress * 100)}%`}>
          {term.phases.map((p) => (
            <span
              key={p.from}
              className={`term__phasebar term__phasebar--${p.kind}`}
              style={{ flexGrow: p.to - p.from + 1 }}
              title={`${p.label} 第 ${p.from}–${p.to} 周`}
            >
              <span>{p.label}</span>
            </span>
          ))}
          <span className="term__cursor" style={{ left: `${progress * 100}%` }} aria-hidden="true" />
        </div>
        <div className="term__ends num">
          <span>{monthDayCn(term.week1Monday)}</span>
          <span>{monthDayCn(term.after.from)} {term.after.label}</span>
        </div>
      </div>

      <Section title="接下来">
        <ol className="timeline">
          {milestones.map((m) => (
            <li key={m.label + m.from} className={`timeline__item timeline__item--${m.kind}`}>
              <span className="timeline__dot" aria-hidden="true" />
              <div className="timeline__body">
                <p className="timeline__title">{m.label}</p>
                <p className="timeline__date num">
                  {monthDayCn(m.from)}
                  {m.to !== m.from && ` – ${monthDayCn(m.to)}`}
                </p>
              </div>
              <span className="timeline__days num">{m.days <= 0 ? "进行中" : `${m.days} 天`}</span>
            </li>
          ))}
        </ol>
      </Section>

      <Section title="本学期每周">
        <div className="weeks-grid" role="list">
          {Array.from({ length: term.totalWeeks }, (_, i) => {
            const w = i + 1;
            const phase = term.phases.find((p) => w >= p.from && w <= p.to);
            const cur = pos?.week === w;
            return (
              <span key={w} role="listitem" className={`wk wk--${phase?.kind ?? "teaching"}${cur ? " is-now" : ""}${pos && w < pos.week ? " is-past" : ""}`}>
                <b className="num">{w}</b>
                <small className="num">{monthDayCn(mondayOf(term, w)).replace("月", "/").replace("日", "")}</small>
              </span>
            );
          })}
        </div>
        <div className="legend">
          <span><i className="lg lg--practice" />实践周</span>
          <span><i className="lg lg--teaching" />教学周</span>
          <span><i className="lg lg--exam" />考试周</span>
        </div>
      </Section>

      {term.makeupDays.length > 0 && (
        <Section title="调休">
          <ul className="notes">
            {term.makeupDays.map((m) => (
              <li key={m.date}>
                <b className="num">{monthDayCn(m.date)}</b> {m.note}
              </li>
            ))}
          </ul>
        </Section>
      )}

      <a className="source-card" href={CALENDAR_SOURCE.url} target="_blank" rel="noreferrer noopener">
        <Icon name="calendar" size={18} />
        <span>
          <b>{CALENDAR_SOURCE.publisher}《{CALENDAR_SOURCE.title}》</b>
          <small className="num">
            发布于 {CALENDAR_SOURCE.publishedOn} · 核对于 {CALENDAR_SOURCE.checkedOn}。法定假期按国务院办公厅安排补充。
          </small>
        </span>
        <Icon name="external" size={16} />
      </a>
    </div>
  );
}
