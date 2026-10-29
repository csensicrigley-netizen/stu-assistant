"""OPPO 推送 · 实况窗/胶囊通知适配器。

鉴权（本项目的简化口径）：
    auth_token = sha256(app_key + timestamp + master_secret) 的小写十六进制
    timestamp 用毫秒字符串，签名与时间戳一起放进请求头 `auth_token` 和表单。

实况窗提示语与卡片样式由 OPPO 推送服务与系统共同渲染，服务端只下发字段：
notification 里的 title / sub_title / content / style=2 / action_parameters，
以及 live_view 的时间字段（starts_at / ends_at / phase）。
"""
from __future__ import annotations

import hashlib
import json
import logging
import time
from typing import Any, Iterable

import httpx

from .models import LiveUpdate

log = logging.getLogger("stu_app.push.oppo")

SEND_URL = "https://api.push.oppomobile.com/server/v1/message/notification/unicast"
TIMEOUT = 30.0
SUCCESS_CODE = "0"


class OppoError(RuntimeError):
    """带错误码的推送异常，方便上层只记 code 不记原文。"""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(detail or code)
        self.code = code


def _client() -> httpx.Client:
    # 唯一出网出口，测试替换 httpx 即可接管。
    return httpx.Client(timeout=TIMEOUT)


def sign(app_key: str, timestamp: str, master_secret: str) -> str:
    """sha256(app_key + timestamp + master_secret)，小写十六进制。"""
    raw = f"{app_key}{timestamp}{master_secret}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


class OppoLivePush:
    """一条实时活动 → 一台/多台 OPPO 设备（unicast 接口，批量按 target_value 传）。"""

    def __init__(self, app_key: str = "", master_secret: str = ""):
        self.app_key = (app_key or "").strip()
        self.master_secret = (master_secret or "").strip()

    @property
    def configured(self) -> bool:
        return bool(self.app_key and self.master_secret)

    # ------------------------------------------------------------------
    # 发送
    # ------------------------------------------------------------------

    def send(self, tokens: Iterable[str], live: LiveUpdate) -> dict:
        """返回 {"sent", "failed", "reason"}，从不向上抛。"""
        if not self.configured:
            return {"sent": 0, "failed": 0, "reason": "dry_run"}

        devices = [str(t).strip() for t in (tokens or []) if str(t).strip()]
        if not devices:
            return {"sent": 0, "failed": 0, "reason": "no_tokens"}

        timestamp = str(int(time.time() * 1000))
        auth_token = sign(self.app_key, timestamp, self.master_secret)
        form = {
            "auth_token": auth_token,
            "timestamp": timestamp,
            "message": json.dumps(self._message(devices, live), ensure_ascii=False),
        }
        headers = {
            "auth_token": auth_token,
            "Content-Type": "application/x-www-form-urlencoded",
        }

        try:
            with _client() as client:
                resp = client.post(SEND_URL, data=form, headers=headers)
            ok, reason = self._judge(resp)
        except Exception as exc:  # noqa: BLE001 - 网络层什么都可能抛
            ok, reason = False, type(exc).__name__

        if ok:
            return {"sent": len(devices), "failed": 0, "reason": "ok"}
        log.warning("oppo 推送失败：%s", reason)
        return {"sent": 0, "failed": len(devices), "reason": reason}

    # ------------------------------------------------------------------
    # 报文
    # ------------------------------------------------------------------

    @staticmethod
    def _message(devices: list[str], live: LiveUpdate) -> dict:
        view = live.as_dict()
        return {
            "target_type": 2,                      # 2 = 按 token 列表投递
            "target_value": list(devices),
            "notification": {
                "title": view["title"],
                "sub_title": view["detail"],
                "content": view["detail"],
                "style": 2,                        # 2 = 实况窗/进度样式
                "action_parameters": json.dumps(
                    {"live_view": view}, ensure_ascii=False, separators=(",", ":")
                ),
                "starts_at": view["starts_at"],
                "ends_at": view["ends_at"],
                "phase": view["phase"],
            },
        }

    @staticmethod
    def _judge(resp: Any) -> tuple[bool, str]:
        if resp.status_code != 200:
            return False, f"http_{resp.status_code}"
        try:
            payload = resp.json() or {}
        except Exception:  # noqa: BLE001
            return False, "bad_json"
        code = str(payload.get("code", SUCCESS_CODE))
        if code not in {SUCCESS_CODE, ""}:
            return False, f"code_{code}"
        return True, "ok"
