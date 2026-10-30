/**
 * One data store for every screen.
 *
 * Modes, always visible in the UI:
 *   preview — not signed in; a real timetable snapshot + labelled samples
 *   live    — read from the server in this session
 *   cached  — signed in, but the server could not be reached; the last copy
 *             this device saw for this user (or nothing yet)
 *
 * Freshness: on open, and whenever the App comes back to the foreground, the
 * store asks the server to sync if any connected source is older than 30
 * minutes. The server applies its own per-source cooldown on top.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api, ApiError, getToken, type Me, type StatusSource } from "./api";
import { nowMs } from "./clock";
import { autoRelogin } from "./school";
import { PREVIEW_EXAMS, PREVIEW_GRADES, PREVIEW_TIMETABLE, previewNotices, previewTasks } from "./preview";
import {
  normalizeExam,
  normalizeGrade,
  normalizeNotice,
  normalizeTask,
  type Exam,
  type Grade,
  type Notice,
  type Task,
} from "./records";
import { normalizeTimetable, type TimetableDoc } from "./timetable";

export type Mode = "preview" | "live" | "cached";
export type RefreshHow = "read" | "sync" | "force";

interface Raw {
  timetable: unknown;
  tasks: unknown[];
  exams: unknown[];
  grades: unknown[];
  notices: { item: unknown; seen: number | null }[];
  syncedAt: number | null;
}

export interface Store {
  mode: Mode;
  /** the screens show labelled sample data (not signed in, or no campus account yet) */
  sample: boolean;
  timetable: TimetableDoc | null;
  tasks: Task[];
  exams: Exam[];
  grades: Grade[];
  notices: Notice[];
  sources: StatusSource[];
  me: Me | null;
  /** the campus account is connected (jw or mystu); null while unknown or not signed in */
  connected: boolean | null;
  /** connected sources whose school session has expired */
  needsReconnect: string[];
  /** epoch ms of the last successful read from the server */
  syncedAt: number | null;
  loading: boolean;
  syncing: boolean;
  error: string | null;
  /** resolves true when the data was read (and the sync, if asked for, started fine) */
  refresh: (how?: RefreshHow) => Promise<boolean>;
  done: Set<string>;
  toggleDone: (id: string) => void;
}

export const PREVIEW_KEY = "stu.preview";
const STALE_MS = 30 * 60_000;
/** 会自己过期的来源：会话掉了可以用本机密码续回来 */
const EXPIRABLE = new Set(["jw", "mystu"]);
/** stop following a sync after this long; the data shown so far stays */
const SYNC_WATCH_MS = 4 * 60_000;
const sleep = (ms: number) => new Promise((r) => window.setTimeout(r, ms));

export function isPreview(): boolean {
  try {
    // 有 token 就是真用户，不看 URL。原来 URL 上的 ?preview=1 优先级最高，
    // 于是"从演示链接点登录、登录成功了"仍然被当成演示，refresh 直接退出、
    // 界面停在样例数据上（审查报告第 32 项）。
    if (getToken()) return false;
    if (new URLSearchParams(location.search).has("preview")) return true;
    if (sessionStorage.getItem(PREVIEW_KEY) === "1") return true;
  } catch {
    /* ignore */
  }
  return !getToken();
}

/** The signed-in user's id, read from the token payload (no verification needed here). */
function subject(): string {
  const token = getToken();
  if (!token) return "anon";
  try {
    const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))) as { sub?: string };
    return String(payload.sub || "anon").replace(/[^\w-]/g, "").slice(0, 64) || "anon";
  } catch {
    return "anon";
  }
}

const cacheKey = (who: string) => `stu.data.v3:${who}`;
const doneKey = (who: string) => `stu.done.v2:${who}`;

/** Remove everything this device remembers about the signed-in user. */
export function forgetUser(): void {
  const who = subject();
  try {
    localStorage.removeItem(cacheKey(who));
    localStorage.removeItem(doneKey(who));
    // 「现在是谁」也要清。留着的话，下一个人登录前，本机校园凭据的分区键
    // 还指着上一个人的账号。
    localStorage.removeItem("stu.account");
  } catch {
    /* ignore */
  }
}

function readJSON<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeJSON(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* quota or private mode */
  }
}

const EMPTY: Raw = { timetable: null, tasks: [], exams: [], grades: [], notices: [], syncedAt: null };

function previewRaw(): Raw {
  return {
    timetable: PREVIEW_TIMETABLE,
    tasks: previewTasks(),
    exams: PREVIEW_EXAMS,
    grades: PREVIEW_GRADES,
    notices: previewNotices().map((item) => ({ item, seen: null })),
    syncedAt: null,
  };
}

const Ctx = createContext<Store | null>(null);

export function StoreProvider({ children }: { children: ReactNode }) {
  const preview = useMemo(isPreview, []);
  const who = useMemo(subject, []);
  const [raw, setRaw] = useState<Raw>(() => (preview ? previewRaw() : readJSON<Raw>(cacheKey(who)) ?? EMPTY));
  const [mode, setMode] = useState<Mode>(preview ? "preview" : "cached");
  const [sources, setSources] = useState<StatusSource[]>([]);
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<Set<string>>(() => new Set(readJSON<string[]>(doneKey(who)) ?? []));
  const lastSyncTry = useRef(0);
  const busy = useRef(false);

  /** Read everything the server has for us. Returns status + me for the freshness check. */
  const read = useCallback(async () => {
    const [tt, feed, exams, grades, notices, status, meRes] = await Promise.allSettled([
      api.timetable(),
      api.feed("task,homework", 120),
      api.exams(),
      api.grades(),
      api.notices(60),
      api.status(),
      api.me(),
    ]);
    const ok = <T,>(r: PromiseSettledResult<T>) => (r.status === "fulfilled" ? r.value : null);
    const data = [tt, feed, exams, grades, notices];
    if (data.every((r) => r.status === "rejected")) throw (tt as PromiseRejectedResult).reason;

    const prev = readJSON<Raw>(cacheKey(who)) ?? EMPTY;
    const ttv = ok(tt) as { empty?: boolean } | null;
    const next: Raw = {
      timetable: ttv ? (ttv.empty ? null : ttv) : prev.timetable,
      tasks: ok(feed)?.items.map((i) => ({ ...i.detail, id: String(i.id), title: i.title, source: i.source })) ?? prev.tasks,
      exams: ok(exams)?.rows ?? prev.exams,
      grades: ok(grades)?.rows ?? prev.grades,
      notices:
        ok(notices)?.items.map((i) => ({
          item: { ...i.detail, id: String(i.id), title: i.title, source: i.source },
          seen: i.updated_at * 1000,
        })) ?? prev.notices,
      syncedAt: nowMs(),
    };
    writeJSON(cacheKey(who), next);
    setRaw(next);
    setMode("live");
    const st = ok(status)?.sources ?? null;
    const m = ok(meRes);
    if (st) setSources(st);
    if (m) setMe(m);
    return { st, m, hasTimetable: Boolean(next.timetable) };
  }, [who]);

  /**
   * Follow a background sync on the server. Each time a source finishes, the
   * screens are re-read, so the timetable shows up before MySTU is done.
   */
  const follow = useCallback(async () => {
    const started = nowMs();
    const seen = new Map<string, string>();
    while (nowMs() - started < SYNC_WATCH_MS) {
      await sleep(2_000);
      const st = await api.status();
      setSources(st.sources);
      const changed = st.sources.some((s) => {
        const mark = `${s.code}|${s.at}`;
        const before = seen.get(s.source);
        seen.set(s.source, mark);
        return before !== undefined && before !== mark && s.code !== -1;
      });
      if (changed) await read();
      if (!st.syncing) return;
    }
  }, [read]);

  const refresh = useCallback(
    async (how: RefreshHow = "read"): Promise<boolean> => {
      if (preview || busy.current) return false;
      busy.current = true;
      setError(null);
      let syncError: unknown = null;
      try {
        if (how !== "read") {
          lastSyncTry.current = nowMs();
          setSyncing(true);
          try {
            const res = await api.sync(how === "force");
            if (!res.demo) await follow();
            // 教务会话过期时，用本机保存的学校密码自动续一次，再同步一遍；
            // 没存密码或学校要人工验证，就保持原样提示"重新连接"。
            const expired = (await api.status())
              .sources.filter((s) => s.code === 3 && EXPIRABLE.has(s.source))
              .map((s) => s.source);
            if (expired.length > 0 && (await autoRelogin(expired))) {
              await api.sync(true);
              await follow();
            }
          } catch (exc) {
            syncError = exc;
          } finally {
            setSyncing(false);
          }
        }
        setLoading(true);
        await read();
        if (syncError) setError(syncError instanceof ApiError ? syncError.code : "sync_failed");
        return !syncError;
      } catch (exc) {
        setError(exc instanceof ApiError ? exc.code : "network");
        setMode("cached");
        return false;
      } finally {
        setLoading(false);
        busy.current = false;
      }
    },
    [preview, read, follow],
  );

  /** Sync when a connected source has not been refreshed for a while. */
  const syncIfStale = useCallback(
    async (st: StatusSource[] | null, m: Me | null, hasTimetable: boolean) => {
      if (preview || !st || !m) return;
      const watched = new Set(["public", ...m.bound.filter((s) => s === "jw" || s === "mystu")]);
      const stale =
        (m.bound.includes("jw") && !hasTimetable) ||
        st.some((s) => watched.has(s.source) && (s.at === null || nowMs() - s.at * 1000 > STALE_MS));
      if (stale) await refresh("sync");
    },
    [preview, refresh],
  );

  useEffect(() => {
    if (preview) return;
    let alive = true;
    (async () => {
      busy.current = true;
      setLoading(true);
      try {
        const { st, m, hasTimetable } = await read();
        busy.current = false;
        if (alive) await syncIfStale(st, m, hasTimetable);
      } catch (exc) {
        if (alive) {
          setError(exc instanceof ApiError ? exc.code : "network");
          setMode("cached");
        }
      } finally {
        busy.current = false;
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [preview, read, syncIfStale]);

  // back in the foreground after a while: bring the data up to date
  useEffect(() => {
    if (preview) return;
    const onVis = () => {
      if (document.visibilityState !== "visible") return;
      if (nowMs() - lastSyncTry.current < STALE_MS) return;
      void refresh(raw.syncedAt && nowMs() - raw.syncedAt < STALE_MS ? "read" : "sync");
    };
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, [preview, refresh, raw.syncedAt]);

  const toggleDone = useCallback(
    (id: string) => {
      setDone((prev) => {
        const next = new Set(prev);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        writeJSON(doneKey(who), [...next]);
        return next;
      });
    },
    [who],
  );

  const sampleRaw = useMemo(previewRaw, []);

  const value = useMemo<Store>(() => {
    const bound = me?.bound ?? [];
    /*
     * 这里原来只认 jw / mystu 为"已连接"，于是**只连了医学院、或只手动导入
     * 课表**的账号被判成未绑定，整页被换成样例数据——真课表被演示课表盖掉
     * （审查报告第 13 项）。医学院走的是 med_sessions，手动课表走
     * manual_timetables，两者都不在 me.bound 里。
     *
     * 现在的判断分两层：
     *   1. 真的连了主校区 → 全量真实数据；
     *   2. 没连主校区，但**手里已经有真课表**（医学院同步来的或手动录的）
     *      → 课表算真实，作业/成绩这些仍然没有，但不再拿样例去顶课表。
     * 只有"既没绑定、也没有任何真课表"才整页用样例。
     */
    const campusBound = bound.includes("jw") || bound.includes("mystu");
    const rawDoc = normalizeTimetable(raw.timetable);
    const hasOwnTimetable = Boolean(rawDoc && rawDoc.courses.length > 0);
    const connected = preview ? null : me ? campusBound || hasOwnTimetable : null;
    const sample = preview || connected === false;
    const src = sample ? { ...sampleRaw, notices: raw.notices.length ? raw.notices : sampleRaw.notices } : raw;
    // 有真课表就别让样例课表盖它（即使作业/成绩还在样例状态）
    const timetableSrc = hasOwnTimetable ? raw.timetable : src.timetable;
    return {
      mode,
      sample,
      timetable: normalizeTimetable(timetableSrc),
      tasks: src.tasks.map(normalizeTask),
      exams: src.exams.map(normalizeExam),
      grades: src.grades.map(normalizeGrade),
      notices: src.notices
        .map(({ item, seen }) => normalizeNotice(item, seen))
        .sort((a, b) => (b.publishedMs ?? 0) - (a.publishedMs ?? 0)),
      sources,
      me,
      connected,
      needsReconnect: sources.filter((s) => s.state === "need_login" && bound.includes(s.source)).map((s) => s.source),
      syncedAt: raw.syncedAt,
      loading,
      syncing,
      error,
      refresh,
      done,
      toggleDone,
    };
  }, [raw, sampleRaw, mode, sources, me, preview, loading, syncing, error, refresh, done, toggleDone]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useStore(): Store {
  const v = useContext(Ctx);
  if (!v) throw new Error("useStore outside StoreProvider");
  return v;
}

/* --------------------------------------------------------------- shell */

/** Navigation actions any screen may trigger. */
export interface ShellActions {
  // 与 TabBar 的 TabKey 对齐；import 是课表页的子页面，不是底栏一格。
  /** 跳页；campus 可以带一个子视图，比如从首页"查看校历"直接落到校历那一栏 */
  go: (tab: "today" | "plan" | "week" | "study" | "campus" | "me" | "import" | "medbind",
       sub?: string) => void;
  connect: () => void;
}

export const ShellCtx = createContext<ShellActions>({ go: () => undefined, connect: () => undefined });

export function useShell(): ShellActions {
  return useContext(ShellCtx);
}
