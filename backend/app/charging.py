"""Charging: the campus 4G socket platform (wx.isfdz.com).

The read path is stu-mcp's verified anonymous client — it discovers the
platform's public JS config, signs each request, and decodes the port list.
Nothing here logs in, writes, or starts a session.

设备清单核对方式（2026-10-29 重做，别再凭猜）：

  学校给的《学校充电桩设备地址》文档里，每个地点配一张**机身上二维码的照片**。
  逐个把照片里的二维码解出来，拿到的是 `https://wx.isfdz.com/charge/enter/<CODE>`
  —— 这才是「地点 ↔ 设备编号」唯一可靠的对应关系。
  之前的清单是按 stu-mcp 自带的注册表抄的，名字和编号**大面积错位**
  （比如 4GR00008480 根本不是"明德书院"，而是"修远书院"），
  所以这次全部以二维码为准重排。

  另外 4GR00006723（教师宿舍）原来根本不在清单里，是文档里解出来的第 15 台。

  4GR00008477 平台回「设备不在线」而不是「业务失败」，说明编号存在但当前离线；
  它的地点名没有二维码可对，标 unverified，界面上如实说明。
"""
from __future__ import annotations

import datetime as dt
import threading
import time
from typing import Any

# 清单核对时间与方式，随数据一起给前端，界面上要写出来
REGISTRY_VERIFIED_ON = "2026-10-29"
REGISTRY_METHOD = "按机身二维码逐台核对（学校《学校充电桩设备地址》）"

# code, name, location, capacity, verified
#   verified=True  —— 二维码解出来的编号与地点，可信
#   verified=False —— 编号有效（平台有响应）但地点名无二维码可对，界面要标出来
DEVICES: list[dict[str, Any]] = [
    # ---- 思源书院（左一/左二/左三/左四）----
    {"code": "4GR00008492", "name": "思源书院左一", "location": "思源书院", "capacity": 20, "verified": True},
    {"code": "4GR00008485", "name": "思源书院左二", "location": "思源书院", "capacity": 20, "verified": True},
    {"code": "4GR00008489", "name": "思源书院左三", "location": "思源书院", "capacity": 20, "verified": True},
    {"code": "4GR00008490", "name": "思源左四", "location": "思源书院", "capacity": 20, "verified": True},
    # ---- 图书馆（一/二/三）----
    {"code": "4GR00008495", "name": "图书馆一", "location": "图书馆", "capacity": 20, "verified": True},
    {"code": "4GR00008493", "name": "图书馆二", "location": "图书馆", "capacity": 20, "verified": True},
    {"code": "4GR00008491", "name": "图书馆三", "location": "图书馆", "capacity": 20, "verified": True},
    # ---- 书院 ----
    {"code": "4GR00008494", "name": "敬一书院", "location": "敬一书院", "capacity": 20, "verified": True},
    {"code": "4GR00008498", "name": "志诚书院", "location": "志诚书院", "capacity": 20, "verified": True},
    {"code": "4GR00008480", "name": "修远书院", "location": "修远书院", "capacity": 20, "verified": True},
    # ---- 教学与运动 ----
    {"code": "4GR00008484", "name": "文科教学楼", "location": "文科教学楼", "capacity": 20, "verified": True},
    {"code": "4GR00008497", "name": "滑板场一", "location": "滑板场", "capacity": 20, "verified": True},
    {"code": "4GR00008488", "name": "滑板场二", "location": "滑板场", "capacity": 20, "verified": True},
    # ---- 生活区 ----
    {"code": "4GR00006723", "name": "教师宿舍", "location": "教师宿舍", "capacity": 20, "verified": True},
    # ---- 编号有效、地点待补 ----
    {"code": "4GR00008477", "name": "水电中心（名称待核）", "location": "水电中心", "capacity": 20, "verified": False},
]

STATE_LABELS = {
    0: "粘连故障",
    1: "空闲",
    2: "占用",
    3: "开路故障",
    4: "设备急停",
    5: "过温保护",
    6: "烟感报警",
    7: "过压故障",
    8: "欠压故障",
}

BY_CODE = {d["code"]: d for d in DEVICES}


def _norm(value: str) -> str:
    return "".join(c for c in value.casefold() if not c.isspace() and c not in "-_")


def search(query: str = "") -> list[dict]:
    q = _norm(query.strip())
    if q in {"", "全部", "所有", "all"}:
        return DEVICES
    return [d for d in DEVICES if q in _norm(d["name"]) or q in _norm(d["location"]) or q in _norm(d["code"])]


def ports(code: str, capacity: int) -> dict:
    """Live port states for one device, through stu-mcp's signed client."""
    from stu_mcp.charging_devices import Device  # type: ignore
    from stu_mcp.features.charging.adapter import ChargingClient  # type: ignore

    dev = BY_CODE.get(code) or {"name": code, "location": "", "capacity": capacity}
    with ChargingClient() as client:
        return client.device(
            Device(code=code, name=dev["name"], location=dev["location"], capacity=dev.get("capacity", capacity))
        )


def has_client() -> bool:
    try:
        import stu_mcp.features.charging.adapter  # noqa: F401
        return True
    except Exception:
        return False


# --------------------------------------------------------------------------
# shared cache: every user sees the same public sockets, so one upstream read
# per device per minute serves the whole site (the platform rate-limits)
# --------------------------------------------------------------------------

FRESH_SECONDS = 120     # serve from cache inside this window
KEEP_SECONDS = 900      # after a failure, keep showing the last sample this long
OVERVIEW_BUDGET = 45    # 一轮总览最多花多少秒向上游取数，超了用缓存顶


_CACHE: dict[str, tuple[float, dict]] = {}
_UPSTREAM = threading.Lock()  # one upstream read at a time, site-wide


def _iso(ts: float) -> str:
    return dt.datetime.fromtimestamp(ts, dt.timezone.utc).isoformat()


def _view(ts: float, state: dict, stale: bool) -> dict:
    return {**state, "collected_at": _iso(ts), "stale": stale}


def cached_ports(code: str, *, allow_fetch: bool = True) -> dict:
    dev = BY_CODE[code]
    hit = _CACHE.get(code)
    if hit and time.time() - hit[0] < FRESH_SECONDS:
        return _view(hit[0], hit[1], False)
    if not allow_fetch:
        if hit and time.time() - hit[0] < KEEP_SECONDS:
            return _view(hit[0], hit[1], True)
        raise LookupError("not_cached")
    with _UPSTREAM:
        hit = _CACHE.get(code)  # another request may have just refreshed it
        if hit and time.time() - hit[0] < FRESH_SECONDS:
            return _view(hit[0], hit[1], False)
        try:
            state = ports(code, dev["capacity"])
        except Exception:
            if hit and time.time() - hit[0] < KEEP_SECONDS:
                return _view(hit[0], hit[1], True)
            raise
        now = time.time()
        _CACHE[code] = (now, state)
        return _view(now, state, False)


def overview() -> list[dict]:
    """All registered devices, fresh where possible, within a time budget.

    上游约束（实测）：
      * 单个 ChargingClient 会话最多 18 个请求（一台设备 2 个）；
        这里每台设备都新开一个 client，所以不吃这条限制；
      * 真正的限制是平台侧限流，以及总耗时。
    所以策略是：**全部允许现取**，只用一个总时间预算兜底（超预算的才退回缓存），
    这样第一次打开不会有一半设备显示"无数据"。
    """
    started = time.time()
    out: list[dict] = []
    for dev in DEVICES:
        within = time.time() - started < OVERVIEW_BUDGET
        try:
            out.append({"device": dev, "state": cached_ports(dev["code"], allow_fetch=within)})
        except Exception as exc:  # noqa: BLE001 - one device must not hide the rest
            out.append({"device": dev, "state": None, "error": str(getattr(exc, "code", "") or "unavailable")})
    return out
