"""Push delivery. DEMO mode logs; production wires APNs / FCM / vendor channels.

The shape stays identical across providers: one call per user event, fan out to
that user's registered device tokens.
"""
from __future__ import annotations

import json
import logging

from .config import settings
from .db import DB, now

log = logging.getLogger("stu_app.push")

_KIND_TEXT = {
    "notice": "条新通知",
    "grade": "门新成绩",
    "exam": "场考试安排更新",
    "homework": "项新作业",
    "task": "项新作业",
    "course": "门课程",
    # 版本通道：新版本 -> 装机版本落后的设备
    "update": "个新版本可用",
}


def _log_only(uid: str, payload: dict) -> bool:
    # kind and count only: course titles and notice text stay out of the server log
    log.info("PUSH %s kind=%s source=%s count=%s", uid[:8], payload.get("kind"),
             payload.get("source"), payload.get("title"))
    return True


def push_many(uid: str, *, kind: str, source: str, count: int, titles: list[str],
              silence_maintenance: bool = True) -> int:
    """Send one collapsed notification per (kind, batch). Returns devices reached."""
    db = DB(settings.db_path)
    devices = db.devices_of(uid)
    body = {
        "kind": kind,
        "source": source,
        "title": f"{count}{_KIND_TEXT.get(kind, '条更新')}",
        "body": "、".join(t for t in titles if t)[:120],
        "data": {"deeplink": f"stuapp://{kind}"},
    }

    ok = True
    if settings.demo or not devices:
        ok = _log_only(uid, body)
    else:
        ok = _dispatch(devices, body)

    with db.conn() as c:
        c.execute(
            "INSERT INTO push_log(user_id,kind,payload,sent_at,ok) VALUES(?,?,?,?,?)",
            (uid, kind, json.dumps(body, ensure_ascii=False), now(), int(ok)),
        )
    return len(devices)


def _dispatch(devices: list[dict], body: dict) -> bool:
    """Replace with real providers. APNs wants HTTP/2 + JWT; FCM wants OAuth2.

    Kept as a single seam so collectors and sync never learn provider details.
    When app/providers.py is absent the call degrades to a log line instead of
    raising, so the whole pipeline stays testable before credentials exist.
    """
    try:
        from .providers import send_apns, send_fcm
    except ImportError:
        log.warning("providers 未接入，%d 台设备仅记录日志", len(devices))
        return _log_only("-", {"devices": len(devices), **body})

    all_ok = True
    for dev in devices:
        token = dev["token"]
        if dev["platform"] == "ios":
            all_ok &= send_apns(token, body)
        else:
            all_ok &= send_fcm(token, body)
    return all_ok
