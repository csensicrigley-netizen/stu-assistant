"""版本登记 + 「新版本推给旧客户端」通道。

两件事分开放：

1. `latest()`   —— 当前线上最新版本是什么（读同目录 `latest_version.json`）。
2. `broadcast()` —— 把「有新版本」推给所有装机版本落后的设备。

客户端只要在 `POST /v1/devices` 时报一次自己的 `versionName/versionCode`，
服务器就把这台设备的版本记进 `devices.app_code`；以后每次发版跑一次

    python -m app.version push

就只会通知到「真·旧版本」的设备，新版本用户不会被打扰。

为什么不让客户端自己轮询就够了：网页版刷新即最新，但装在手机里的 App
自己不会重装。所以通道要由服务器推，而不是等人想起来去更新。
"""
from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

from .config import settings
from .db import DB

log = logging.getLogger("stu_app.version")

LATEST_FILE = Path(__file__).with_name("latest_version.json")

# 没有登记文件时的兜底：跟 Android build.gradle 保持一致
FALLBACK = {
    "versionName": "1.0.19",
    "versionCode": 20,
    "apk": "/download/stu-assistant.apk",
    "notes": [],
    "mandatory": False,
    "releasedAt": None,
    "minSupported": "1.0.0",
}

_cache: tuple[float, dict] | None = None


def latest() -> dict:
    """读登记文件；文件没变就用缓存，避免每次请求都读盘。"""
    global _cache
    try:
        mtime = LATEST_FILE.stat().st_mtime
    except OSError:
        return dict(FALLBACK)
    if _cache and _cache[0] == mtime:
        return dict(_cache[1])
    try:
        # utf-8-sig：Windows 上写出来的 JSON 可能带 BOM，直接 utf-8 读会让
        # json.loads 在第一格就炸，然后悄悄退回 FALLBACK —— 版本号就永远是旧的。
        raw = json.loads(LATEST_FILE.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return dict(FALLBACK)
    data = {**FALLBACK, **{k: v for k, v in raw.items() if v is not None}}
    _cache = (mtime, data)
    return dict(data)


def parse_pair(name: str | None, code: int | None) -> tuple[str | None, int | None]:
    """客户端上报的版本。两个都可能是 None（老版本 App 不会报）。"""
    clean_name = (name or "").strip()[:32] or None
    try:
        clean_code = int(code) if code is not None else None
    except (TypeError, ValueError):
        clean_code = None
    if clean_code is not None and not 0 <= clean_code <= 10_000_000:
        clean_code = None
    return clean_name, clean_code


def is_outdated(code: int | None) -> bool:
    """没报版本也算旧（那种客户端肯定比线上早）。"""
    if code is None:
        return True
    return code < int(latest()["versionCode"])


def hint_for(name: str | None, code: int | None) -> dict | None:
    """要展示更新提示就返回提示体，否则 None。"""
    if not is_outdated(code):
        return None
    cur = latest()
    return {
        "versionName": cur["versionName"],
        "versionCode": cur["versionCode"],
        "apk": cur.get("apk") or "/download/stu-assistant.apk",
        "notes": cur.get("notes") or [],
        "mandatory": bool(cur.get("mandatory")),
        "current": {"versionName": name, "versionCode": code},
    }


def broadcast(*, force: bool = False) -> dict:
    """把新版本推给所有落后设备。返回统计。"""
    from .push import push_many

    cur = latest()
    db = DB(settings.db_path)
    devices = db.outdated_devices(int(cur["versionCode"]))
    if not devices:
        return {"ok": True, "latest": cur["versionName"], "devices": 0, "pushed": 0, "skipped": "up-to-date"}

    title = f"新版本 {cur['versionName']} 已发布"
    by_user: dict[str, int] = {}
    for d in devices:
        by_user[d["user_id"]] = by_user.get(d["user_id"], 0) + 1

    pushed = 0
    for uid, n in by_user.items():
        try:
            push_many(uid, kind="update", source="server", count=1, titles=[title])
            pushed += n
        except Exception as exc:  # noqa: BLE001 - 推送是附带动作，不能把发版弄挂
            log.warning("版本推送失败 uid=%s: %s", uid[:8], exc)
    log.info("版本推送：最新 %s，落后设备 %d 台，覆盖 %d 台", cur["versionName"], len(devices), pushed)
    return {"ok": True, "latest": cur["versionName"], "versionCode": cur["versionCode"],
            "devices": len(devices), "users": len(by_user), "pushed": pushed,
            "forced": bool(force)}


def bump(*, name: str, code: int, notes: list[str] | None = None,
         mandatory: bool = False) -> dict:
    """写登记文件。发版脚本只需要改这一个文件。"""
    payload = {
        "versionName": name,
        "versionCode": int(code),
        "apk": "/download/stu-assistant.apk",
        "notes": notes or [],
        "mandatory": bool(mandatory),
        "releasedAt": __import__("datetime").datetime.now().isoformat(timespec="seconds"),
        "minSupported": FALLBACK["minSupported"],
    }
    LATEST_FILE.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    global _cache
    _cache = None
    return {"ok": True, **payload}


def _main(argv: list[str]) -> int:
    import argparse

    ap = argparse.ArgumentParser(prog="python -m app.version", description="版本登记与推送")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("show", help="打印当前登记的最新版本")
    b = sub.add_parser("bump", help="登记一个新版本")
    b.add_argument("--name", required=True)
    b.add_argument("--code", required=True, type=int)
    b.add_argument("--note", action="append", default=[])
    b.add_argument("--mandatory", action="store_true")
    p = sub.add_parser("push", help="把新版本推给落后设备")
    p.add_argument("--force", action="store_true")
    args = ap.parse_args(argv or ["show"])

    if args.cmd == "bump":
        print(json.dumps(bump(name=args.name, code=args.code, notes=args.note,
                              mandatory=args.mandatory), ensure_ascii=False, indent=2))
        return 0
    if args.cmd == "push":
        print(json.dumps(broadcast(force=args.force), ensure_ascii=False, indent=2))
        return 0
    print(json.dumps(latest(), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
