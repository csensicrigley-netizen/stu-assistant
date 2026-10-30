"""Sync orchestration: rate limit -> collect -> persist -> notify.

A sync runs on a background thread, one per user, so the request that starts
it returns at once and the App polls /v1/status. Sources run in order of
importance (timetable first) and each result is saved the moment it is in, so
the App can show the timetable before MySTU has finished.

Being a good neighbour to the school systems:
  * each source is refreshed at most every 10 minutes per user (1 minute when
    the user taps "sync now");
  * at most a few users talk to one school system at the same moment;
  * the public notice listings are shared by all users (collectors.py).
"""
from __future__ import annotations

import threading
import time

from .collectors import CODE_MAINTENANCE, CODE_NEED_INPUT, CODE_OK, collect
from .config import settings
from .db import DB
from .tenancy import tenant_scope

# Which collected kind is a snapshot (replace) vs a stream (upsert-new).
SNAPSHOT_KINDS = {"timetable"}

# 采集源 -> 它在租户 vault 里用的会话名。
# OA 通知挂在 public 源上，但它读的是 WebVPN 会话，所以重登时要认 "webvpn"。
SESSION_FOR = {"jw": "jw", "mystu": "mystu"}

ORDER = ("jw", "mystu", "public")
FORCE_MIN_INTERVAL = 60

_SLOTS = {
    "jw": threading.BoundedSemaphore(3),
    "mystu": threading.BoundedSemaphore(3),
    "public": threading.BoundedSemaphore(2),
}

_RUNNING: dict[str, threading.Thread] = {}
_RUNNING_GUARD = threading.Lock()


def _cooldown_ok(db: DB, uid: str, source: str) -> tuple[bool, float]:
    last = db.last_run(uid, source)
    elapsed = time.time() - (last["started_at"] if last else 0)
    return elapsed >= settings.sync_min_interval, max(0.0, settings.sync_min_interval - elapsed)


def sync_user(uid: str, sources: list[str] | None = None, *, force: bool = False) -> list[dict]:
    db = DB(settings.db_path)
    wanted = set(sources or ORDER)
    report: list[dict] = []

    for source in [s for s in ORDER if s in wanted]:
        allowed, wait = _cooldown_ok(db, uid, source)
        # "force" (the user tapped 立即同步) still waits one minute between
        # runs, so a stuck button cannot hammer the school systems
        if not allowed and (not force or wait > settings.sync_min_interval - FORCE_MIN_INTERVAL):
            report.append({"source": source, "code": -1, "skipped": True, "retry_after": round(wait)})
            continue

        bound = {b["service"] for b in db.bound_services(uid)}
        if source in {"jw", "mystu"} and source not in bound:
            report.append({"source": source, "code": -1, "skipped": True, "reason": "not_bound"})
            continue

        # public 源现在只读学校官网的公开栏目：网站公开页面，不需要登录、
        # 不需要授权、也不需要 WebVPN 会话（OA 那条线已整体下线）。

        # 数千人同时首同步时，直接排队挤 3 个槽位会让请求线程堆满。
        # 拿不到槽位就先告诉 App"在排队"，下轮再来，不阻塞。
        if not _SLOTS[source].acquire(blocking=False):
            report.append({"source": source, "code": -1, "skipped": True, "reason": "queued",
                           "retry_after": 20})
            continue

        run_id = db.start_run(uid, source)
        try:
            with tenant_scope(uid) as tenant:
                # public 源也要 vault：OA 通知走的是租户自己的 WebVPN 会话
                vault = tenant.vault()
                result = collect(vault, source)
                # 学校会话过期很频繁；绑定时密码已经加密存在租户自己的 keyring 里，
                # 这里直接用它重登一次再读，用户不用再去点"重新连接"。
                service = SESSION_FOR.get(source, source)
                if result.code == CODE_NEED_INPUT and vault is not None:
                    from .relogin import relogin

                    if relogin(vault, service):
                        result = collect(vault, source)
                        if result.code == CODE_OK:
                            result.detail = f"{result.detail}（已自动重登）"
        except Exception as exc:  # noqa: BLE001 - never let one source kill the sweep
            # 会话过期有时是抛异常而不是返回码：也走一次"静默重登 + 重试"，
            # 免得用户看到一屏红字。
            healed = None
            if source in SESSION_FOR:
                service = SESSION_FOR.get(source, source)
                try:
                    # 只进一层 scope，复用同一个 vault。
                    # 原来这里套了两层 tenant_scope，靠"外层已经拿住槽位"的注释
                    # 来解释——但锁本身没变可重入，第二次进直接自己等自己，
                    # 线程永久卡死、槽位不释放（审查报告第 06 项）。
                    with tenant_scope(uid) as tenant:
                        from .relogin import relogin

                        vault = tenant.vault()
                        if relogin(vault, service):
                            healed = collect(vault, source)
                            if healed.code == CODE_OK:
                                healed.detail = f"{healed.detail}（已自动重登）"
                except Exception:  # noqa: BLE001
                    healed = None
            if healed is None or healed.code != CODE_OK:
                db.finish_run(run_id, 1, type(exc).__name__)
                report.append({"source": source, "code": 1, "detail": type(exc).__name__})
                continue
            result = healed

        finally:
            _SLOTS[source].release()

        fresh_total = 0
        if result.code == CODE_OK:
            for kind, records in result.kind_records.items():
                if kind in SNAPSHOT_KINDS:
                    db.replace_kind(uid, source, kind, _flatten_timetable(records))
                    continue
                fresh = db.upsert_items(uid, source, kind, records)
                fresh_total += len(fresh)
                if fresh:
                    _notify_new(uid, source, kind, fresh)

        db.finish_run(run_id, result.code, result.detail, fresh_total)
        report.append({"source": source, "code": result.code, "detail": result.detail,
                       "new": fresh_total, "total": result.total})

    return report


def start_sync(uid: str, sources: list[str] | None = None, *, force: bool = False) -> bool:
    """Start a background sync for this user; False when one is already running."""
    with _RUNNING_GUARD:
        running = _RUNNING.get(uid)
        if running and running.is_alive():
            return False

        def work() -> None:
            try:
                sync_user(uid, sources, force=force)
            finally:
                with _RUNNING_GUARD:
                    if _RUNNING.get(uid) is threading.current_thread():
                        _RUNNING.pop(uid, None)

        thread = threading.Thread(target=work, name=f"sync-{uid[:8]}", daemon=True)
        _RUNNING[uid] = thread
        thread.start()
        return True


def is_syncing(uid: str) -> bool:
    with _RUNNING_GUARD:
        running = _RUNNING.get(uid)
        return bool(running and running.is_alive())


def _flatten_timetable(records: list[dict]) -> list[dict]:
    """Keep stu-mcp's whole timetable document (one row per semester), including
    the fields the App shows as warnings: pending notes, unarranged courses and
    whether the read was complete."""
    out: list[dict] = []
    for doc in records:
        semester = doc.get("semester") or ""
        out.append({
            "external_id": f"doc:{semester}",
            "title": f"{semester} 课表",
            "semester": semester,
            "status": doc.get("status", "ok"),
            "checked_at": doc.get("checked_at"),
            "courses": doc.get("courses", []),
            "unarranged": doc.get("unarranged", []),
            "unplaced_details": doc.get("unplaced_details", []),
            "pending": doc.get("pending", []),
            "coverage": doc.get("coverage", {}),
        })
    return out


def _notify_new(uid: str, source: str, kind: str, fresh: list[dict]) -> None:
    if kind not in {"notice", "grade", "exam", "task"}:
        return
    from .push import push_many

    titles = [r.get("title") or r.get("course_name") or "新条目" for r in fresh[:3]]
    push_many(uid, kind=kind, source=source, count=len(fresh), titles=titles,
              silence_maintenance=True)


def healthy_code(code: int) -> bool:
    return code == CODE_OK or code == CODE_MAINTENANCE or code == -1
