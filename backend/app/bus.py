"""到桑浦山校区的实时公交。

两种数据来源，同一份结构：

1. **真实时**：环境变量 `STU_BUS_UPSTREAM` 指到一个返回 JSON 的地址（学校班车系统、
   车来了、或自己写的转发），约定见 `_from_upstream()` 的注释；
2. **按班次推算**：没有上游时用首末班 + 发车间隔算"下一班几分钟后到"，
   `source="schedule"`，界面上会写明是推算值。

不做假的"实时"：没有上游就老实标推算，数字仍然可用（学生只关心还有几分钟）。
"""
from __future__ import annotations

import os
from datetime import datetime, timedelta, timezone

# 汕头市区到汕头大学（桑浦山校区）的常用线路。
# headway/first/last 取自公开线路信息的常见值，属"估算"；有官方班次就改这张表。
LINES = [
    {"name": "6", "from": "汕头火车站", "to": "汕头大学", "first": "06:30", "last": "21:30", "headway": 12, "stops": 28},
    {"name": "17", "from": "人民广场", "to": "汕头大学", "first": "06:40", "last": "21:00", "headway": 15, "stops": 24},
    {"name": "21", "from": "金砂公园", "to": "汕头大学", "first": "06:30", "last": "22:00", "headway": 15, "stops": 26},
    {"name": "27", "from": "达濠", "to": "汕头大学", "first": "06:20", "last": "20:30", "headway": 20, "stops": 31},
    {"name": "39", "from": "汕头大学正门", "to": "鮀浦", "first": "06:50", "last": "21:40", "headway": 15, "stops": 12},
    {"name": "K3", "from": "潮汕站", "to": "汕头大学", "first": "07:00", "last": "20:00", "headway": 25, "stops": 9},
]

CZ = timezone(timedelta(hours=8))
UPSTREAM = os.environ.get("STU_BUS_UPSTREAM", "").strip()
UPSTREAM_TIMEOUT = 6.0


def _minutes(text: str) -> int:
    hour, minute = text.split(":")
    return int(hour) * 60 + int(minute)


def _from_upstream() -> dict[str, dict] | None:
    """上游约定：GET UPSTREAM → {"lines": [{"name": "6", "eta_sec": 240, "next_sec": 900}]}

    eta_sec 是"最近一班还有多少秒到站"，next_sec 是可选的再下一班。
    返回 None 表示这次没拿到（超时/格式不对），调用方退回推算。
    """
    if not UPSTREAM:
        return None
    try:
        import httpx

        res = httpx.get(UPSTREAM, timeout=UPSTREAM_TIMEOUT)
        if res.status_code != 200:
            return None
        payload = res.json()
        rows = payload.get("lines") if isinstance(payload, dict) else None
        if not isinstance(rows, list):
            return None
        out: dict[str, dict] = {}
        for row in rows:
            if not isinstance(row, dict) or not row.get("name"):
                continue
            eta = row.get("eta_sec")
            if not isinstance(eta, (int, float)):
                continue
            out[str(row["name"])] = {
                "eta_sec": int(eta),
                "next_sec": int(row["next_sec"]) if isinstance(row.get("next_sec"), (int, float)) else None,
            }
        return out or None
    except Exception:  # noqa: BLE001 - 上游挂了就按班次推算
        return None


def _schedule_eta(line: dict, now: datetime) -> tuple[int | None, int | None, str]:
    """按首末班 + 间隔推算下一班的等待秒数与再下一班。"""
    first = _minutes(line["first"])
    last = _minutes(line["last"])
    headway = max(5, int(line["headway"]))
    now_min = now.hour * 60 + now.minute + now.second / 60

    if now_min < first:
        wait = (first - now_min) * 60
        return int(wait), int(wait + headway * 60), "首班前"
    if now_min > last:
        wait = (24 * 60 - now_min + first) * 60
        return int(wait), None, "末班已过"

    offset = (now_min - first) % headway
    wait = (headway - offset) % headway
    if wait == 0:
        wait = headway
    # 推算出来的这一班必须还落在运营区间内。
    # 原来只判"当前是否晚于末班"，于是末班 20:30、现在 20:29 时会算出
    # "660 秒后还有一班"，也就是 20:40 —— 末班之后凭空多出一趟车
    # （审查报告第 29 项）。越过末班就按"末班已过"处理，给下一日首班。
    if now_min + wait > last:
        wait_next_day = (24 * 60 - now_min + first) * 60
        return int(wait_next_day), None, "末班已过"
    next_wait = wait + headway
    next_sec = int(next_wait * 60) if now_min + next_wait <= last else None
    return int(wait * 60), next_sec, "班次推算"


def snapshot() -> dict:
    now = datetime.now(CZ)
    live = _from_upstream()
    rows = []
    for line in LINES:
        wait_sec, next_sec, note = _schedule_eta(line, now)
        source = "schedule"
        if live and line["name"] in live:
            wait_sec = live[line["name"]]["eta_sec"]
            next_sec = live[line["name"]]["next_sec"]
            source = "live"
            note = "实时"
        rows.append({
            "name": line["name"],
            "from": line["from"],
            "to": line["to"],
            "first": line["first"],
            "last": line["last"],
            "headway": line["headway"],
            "stops": line["stops"],
            "eta_sec": wait_sec,
            "next_sec": next_sec,
            "eta_min": None if wait_sec is None else max(0, round(wait_sec / 60)),
            "next_min": None if next_sec is None else max(0, round(next_sec / 60)),
            "source": source,
            "note": note,
        })
    live_count = sum(1 for r in rows if r["source"] == "live")
    return {
        "ok": True,
        "stop": "汕头大学（桑浦山校区）",
        "updated_at": now.isoformat(),
        "live": live_count > 0,
        "live_count": live_count,
        "note": (
            "到站时间来自上游接口，仅供参考。"
            if live_count
            else "本站没有接官方实时接口，下面的分钟数按首末班和发车间隔推算，实际会因路况浮动。"
        ),
        "official": "https://www.stu.edu.cn/",
        "sources": [
            {
                "name": "汕头市交通运输局",
                "url": "https://www.shantou.gov.cn/jtysj/",
                "note": "公交线路与班次的官方口径",
            },
            {
                "name": "学校来访交通指引",
                "url": "https://elc.stu.edu.cn/conf2023/INFORMATION/Transportation.htm",
                "note": "到桑浦山校区的乘车方式（学校官网）",
            },
            {
                "name": "高德地图 · 公交",
                "url": "https://www.amap.com/",
                "note": "第三方实时到站，可查车辆位置",
            },
            {
                "name": "车来了",
                "url": "https://www.chelaile.net.cn/",
                "note": "第三方实时到站",
            },
        ],
        "lines": rows,
    }
