"""vivo Push · 原子岛 / 原子通知适配器。

vivo 的胶囊（OriginOS 的「原子岛」「原子通知」）由系统和 vivo Push 服务渲染，
第三方只能通过鉴权 + 消息体里的通知类型来"申请"一个原子通知，能不能变成岛由
系统按场景决定：

  * 必须在 vivo 开放平台（dev.vivo.com.cn）建应用、开通 **Push 服务**，并在
    「原子通知/原子岛」里按场景申请权限；没有开通时消息照发，但只会是普通通知。
  * 鉴权分两步：先 POST /message/auth 用 appId + appKey + appSecret + timestamp
    换 authToken（默认 24 小时），再用 authToken 发消息。
  * 消息体里 `notifyType`、`skipType`、`classification` 决定通道形态；
    `requestId` 必须唯一，重复会被服务端判为幂等重复。
  * 时钟类卡片靠 `time` 字段（秒级时间戳）驱动，这里把 live_view 编进 clientData
    供客户端还原，同时把时间放进 time 字段。

与华为/OPPO 保持同一套接口：`configured`、`send(tokens, live) -> dict`。
"""
from __future__ import annotations

import hashlib
import json
import logging
import time
import uuid
from typing import Any

import httpx

from .models import LiveUpdate

log = logging.getLogger("stu_app.push.vivo")

AUTH_URL = "https://api-push.vivo.com.cn/message/auth"
SEND_URL = "https://api-push.vivo.com.cn/message/send"
TIMEOUT = 30.0
SUCCESS = 0
AUTH_EXPIRED = 10200


class VivoError(RuntimeError):
    def __init__(self, code: str, detail: str = ""):
        super().__init__(detail or code)
        self.code = code


def _client() -> httpx.Client:
    return httpx.Client(timeout=TIMEOUT)


class VivoLivePush:
    def __init__(self, app_id: str, app_key: str, app_secret: str):
        self.app_id = app_id
        self.app_key = app_key
        self.app_secret = app_secret
        self._token: str | None = None
        self._token_at = 0.0

    @property
    def configured(self) -> bool:
        return bool(self.app_id and self.app_key and self.app_secret)

    def _auth(self, client: httpx.Client) -> str:
        # vivo 的签名是 appKey + timestamp + appSecret 的 md5，时间戳为毫秒。
        ts = str(int(time.time() * 1000))
        sign = hashlib.md5(f"{self.app_key}{ts}{self.app_secret}".encode()).hexdigest()
        body = {"appId": self.app_id, "appKey": self.app_key, "timestamp": ts, "sign": sign}
        for attempt in range(2):  # 只重试一次，超时 30s
            try:
                res = client.post(AUTH_URL, json=body)
                data = res.json()
                if data.get("result") == SUCCESS and data.get("authToken"):
                    self._token = data["authToken"]
                    self._token_at = time.time()
                    return self._token
                raise VivoError(str(data.get("result")), data.get("desc", ""))
            except (httpx.HTTPError, ValueError) as exc:
                if attempt == 1:
                    raise VivoError("token_failed", str(exc)) from None
        raise VivoError("token_failed")

    def _token_for(self, client: httpx.Client, force: bool = False) -> str:
        fresh = self._token and time.time() - self._token_at < 23 * 3600
        if fresh and not force:
            return self._token  # type: ignore[return-value]
        return self._auth(client)

    def _payload(self, token: str, live: LiveUpdate, request_id: str) -> dict[str, Any]:
        at = live.as_dict()
        # 原子通知按"时刻"呈现：before 用它开课的时刻，during 用结束时刻做消耗。
        moment = at["starts_at"] if at["phase"] == "before" else at["ends_at"]
        return {
            "appId": self.app_id,
            "regId": token,
            "notifyType": 4,  # 4 = 时间类原子通知
            "skipType": 1,
            "classification": 1,
            "requestId": request_id,
            "title": at["title"],
            "content": at["detail"],
            "time": int(moment // 1000),
            "clientData": json.dumps({"live_view": at}, ensure_ascii=False),
        }

    def send(self, tokens: list[str], live: LiveUpdate) -> dict:
        if not self.configured:
            return {"skipped": "dry_run"}
        sent = 0
        failed = 0
        reason = ""
        with _client() as client:
            try:
                token = self._token_for(client)
            except VivoError as exc:
                return {"sent": 0, "failed": len(tokens), "reason": f"token_failed:{exc.code}"}
            for device in tokens:
                request_id = f"{self.app_id}-{uuid.uuid4().hex[:16]}"
                try:
                    res = client.post(
                        SEND_URL,
                        json=self._payload(device, live, request_id),
                        headers={"authToken": token},
                    )
                    data = res.json()
                    if data.get("result") == SUCCESS:
                        sent += 1
                        continue
                    # authToken 过期：换一次再发这一条
                    if str(data.get("result")) == str(AUTH_EXPIRED):
                        token = self._token_for(client, force=True)
                        again = client.post(
                            SEND_URL,
                            json=self._payload(device, live, request_id),
                            headers={"authToken": token},
                        )
                        if again.json().get("result") == SUCCESS:
                            sent += 1
                            continue
                    failed += 1
                    reason = reason or f"vivo_{data.get('result')}:{data.get('desc', '')}"
                except (httpx.HTTPError, ValueError) as exc:
                    failed += 1
                    reason = reason or f"transport:{exc}"
        out = {"sent": sent, "failed": failed}
        if reason:
            out["reason"] = reason
        return out
