"""实时活动（实况窗）推送路由工厂。

`build_router(get_user)` 把路由绑到调用方传进来的登录态依赖上，所以 main.py 只需要
`app.include_router(build_router(current_user))` 一行，测试也能换成自己的假用户。

硬约定：这个端点**永不 5xx**。凭据缺失、没有设备 token、厂商接口抽风，都折进
per-provider 结果里（`skipped=dry_run` / `skipped=no_tokens` / `reason=...`），
因为推送是附带动作，不能把「活动创建成功」这件事一起弄挂。
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field, field_validator

from ..config import settings
from ..db import DB
from .huawei import HuaweiLivePush
from .models import LiveUpdate
from .oppo import OppoLivePush
from .vivo import VivoLivePush

log = logging.getLogger("stu_app.push.live")

PROVIDERS = ("huawei", "oppo", "vivo")


# --------------------------------------------------------------------------
# 请求体
# --------------------------------------------------------------------------

class LiveIn(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    detail: str = Field(default="", max_length=1000)
    starts_at: int
    ends_at: int
    phase: str = "before"

    @field_validator("phase")
    @classmethod
    def _phase(cls, value: str) -> str:
        return value if value in {"before", "during"} else "before"


class LivePushIn(BaseModel):
    targets: list[str] = Field(default_factory=list)
    live: LiveIn


# --------------------------------------------------------------------------
# 环境与数据
# --------------------------------------------------------------------------

def _env(name: str) -> str:
    return (os.environ.get(name) or "").strip()


def _adapters() -> dict[str, Any]:
    """每次请求按环境变量现构造：改了 env 立刻生效，不需要重启进程。"""
    return {
        "huawei": HuaweiLivePush(
            _env("HUAWEI_APP_ID"), _env("HUAWEI_CLIENT_ID"), _env("HUAWEI_CLIENT_SECRET")
        ),
        "oppo": OppoLivePush(_env("OPPO_APP_KEY"), _env("OPPO_MASTER_SECRET")),
        "vivo": VivoLivePush(_env("VIVO_APP_ID"), _env("VIVO_APP_KEY"), _env("VIVO_APP_SECRET")),
    }


def _db_path() -> Path:
    return Path(settings.db_path)


def _device_tokens(uid: str, provider: str) -> list[str]:
    """从 devices 表取该用户的设备 token。

    表里目前没有厂商字段（platform 只有 ios/android），所以 android 设备对已配置的
    厂商都算候选；接真实厂商通道时把 platform 拆成 huawei/oppo 精确匹配即可。
    读库失败按「没有 token」处理，不抛给调用方。
    """
    try:
        rows = DB(_db_path()).devices_of(uid)
    except Exception as exc:  # noqa: BLE001 - 库坏了也不能 500
        log.warning("读取 devices 失败（%s），按无 token 处理", type(exc).__name__)
        return []

    out: list[str] = []
    seen: set[str] = set()
    for row in rows:
        token = str(row.get("token") or "").strip()
        platform = str(row.get("platform") or "").strip().lower()
        if not token or token in seen:
            continue
        if platform in {provider, "android"}:
            seen.add(token)
            out.append(token)
    return out


def _dispatch(provider: str, adapter: Any, tokens: list[str], live: LiveUpdate) -> dict:
    try:
        result = dict(adapter.send(tokens, live))
    except Exception as exc:  # noqa: BLE001 - 适配器内部已兜一层，这里再兜一层
        log.warning("%s 推送异常：%s", provider, type(exc).__name__)
        return {"skipped": "error", "reason": type(exc).__name__}
    result.setdefault("sent", 0)
    result.setdefault("failed", 0)
    result.setdefault("reason", "ok")
    return result


# --------------------------------------------------------------------------
# 路由
# --------------------------------------------------------------------------

def build_router(get_user) -> APIRouter:
    router = APIRouter(tags=["push"])

    @router.post("/v1/push/live")
    def push_live(body: LivePushIn, uid: str = Depends(get_user)) -> dict:
        live = LiveUpdate(
            title=body.live.title,
            detail=body.live.detail,
            starts_at=body.live.starts_at,
            ends_at=body.live.ends_at,
            phase=body.live.phase,
        )

        wanted: list[str] = []
        for name in (body.targets or list(PROVIDERS)):
            key = str(name).strip().lower()
            if key not in wanted:
                wanted.append(key)

        adapters = _adapters()
        results: dict[str, dict] = {}
        for key in wanted:
            if key not in PROVIDERS:
                results[key] = {"skipped": "unknown_target"}
                continue
            adapter = adapters[key]
            if not adapter.configured:
                results[key] = {"skipped": "dry_run"}
                continue
            tokens = _device_tokens(uid, key)
            if not tokens:
                results[key] = {"skipped": "no_tokens"}
                continue
            results[key] = _dispatch(key, adapter, tokens, live)

        log.info("live push uid=%s targets=%s results=%s", uid[:8], wanted,
                 {k: v.get("reason", v.get("skipped")) for k, v in results.items()})
        return {"ok": True, "live": live.as_dict(), "providers": results}

    return router
