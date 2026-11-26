"""WebVPN 后台保活已停用（零服务端密码/TOTP 种子落盘）。

OA 通知线路已下线，且服务端不再保存学校密码或数盾 TOTP 种子，不再启动后台
保活巡检线程。保留只读桩函数以维持既有接口向后兼容。
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

log = logging.getLogger("stu.keepalive")

STATE_PATH = Path(os.environ.get("STU_WEBVPN_STATE", "/var/lib/stu-assistant/webvpn_state.json"))
INTERVAL_MIN = 0
ENABLED = False

_started = False


def state() -> dict:
    """给 /v1/webvpn/state 用的只读快照（保活已停用，始终返回空状态）。"""
    return {}


def tick() -> dict:
    """已停用：不探针 OA、不读取或重登任何租户会话。"""
    return {}


def start() -> None:
    """已停用：绝不启动后台保活线程。"""
    log.info("WebVPN 后台保活已停用（零密码/TOTP 种子落盘策略）")
