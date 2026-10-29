"""Transit: realtime bus arrivals for the two campuses.

A provider returns minutes-until-arrival for a stop. The App polls us, and also
counts down locally between polls, so a slow upstream never freezes the UI.

Providers
  demo      deterministic, always available, shifts with the clock
  chelaile  车来了 realtime; needs the three values in `ChelaileProvider.config`

Set STU_TRANSIT_PROVIDER=chelaile and fill the config to go live. The response
shape is identical, so the App does not change.
"""
from __future__ import annotations

import hashlib
import os
import time
from typing import Any, Protocol

# stops the campus actually uses; ids are ours, `external` is the provider's
STOPS: list[dict[str, Any]] = [
    {
        "id": "sp-main",
        "campus": "桑浦山",
        "name": "汕头大学",
        "external": "[填写车来了 stopId]",
        "lines": ["2路", "5路", "12路", "21路"],
    },
    {
        "id": "sp-gate",
        "campus": "桑浦山",
        "name": "汕头大学东门",
        "external": "[填写车来了 stopId]",
        "lines": ["5路", "12路"],
    },
    {
        "id": "ec-main",
        "campus": "东海岸",
        "name": "汕头大学东校区",
        "external": "[填写车来了 stopId]",
        "lines": ["18路", "25路", "K3"],
    },
    {
        "id": "ec-library",
        "campus": "东海岸",
        "name": "东校区图书馆",
        "external": "[填写车来了 stopId]",
        "lines": ["18路", "25路"],
    },
]

BY_ID = {s["id"]: s for s in STOPS}


class Provider(Protocol):
    name: str

    def arrivals(self, stop: dict[str, Any]) -> list[dict[str, Any]]: ...


def _stable_int(seed: str, modulo: int) -> int:
    return int(hashlib.sha256(seed.encode()).hexdigest()[:8], 16) % modulo


class DemoProvider:
    """Deterministic per (line, stop, 30-second bucket), so it looks alive."""

    name = "demo"

    def arrivals(self, stop: dict[str, Any]) -> list[dict[str, Any]]:
        bucket = int(time.time() // 30)
        out = []
        for i, line in enumerate(stop["lines"]):
            base = _stable_int(f"{stop['id']}|{line}|{bucket}", 14) + 1
            # two upcoming buses per line
            for k in range(2):
                minutes = base + k * (8 + _stable_int(f"{line}{k}", 9))
                out.append({
                    "line": line,
                    "minutes": minutes,
                    "eta_at": int(time.time()) + minutes * 60,
                    "vehicle": f"{_stable_int(line + str(bucket), 9000) + 1000}",
                    "crowded": _stable_int(f"{line}{bucket}", 3) == 0,
                })
        return sorted(out, key=lambda a: a["minutes"])


class ChelaileProvider:
    """车来了 realtime. Fill `config` and this becomes live with no other change."""

    name = "chelaile"

    config = {
        "base": "https://web.chelaile.net.cn/api",
        "city_id": os.environ.get("STU_TRANSIT_CITY_ID", ""),
        "sign_key": os.environ.get("STU_TRANSIT_SIGN_KEY", ""),
        "user_id": os.environ.get("STU_TRANSIT_USER_ID", ""),
    }

    def arrivals(self, stop: dict[str, Any]) -> list[dict[str, Any]]:
        import httpx

        if not (self.config["city_id"] and self.config["sign_key"]):
            raise RuntimeError("transit_not_configured")

        params = {
            "cityId": self.config["city_id"],
            "stopId": stop["external"],
            "cne": "true",
            "v": "3.1.0",
            "src": "3",
            "userId": self.config["user_id"],
        }
        # 车来了 signs the sorted query with the client key
        ordered = "&".join(f"{k}={params[k]}" for k in sorted(params))
        params["sign"] = hashlib.md5((ordered + self.config["sign_key"]).encode()).hexdigest()

        with httpx.Client(timeout=httpx.Timeout(8, connect=4)) as client:
            res = client.get(f"{self.config['base']}/bus/stop", params=params)
            res.raise_for_status()
            payload = res.json()

        lines = ((payload.get("data") or {}).get("lines") or [])
        out, now = [], int(time.time())
        for item in lines:
            for bus in item.get("buses") or []:
                minutes = bus.get("time")
                if not isinstance(minutes, (int, float)) or minutes < 0:
                    continue
                out.append({
                    "line": item.get("lineNo") or item.get("lineName") or "",
                    "minutes": int(minutes),
                    "eta_at": now + int(minutes) * 60,
                    "vehicle": str(bus.get("busId") or ""),
                    "crowded": bool(bus.get("crowded")),
                })
        return sorted(out, key=lambda a: a["minutes"])


def provider() -> Provider:
    if os.environ.get("STU_TRANSIT_PROVIDER", "demo").lower() == "chelaile":
        return ChelaileProvider()
    return DemoProvider()


def campuses() -> list[dict]:
    out: dict[str, list] = {}
    for stop in STOPS:
        out.setdefault(stop["campus"], []).append({
            "id": stop["id"], "name": stop["name"], "lines": stop["lines"],
        })
    return [{"campus": k, "stops": v} for k, v in out.items()]


def arrivals(stop_id: str) -> dict:
    stop = BY_ID.get(stop_id)
    if stop is None:
        raise KeyError(stop_id)
    p = provider()
    return {
        "stop": {"id": stop["id"], "name": stop["name"], "campus": stop["campus"]},
        "provider": p.name,
        "generated_at": int(time.time()),
        "arrivals": p.arrivals(stop),
    }
