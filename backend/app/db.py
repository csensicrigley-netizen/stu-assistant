"""SQLite storage. Zero external deps; swap to Postgres by changing the driver only."""
from __future__ import annotations

import json
import sqlite3
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS users (
  id            TEXT PRIMARY KEY,
  email         TEXT UNIQUE NOT NULL,
  pass_hash     TEXT NOT NULL,
  student_id_h  TEXT,
  status        TEXT NOT NULL DEFAULT 'active',
  created_at    REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_users_sid ON users(student_id_h);

CREATE TABLE IF NOT EXISTS items (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source        TEXT NOT NULL,
  kind          TEXT NOT NULL,
  external_id   TEXT NOT NULL,
  title         TEXT,
  payload       TEXT NOT NULL,
  first_seen    REAL NOT NULL,
  updated_at    REAL NOT NULL,
  UNIQUE (user_id, source, kind, external_id)
);
CREATE INDEX IF NOT EXISTS idx_items_user_kind ON items(user_id, kind, updated_at DESC);

CREATE TABLE IF NOT EXISTS sync_runs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     TEXT NOT NULL,
  source      TEXT NOT NULL,
  code        INTEGER NOT NULL,
  detail      TEXT,
  items_seen  INTEGER NOT NULL DEFAULT 0,
  started_at  REAL NOT NULL,
  ended_at    REAL
);
CREATE INDEX IF NOT EXISTS idx_runs_user ON sync_runs(user_id, started_at DESC);

CREATE TABLE IF NOT EXISTS devices (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  platform    TEXT NOT NULL,
  token       TEXT NOT NULL,
  created_at  REAL NOT NULL,
  app_version TEXT,
  app_code    INTEGER,
  seen_at     REAL,
  UNIQUE (user_id, token)
);

CREATE TABLE IF NOT EXISTS push_log (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id  TEXT NOT NULL,
  kind     TEXT NOT NULL,
  payload  TEXT NOT NULL,
  sent_at  REAL NOT NULL,
  ok       INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS credentials_meta (
  user_id    TEXT NOT NULL,
  service    TEXT NOT NULL,
  bound_at   REAL NOT NULL,
  account_h  TEXT,
  PRIMARY KEY (user_id, service)
);

-- 本人授权留痕：没有这一行，就不读 OA。
-- scope 目前只有 'oa'；at 是本人点「同意」的时刻，改版也不许偷偷补写。
CREATE TABLE IF NOT EXISTS consents (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  scope    TEXT NOT NULL,
  at       REAL NOT NULL,
  version  TEXT NOT NULL DEFAULT '1',
  PRIMARY KEY (user_id, scope)
);

-- 手动录入的课表（医学院等教务系统无法自动同步的院系）。
-- payload 存整份课表文档，形状与 sync._flatten_timetable() 一致；
-- 同一用户同一学期只有一份，再保存就是覆盖。
CREATE TABLE IF NOT EXISTS manual_timetables (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  semester   TEXT NOT NULL,
  school     TEXT NOT NULL DEFAULT 'main',
  source     TEXT NOT NULL DEFAULT 'manual',
  payload    TEXT NOT NULL,
  note       TEXT NOT NULL DEFAULT '',
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL,
  UNIQUE (user_id, semester)
);

-- 医学院教务会话：只存会话 token，不存密码。
-- 医学院那套是 JSON API + token 头（强智新版教学服务平台），token 由用户
-- 自己输账号密码换出来，换完就只留 token；密码一个字都不落库。
-- token 失效时接口会回 401，前端让用户重新输一次即可。
CREATE TABLE IF NOT EXISTS med_sessions (
  user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  username   TEXT NOT NULL DEFAULT '',
  name       TEXT NOT NULL DEFAULT '',
  token      TEXT NOT NULL,
  updated_at REAL NOT NULL
);

-- 每日计划：本人自己写的待办，和 items 里学校推下来的 task 是两回事。
-- end 是 SQL 关键字，必须加双引号。老库靠 IF NOT EXISTS 平滑升级。
CREATE TABLE IF NOT EXISTS plans (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  date       TEXT NOT NULL,
  title      TEXT NOT NULL,
  note       TEXT NOT NULL DEFAULT '',
  start      TEXT,
  "end"      TEXT,
  done       INTEGER NOT NULL DEFAULT 0,
  priority   INTEGER NOT NULL DEFAULT 0,
  sort       INTEGER NOT NULL DEFAULT 0,
  created_at REAL NOT NULL,
  updated_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_plans_user_date ON plans(user_id, date, sort);
"""

# Keep account deletion and the one-time legacy orphan cleanup in sync.
USER_TABLES = (
    "items", "devices", "consents", "manual_timetables", "med_sessions", "plans",
    "credentials_meta", "sync_runs", "push_log",
)


def now() -> float:
    return time.time()


class DB:
    def __init__(self, path: Path | str):
        self.path = str(path)
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with self.conn() as c:
            c.executescript(SCHEMA)
            self._migrate(c)

    @staticmethod
    def _migrate(c: sqlite3.Connection) -> None:
        """保留有效用户的数据，并在事务内清理旧版本留下的孤儿记录。"""

        cols = {row["name"] for row in c.execute("PRAGMA table_info(devices)")}
        for name, ddl in (("app_version", "TEXT"), ("app_code", "INTEGER"), ("seen_at", "REAL")):
            if name not in cols:
                c.execute(f"ALTER TABLE devices ADD COLUMN {name} {ddl}")
        if c.execute("PRAGMA user_version").fetchone()[0] < 1:
            for table in USER_TABLES:
                c.execute(f"DELETE FROM {table} WHERE NOT EXISTS "
                          f"(SELECT 1 FROM users WHERE users.id = {table}.user_id)")
            c.execute("PRAGMA user_version=1")

    @contextmanager
    def conn(self) -> Iterator[sqlite3.Connection]:
        c = sqlite3.connect(self.path, timeout=30)
        c.row_factory = sqlite3.Row
        # 外键开关按连接生效；不能只在建表连接上开启。
        c.execute("PRAGMA foreign_keys=ON")
        try:
            yield c
            c.commit()
        except Exception:
            c.rollback()
            raise
        finally:
            c.close()

    # ---------- users ----------

    def create_user(self, email: str, pass_hash: str, student_id_h: str | None = None) -> dict:
        uid = str(uuid.uuid4())
        with self.conn() as c:
            c.execute(
                "INSERT INTO users(id,email,pass_hash,student_id_h,created_at) VALUES(?,?,?,?,?)",
                (uid, email.lower().strip(), pass_hash, student_id_h, now()),
            )
        return {"id": uid, "email": email.lower().strip()}

    def user_by_email(self, email: str) -> dict | None:
        with self.conn() as c:
            row = c.execute("SELECT * FROM users WHERE email=?", (email.lower().strip(),)).fetchone()
        return dict(row) if row else None

    def user_by_id(self, uid: str) -> dict | None:
        with self.conn() as c:
            row = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        return dict(row) if row else None

    def set_student_id_hash(self, uid: str, sid_h: str) -> None:
        with self.conn() as c:
            c.execute("UPDATE users SET student_id_h=? WHERE id=?", (sid_h, uid))

    def delete_user(self, uid: str) -> None:
        with self.conn() as c:
            # 老库的三张日志/凭据元数据表没有外键，不能只依赖 CASCADE。
            for table in USER_TABLES:
                c.execute(f"DELETE FROM {table} WHERE user_id=?", (uid,))
            c.execute("DELETE FROM users WHERE id=?", (uid,))

    # ---------- items ----------

    def upsert_items(self, uid: str, source: str, kind: str, records: list[dict]) -> list[dict]:
        """Idempotent by (user, source, kind, external_id). Returns only NEW rows."""
        ts, fresh = now(), []
        with self.conn() as c:
            for rec in records:
                ext = str(rec.get("external_id") or rec.get("id") or "")
                if not ext:
                    continue
                title = rec.get("title")
                payload = json.dumps(rec, ensure_ascii=False)
                cur = c.execute(
                    "SELECT id FROM items WHERE user_id=? AND source=? AND kind=? AND external_id=?",
                    (uid, source, kind, ext),
                ).fetchone()
                if cur:
                    c.execute(
                        "UPDATE items SET title=?, payload=?, updated_at=? WHERE id=?",
                        (title, payload, ts, cur["id"]),
                    )
                else:
                    c.execute(
                        "INSERT INTO items(user_id,source,kind,external_id,title,payload,"
                        "first_seen,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                        (uid, source, kind, ext, title, payload, ts, ts),
                    )
                    fresh.append(rec)
        return fresh

    def feed(self, uid: str, kinds: list[str] | None = None, since: float | None = None,
             limit: int = 100) -> list[dict]:
        q = "SELECT * FROM items WHERE user_id=?"
        args: list[Any] = [uid]
        if kinds:
            q += " AND kind IN (%s)" % ",".join("?" * len(kinds))
            args += kinds
        if since:
            q += " AND updated_at>=?"
            args.append(since)
        q += " ORDER BY updated_at DESC LIMIT ?"
        args.append(limit)
        with self.conn() as c:
            rows = c.execute(q, args).fetchall()
        return [{**dict(r), "payload": json.loads(r["payload"])} for r in rows]

    def items_of(self, uid: str, kind: str, source: str | None = None, limit: int = 200) -> list[dict]:
        q = "SELECT * FROM items WHERE user_id=? AND kind=?"
        args: list[Any] = [uid, kind]
        if source:
            q += " AND source=?"
            args.append(source)
        q += " ORDER BY updated_at DESC LIMIT ?"
        args.append(limit)
        with self.conn() as c:
            rows = c.execute(q, args).fetchall()
        return [{**dict(r), "payload": json.loads(r["payload"])} for r in rows]

    def replace_kind(self, uid: str, source: str, kind: str, records: list[dict]) -> int:
        """For snapshot-shaped data (timetable), drop stale rows of the same kind first."""
        ts = now()
        with self.conn() as c:
            seen = set()
            for rec in records:
                ext = str(rec.get("external_id") or "")
                if not ext:
                    continue
                seen.add(ext)
                c.execute(
                    "INSERT INTO items(user_id,source,kind,external_id,title,payload,first_seen,updated_at)"
                    " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,source,kind,external_id)"
                    " DO UPDATE SET title=excluded.title,payload=excluded.payload,updated_at=excluded.updated_at",
                    (uid, source, kind, ext, rec.get("title"),
                     json.dumps(rec, ensure_ascii=False), ts, ts),
                )
            if seen:
                q = ("DELETE FROM items WHERE user_id=? AND source=? AND kind=?"
                     " AND external_id NOT IN (%s)" % ",".join("?" * len(seen)))
                c.execute(q, [uid, source, kind, *seen])
        return len(seen)

    # ---------- sync ----------

    def start_run(self, uid: str, source: str) -> int:
        with self.conn() as c:
            cur = c.execute(
                "INSERT INTO sync_runs(user_id,source,code,started_at) VALUES(?,?,?,?)",
                (uid, source, -1, now()),
            )
            return int(cur.lastrowid)

    def finish_run(self, run_id: int, code: int, detail: str = "", items_seen: int = 0) -> None:
        with self.conn() as c:
            c.execute(
                "UPDATE sync_runs SET code=?, detail=?, items_seen=?, ended_at=? WHERE id=?",
                (code, detail[:500], items_seen, now(), run_id),
            )

    def last_run(self, uid: str, source: str) -> dict | None:
        with self.conn() as c:
            row = c.execute(
                "SELECT * FROM sync_runs WHERE user_id=? AND source=? ORDER BY started_at DESC LIMIT 1",
                (uid, source),
            ).fetchone()
        return dict(row) if row else None

    def recent_logins(self, uid: str, window: int = 3600) -> int:
        cutoff = now() - window
        with self.conn() as c:
            row = c.execute(
                "SELECT COUNT(*) AS n FROM sync_runs WHERE user_id=? AND code=3 AND started_at>=?",
                (uid, cutoff),
            ).fetchone()
        return int(row["n"]) if row else 0

    # ---------- bind meta ----------

    def mark_bound(self, uid: str, service: str, account_h: str | None) -> None:
        with self.conn() as c:
            c.execute(
                "INSERT INTO credentials_meta(user_id,service,bound_at,account_h) VALUES(?,?,?,?)"
                " ON CONFLICT(user_id,service) DO UPDATE SET bound_at=excluded.bound_at,"
                " account_h=excluded.account_h",
                (uid, service, now(), account_h),
            )

    def bound_services(self, uid: str) -> list[dict]:
        with self.conn() as c:
            rows = c.execute(
                "SELECT service,bound_at,account_h FROM credentials_meta WHERE user_id=?", (uid,)
            ).fetchall()
        return [dict(r) for r in rows]

    def clear_bind_meta(self, uid: str) -> None:
        with self.conn() as c:
            c.execute("DELETE FROM credentials_meta WHERE user_id=?", (uid,))

    # ---------- devices ----------

    def add_device(self, uid: str, platform: str, token: str,
                   app_version: str | None = None, app_code: int | None = None) -> None:
        with self.conn() as c:
            c.execute(
                "INSERT INTO devices(user_id,platform,token,created_at,app_version,app_code,seen_at)"
                " VALUES(?,?,?,?,?,?,?)"
                " ON CONFLICT(user_id,token) DO UPDATE SET"
                "   platform=excluded.platform,"
                "   app_version=COALESCE(excluded.app_version, devices.app_version),"
                "   app_code=COALESCE(excluded.app_code, devices.app_code),"
                "   seen_at=excluded.seen_at",
                (uid, platform, token, now(), app_version, app_code, now()),
            )

    def devices_of(self, uid: str) -> list[dict]:
        with self.conn() as c:
            rows = c.execute("SELECT * FROM devices WHERE user_id=?", (uid,)).fetchall()
        return [dict(r) for r in rows]

    def all_devices(self) -> list[dict]:
        """版本通道用：全部设备（含所属用户与装机版本）。"""
        with self.conn() as c:
            rows = c.execute("SELECT * FROM devices").fetchall()
        return [dict(r) for r in rows]

    def outdated_devices(self, latest_code: int) -> list[dict]:
        """装机版本低于最新版本的设备；没上报过版本的也算旧。"""
        with self.conn() as c:
            rows = c.execute(
                "SELECT * FROM devices WHERE app_code IS NULL OR app_code < ?", (latest_code,)
            ).fetchall()
        return [dict(r) for r in rows]

    def remove_device(self, uid: str, token: str) -> None:
        with self.conn() as c:
            c.execute("DELETE FROM devices WHERE user_id=? AND token=?", (uid, token))

    # ---------- 本人授权留痕 ----------
    #
    # 没有 consents 里那行，就不读 OA。授权是「本人点同意」这件事的凭据，
    # 只记录 scope + 时间 + 文案版本，不记录任何生物特征、IP 或设备指纹。

    def grant_consent(self, uid: str, scope: str = "oa", version: str = "1") -> dict:
        at = now()
        with self.conn() as c:
            c.execute(
                "INSERT INTO consents(user_id,scope,at,version) VALUES(?,?,?,?)"
                " ON CONFLICT(user_id,scope) DO UPDATE SET at=excluded.at, version=excluded.version",
                (uid, scope, at, version),
            )
        return {"scope": scope, "at": at, "version": version}

    def has_consent(self, uid: str, scope: str = "oa") -> bool:
        with self.conn() as c:
            row = c.execute(
                "SELECT 1 FROM consents WHERE user_id=? AND scope=?", (uid, scope)
            ).fetchone()
        return row is not None

    def consents_of(self, uid: str) -> list[dict]:
        with self.conn() as c:
            rows = c.execute(
                "SELECT scope,at,version FROM consents WHERE user_id=?", (uid,)
            ).fetchall()
        return [dict(r) for r in rows]

    def withdraw_consent(self, uid: str, scope: str = "oa") -> None:
        with self.conn() as c:
            c.execute("DELETE FROM consents WHERE user_id=? AND scope=?", (uid, scope))

    # ---------- 手动录入的课表 ----------
    #
    # 医学院的教务系统和主校区不一样，没法自动同步，只能自己录。
    # 这里只管存取；解析和校验在 timetable_import.py，不混进来。

    def tt_manual_get(self, uid: str, semester: str) -> dict | None:
        with self.conn() as c:
            row = c.execute(
                "SELECT * FROM manual_timetables WHERE user_id=? AND semester=?",
                (uid, semester),
            ).fetchone()
        if not row:
            return None
        out = dict(row)
        out["doc"] = json.loads(out.pop("payload"))
        return out

    def tt_manual_list(self, uid: str) -> list[dict]:
        with self.conn() as c:
            rows = c.execute(
                "SELECT * FROM manual_timetables WHERE user_id=? ORDER BY semester DESC",
                (uid,),
            ).fetchall()
        out: list[dict] = []
        for row in rows:
            item = dict(row)
            item["doc"] = json.loads(item.pop("payload"))
            out.append(item)
        return out

    def tt_manual_save(self, uid: str, semester: str, payload: dict, *,
                       school: str = "main", source: str = "manual", note: str = "") -> dict:
        """同一用户同一学期只有一份：再保存就是覆盖，created_at 保持第一次的。"""
        ts = now()
        body = json.dumps(payload, ensure_ascii=False)
        with self.conn() as c:
            cur = c.execute(
                "SELECT id, created_at FROM manual_timetables WHERE user_id=? AND semester=?",
                (uid, semester),
            ).fetchone()
            if cur:
                tid = cur["id"]
                created = cur["created_at"]
                c.execute(
                    "UPDATE manual_timetables SET school=?, source=?, payload=?, note=?, updated_at=?"
                    " WHERE id=?",
                    (school, source, body, note, ts, tid),
                )
            else:
                tid = "ttman_" + uuid.uuid4().hex[:16]
                created = ts
                c.execute(
                    "INSERT INTO manual_timetables(id,user_id,semester,school,source,payload,note,"
                    "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    (tid, uid, semester, school, source, body, note, ts, ts),
                )
        return {
            "id": tid,
            "user_id": uid,
            "semester": semester,
            "school": school,
            "source": source,
            "doc": payload,
            "note": note,
            "created_at": created,
            "updated_at": ts,
        }

    def tt_manual_delete(self, uid: str, semester: str) -> bool:
        with self.conn() as c:
            cur = c.execute(
                "DELETE FROM manual_timetables WHERE user_id=? AND semester=?",
                (uid, semester),
            )
            return cur.rowcount > 0

    # ---------- 医学院教务会话（只有 token，没有密码） ----------

    def med_session_get(self, uid: str) -> dict | None:
        with self.conn() as c:
            row = c.execute(
                "SELECT * FROM med_sessions WHERE user_id=?", (uid,)
            ).fetchone()
        if row is None:
            return None
        return {
            "username": row["username"],
            "name": row["name"],
            "token": row["token"],
            "updated_at": row["updated_at"],
        }

    def med_session_save(self, uid: str, *, username: str, name: str, token: str) -> None:
        ts = now()
        with self.conn() as c:
            c.execute(
                "INSERT INTO med_sessions(user_id, username, name, token, updated_at) "
                "VALUES(?,?,?,?,?) "
                "ON CONFLICT(user_id) DO UPDATE SET username=excluded.username, "
                "name=excluded.name, token=excluded.token, updated_at=excluded.updated_at",
                (uid, username, name, token, ts),
            )

    def med_session_delete(self, uid: str) -> bool:
        with self.conn() as c:
            cur = c.execute("DELETE FROM med_sessions WHERE user_id=?", (uid,))
            return cur.rowcount > 0

    # ---------- 每日计划（本人自己写的待办） ----------
    #
    # 全部方法都带 uid：租户隔离靠 WHERE user_id=?，没有一处按 id 单查。
    # 字段校验在 plan.normalize_fields 里；这里只做「跨行才看得见」的那部分
    # （新建时排到当天末尾、改一头时拿合并后的整行复查时间段）。

    @staticmethod
    def _plan_row(row: sqlite3.Row) -> dict:
        return {
            "id": row["id"],
            "date": row["date"],
            "title": row["title"],
            "note": row["note"] or "",
            "start": row["start"],
            "end": row["end"],
            "done": bool(row["done"]),
            "priority": int(row["priority"]),
            "sort": int(row["sort"]),
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    def plan_list(self, uid: str, *, date: str | None = None, start: str | None = None,
                  end: str | None = None, include_done: bool = True) -> list[dict]:
        """按 (date, sort, created_at) 排序；start/end 是 YYYY-MM-DD 闭区间。"""
        q = "SELECT * FROM plans WHERE user_id=?"
        args: list[Any] = [uid]
        if date:
            q += " AND date=?"
            args.append(date)
        if start:
            q += " AND date>=?"
            args.append(start)
        if end:
            q += " AND date<=?"
            args.append(end)
        if not include_done:
            q += " AND done=0"
        q += " ORDER BY date ASC, sort ASC, created_at ASC"
        with self.conn() as c:
            rows = c.execute(q, args).fetchall()
        return [self._plan_row(r) for r in rows]

    def plan_get(self, uid: str, plan_id: str) -> dict | None:
        with self.conn() as c:
            row = c.execute(
                "SELECT * FROM plans WHERE user_id=? AND id=?", (uid, plan_id)
            ).fetchone()
        return self._plan_row(row) if row else None

    def plan_create(self, uid: str, fields: dict) -> dict:
        """新建。`sort` 没给（或给 0）就排到当天末尾——加一条总是落在最下面。"""
        from .plan import new_plan_id

        ts = now()
        pid = str(fields.get("id") or new_plan_id())
        day = str(fields["date"])
        with self.conn() as c:
            sort = int(fields.get("sort") or 0)
            if sort <= 0:
                row = c.execute(
                    "SELECT COALESCE(MAX(sort), -1) + 1 AS next FROM plans WHERE user_id=? AND date=?",
                    (uid, day),
                ).fetchone()
                sort = int(row["next"]) if row else 0
            c.execute(
                'INSERT INTO plans(id,user_id,date,title,note,start,"end",done,priority,sort,'
                "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    pid, uid, day, str(fields["title"]), str(fields.get("note") or ""),
                    fields.get("start"), fields.get("end"),
                    1 if fields.get("done") else 0,
                    int(fields.get("priority") or 0), sort, ts, ts,
                ),
            )
        return self.plan_get(uid, pid) or {}

    def plan_update(self, uid: str, plan_id: str, fields: dict) -> dict | None:
        """改一条。只写传进来的列；时间段按「合并后的整行」复查。"""
        current = self.plan_get(uid, plan_id)
        if current is None:
            return None

        merged = {**current, **fields}
        start = merged.get("start") or None
        end = merged.get("end") or None
        if (start is None) != (end is None):
            raise ValueError("开始和结束时间要么都填，要么都空")
        if start and end and str(end) <= str(start):
            raise ValueError("结束时间要晚于开始时间")

        # 列名 → 新值；`end` 是 SQL 关键字，写语句时统一加双引号。
        changes: dict[str, Any] = {}
        for column in ("date", "title", "note", "start", "end", "priority", "sort"):
            if column in fields:
                changes[column] = fields[column]
        # 时间段成对：清掉一头，另一头一起清，不留半截。
        if ("start" in changes and changes["start"] is None) or ("end" in changes and changes["end"] is None):
            changes["start"] = None
            changes["end"] = None
        if "done" in fields:
            changes["done"] = 1 if fields["done"] else 0
        if not changes:
            return current

        sets = [('"end"=?' if column == "end" else f"{column}=?") for column in changes]
        args: list[Any] = [*changes.values(), now(), uid, plan_id]
        with self.conn() as c:
            c.execute(f"UPDATE plans SET {', '.join(sets)}, updated_at=? WHERE user_id=? AND id=?", args)
        return self.plan_get(uid, plan_id)

    def plan_delete(self, uid: str, plan_id: str) -> bool:
        with self.conn() as c:
            cur = c.execute("DELETE FROM plans WHERE user_id=? AND id=?", (uid, plan_id))
            return cur.rowcount > 0

    def plan_toggle(self, uid: str, plan_id: str, done: bool) -> dict | None:
        with self.conn() as c:
            cur = c.execute(
                "UPDATE plans SET done=?, updated_at=? WHERE user_id=? AND id=?",
                (1 if done else 0, now(), uid, plan_id),
            )
            if cur.rowcount <= 0:
                return None
        return self.plan_get(uid, plan_id)

    def plan_reorder(self, uid: str, date: str, ordered_ids: list[str]) -> int:
        """按给定顺序重排某一天；不属于这一天的一律跳过，返回真正动过的条数。"""
        if not ordered_ids:
            return 0
        ts = now()
        moved = 0
        with self.conn() as c:
            owned = {
                row["id"]
                for row in c.execute(
                    "SELECT id FROM plans WHERE user_id=? AND date=?", (uid, date)
                ).fetchall()
            }
            for index, pid in enumerate(ordered_ids):
                if pid not in owned:
                    continue
                c.execute(
                    "UPDATE plans SET sort=?, updated_at=? WHERE user_id=? AND id=?",
                    (index, ts, uid, pid),
                )
                moved += 1
        return moved
