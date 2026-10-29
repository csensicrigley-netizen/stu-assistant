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
"""


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
        """老库平滑升级：只加列，不改名不删列。"""

        cols = {row["name"] for row in c.execute("PRAGMA table_info(devices)")}
        for name, ddl in (("app_version", "TEXT"), ("app_code", "INTEGER"), ("seen_at", "REAL")):
            if name not in cols:
                c.execute(f"ALTER TABLE devices ADD COLUMN {name} {ddl}")

    @contextmanager
    def conn(self) -> Iterator[sqlite3.Connection]:
        c = sqlite3.connect(self.path, timeout=30)
        c.row_factory = sqlite3.Row
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
