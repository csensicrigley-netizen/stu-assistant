/**
 * 到桑浦山校区的实时公交卡片。
 *
 * 数据来自后端 /v1/bus：有上游接口就是真实时，没有就按首末班+间隔推算，
 * 界面上如实标出来，不假装实时。数字每 30 秒自己走一遍，按"分钟"跳动。
 */
import { useEffect, useMemo, useState } from "react";
import { motion } from "motion/react";
import { api, type BusSnapshot } from "../lib/api";
import { agoText } from "../lib/clock";
import { Icon } from "../components/Icon";

const TICK_MS = 30_000;

function etaText(line: BusSnapshot["lines"][number]): string {
  if (line.eta_min === null) return "—";
  if (line.eta_min <= 0) return "即将到站";
  return `${line.eta_min} 分`;
}

function soonness(min: number | null): "now" | "soon" | "far" {
  if (min === null) return "far";
  if (min <= 1) return "now";
  if (min <= 8) return "soon";
  return "far";
}

export function BusBoard() {
  const [data, setData] = useState<BusSnapshot | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const next = await api.bus();
        if (alive) {
          setData(next);
          setError(false);
        }
      } catch {
        if (alive) setError(true);
      }
    };
    void load();
    const timer = window.setInterval(load, TICK_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, []);

  const updated = useMemo(() => {
    if (!data) return "";
    const at = Date.parse(data.updated_at);
    return Number.isNaN(at) ? "" : agoText(at);
  }, [data]);

  if (error && !data) {
    return (
      <p className="bus__empty">
        <Icon name="info" size={16} /> 公交到站暂时取不到，稍后自动重试。
      </p>
    );
  }

  if (!data) {
    return (
      <div className="bus__skeleton" aria-hidden="true">
        {[0, 1, 2].map((i) => (
          <span key={i} className="bus__skel-row" />
        ))}
      </div>
    );
  }

  return (
    <div className="bus">
      <div className="bus__head">
        <span className={`bus__badge${data.live ? " is-live" : ""}`}>
          {data.live ? `实时 · ${data.live_count} 条` : "按班次推算"}
        </span>
        <span className="bus__meta">
          {data.stop}
          {updated ? ` · ${updated}` : ""}
        </span>
      </div>

      <ul className="bus__list">
        {data.lines.map((line, i) => {
          const tone = soonness(line.eta_min);
          return (
            <motion.li
              key={line.name}
              className={`bus__row is-${tone}`}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ type: "spring", stiffness: 260, damping: 22, delay: i * 0.04 }}
              whileTap={{ scale: 0.985 }}
            >
              <span className="bus__no num">{line.name}</span>
              <span className="bus__route">
                <b>
                  {line.from} → {line.to}
                </b>
                <small>
                  首 {line.first} · 末 {line.last} · 约 {line.headway} 分钟一班
                </small>
              </span>
              <span className="bus__eta">
                <b className="num">{etaText(line)}</b>
                <small>{line.next_min !== null ? `下一班 ${line.next_min} 分` : line.note}</small>
              </span>
            </motion.li>
          );
        })}
      </ul>

      <p className="bus__note">
        <Icon name="info" size={14} /> {data.note}
      </p>

      <div className="bus__sources">
        <p className="bus__sources-title">数据来源 · 以官方为准</p>
        <ul>
          {data.sources.map((s) => (
            <li key={s.url}>
              <a href={s.url} target="_blank" rel="noreferrer noopener">
                <b>{s.name}</b>
                <small>{s.note}</small>
                <Icon name="external" size={13} />
              </a>
            </li>
          ))}
        </ul>
        <a className="bus__official" href={data.official} target="_blank" rel="noreferrer noopener">
          打开学校官网
          <Icon name="external" size={13} />
        </a>
      </div>
    </div>
  );
}
