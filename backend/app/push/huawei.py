"""华为 Push Kit · 实况窗（Live View）适配器。

服务端只负责把 live_view 载荷下发，剩下的交给系统和 AGC：

  * **实况窗权限必须在 AGC（AppGallery Connect）后台为本应用单独开通**，并按业务
    场景配置实况窗模板；没有开通时 `messages:send` 直接回错误码，不会自动退化成
    普通通知。
  * **实况窗由系统渲染**（HarmonyOS / EMUI 的桌面卡片与通知区），这里不画任何卡片，
    也不做本地布局。
  * `message.data` 在华为协议里必须是 **JSON 字符串**（不是对象），所以 live_view
    要 `json.dumps` 之后塞进去，客户端自己 parse。

鉴权：OAuth2 client_credentials 换 access_token，Authorization 头用它；报文里
`message.token` 才是设备 token。两者不要混。
"""
from __future__ import annotations

import json
import logging
from typing import Any, Iterable

import httpx

from .models import LiveUpdate

log = logging.getLogger("stu_app.push.huawei")

TOKEN_URL = "https://oauth-login.cloud.huawei.com/oauth2/v3/token"
SEND_URL = "https://push-api.cloud.huawei.com/v1/{app_id}/messages:send"
TIMEOUT = 30.0
SUCCESS_CODE = "80000000"


class HuaweiError(RuntimeError):
    """带错误码的推送异常，方便上层只记 code 不记原文。"""

    def __init__(self, code: str, detail: str = ""):
        super().__init__(detail or code)
        self.code = code


def _client() -> httpx.Client:
    # 唯一的出网出口：超时只在这里定义，测试替换 httpx 就能整体接管。
    return httpx.Client(timeout=TIMEOUT)


class HuaweiLivePush:
    """一条实时活动 → 若干台华为设备的实况窗。"""

    def __init__(self, app_id: str = "", client_id: str = "", client_secret: str = ""):
        self.app_id = (app_id or "").strip()
        self.client_id = (client_id or "").strip()
        self.client_secret = (client_secret or "").strip()

    @property
    def configured(self) -> bool:
        return bool(self.app_id and self.client_id and self.client_secret)

    # ------------------------------------------------------------------
    # OAuth2
    # ------------------------------------------------------------------

    def _token(self) -> str:
        """取 access_token；失败重试一次，两次都挂就抛 HuaweiError。"""
        form = {
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self.client_secret,
        }
        last = "token_failed"
        for attempt in (1, 2):
            try:
                with _client() as client:
                    resp = client.post(TOKEN_URL, data=form)
                if resp.status_code != 200:
                    last = f"http_{resp.status_code}"
                else:
                    payload = resp.json() or {}
                    token = str(payload.get("access_token") or "")
                    if token:
                        return token
                    last = "no_access_token"
            except Exception as exc:  # noqa: BLE001 - 网络层什么都可能抛
                last = type(exc).__name__
            log.warning("huawei token 第 %s 次失败：%s", attempt, last)
        raise HuaweiError(last)

    # ------------------------------------------------------------------
    # 发送
    # ------------------------------------------------------------------

    def send(self, tokens: Iterable[str], live: LiveUpdate) -> dict:
        """逐台设备下发；返回 {"sent", "failed", "reason"}，从不向上抛。"""
        if not self.configured:
            return {"sent": 0, "failed": 0, "reason": "dry_run"}

        devices = [str(t).strip() for t in (tokens or []) if str(t).strip()]
        if not devices:
            return {"sent": 0, "failed": 0, "reason": "no_tokens"}

        try:
            access_token = self._token()
        except Exception as exc:  # noqa: BLE001
            code = getattr(exc, "code", None) or type(exc).__name__
            return {"sent": 0, "failed": len(devices), "reason": f"token_failed:{code}"}

        sent = 0
        failed = 0
        reasons: list[str] = []
        for device_token in devices:
            body = self._body(device_token, live)
            try:
                with _client() as client:
                    resp = client.post(
                        SEND_URL.format(app_id=self.app_id),
                        json=body,
                        headers={
                            "Authorization": f"Bearer {access_token}",
                            "Content-Type": "application/json; charset=UTF-8",
                        },
                    )
                ok, reason = self._judge(resp)
            except Exception as exc:  # noqa: BLE001
                ok, reason = False, type(exc).__name__
            if ok:
                sent += 1
            else:
                failed += 1
                reasons.append(reason)
                log.warning("huawei 推送失败：%s", reason)

        return {"sent": sent, "failed": failed, "reason": reasons[0] if reasons else "ok"}

    # ------------------------------------------------------------------
    # 报文
    # ------------------------------------------------------------------

    @staticmethod
    def _body(device_token: str, live: LiveUpdate) -> dict:
        view = live.as_dict()
        data = json.dumps(
            {
                "live_view": {
                    "title": view["title"],
                    "detail": view["detail"],
                    "starts_at": view["starts_at"],
                    "ends_at": view["ends_at"],
                    "phase": view["phase"],
                }
            },
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return {
            "message": {
                "token": [device_token],
                "notification": {"title": view["title"], "body": view["detail"]},
                "data": data,
                "android": {"urgency": "NORMAL", "category": "TRANSPORT"},
            }
        }

    @staticmethod
    def _judge(resp: Any) -> tuple[bool, str]:
        if resp.status_code != 200:
            return False, f"http_{resp.status_code}"
        try:
            payload = resp.json() or {}
        except Exception:  # noqa: BLE001 - 非 JSON 响应也算失败
            return False, "bad_json"
        code = str(payload.get("code") or "")
        if code and code != SUCCESS_CODE:
            return False, f"code_{code}"
        return True, "ok"
