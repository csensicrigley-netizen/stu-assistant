"""带人工验证的 WebVPN 登录：图形验证码 + 数盾动态码。

**为什么不用 stu-mcp 的自动登录**（2026-10-29 实测确诊）

学校在 WebVPN 登录页开了图形验证码：

    GET /por/login_auth.csp  -> RndImg = 1
    GET /public/psw_config   -> USE_RAND_CODE = 1
    （MID_ATK_CHECK = 0，本机安全校验没开）

stu-mcp 的协议版（`stu_mcp.auth_http.webvpn.acquire_state`）在**提交密码之前**
看到这两个标志就直接放弃：

    if any(item.get("RndImg") != "0" ...) or config.get("USE_RAND_CODE") != "0":
        raise AppError("auto_login_interaction_required", "WebVPN 要求人工挑战或本机安全验证。")

也就是说它一次都没真正提交过密码，永远走不到「数盾六位动态码」那一步——
用户看到的是一个死错误码，点多少次都一样。

这个模块自己把整条路走完：

    出图（/por/rand_code.csp）→ 人看图输 4 位 → 提交密码
      → 学校要令牌就再要他现输 6 位动态码 → 会话落盘

边界：密码只在请求里用一次，进程内不落盘、不进日志；图码与动态码都由本人现输，
服务器不猜、不生成、不重放。
"""
from __future__ import annotations

import base64
import threading
import time
import uuid

import httpx

from stu_mcp.auth_http.webvpn import (  # 纯函数/常量，直接复用
    CONF,
    CONFIG,
    GRAPHICAL_CODES,
    INIT,
    ORIGIN,
    PASSWORD,
    RESOURCES,
    TOTP,
    WEB_HEADERS,
    auth_fields,
    encrypt_password,
    oa_route,
)

CAPTCHA_URL = ORIGIN + "/por/rand_code.csp"


class WrongCaptcha(Exception):
    """图码不对：重新出一张图，让他再输一次。"""


class _NeedCaptcha(Exception):
    """流程内需要一张新的验证码图。"""


class _Pending:
    """一次登录的现场：HTTP 会话 + 登录页下发的加密参数。"""

    def __init__(self, user_id: str) -> None:
        self.user_id = user_id
        self.token = uuid.uuid4().hex[:16]
        self.created = time.time()
        self.client = httpx.Client(
            verify=True,
            trust_env=False,
            follow_redirects=False,
            timeout=40,
            headers=dict(WEB_HEADERS),
        )
        self.fresh: dict[str, str] = {}
        self.sid = ""
        self.user = ""
        self.password = ""
        self.needs_captcha = False

    def close(self) -> None:
        try:
            self.client.close()
        except Exception:  # noqa: BLE001
            pass
        self.password = ""


_lock = threading.Lock()
_pending: dict[str, _Pending] = {}
_TTL = 600  # 十分钟没走完就作废，别把密码留在内存里


def _sweep() -> None:
    now = time.time()
    for key, item in list(_pending.items()):
        if now - item.created > _TTL:
            item.close()
            _pending.pop(key, None)


def _get(p: _Pending, url: str) -> httpx.Response:
    response = p.client.get(url)
    if response.status_code != 200:
        raise _NeedCaptcha(f"webvpn_http_{response.status_code}")
    return response


def _post(p: _Pending, url: str, data: dict[str, str]) -> dict[str, str]:
    response = p.client.post(url, data=data, headers={"Origin": ORIGIN})
    if response.status_code != 200:
        raise _NeedCaptcha(f"webvpn_http_{response.status_code}")
    return auth_fields(response.content)


def _captcha_image(p: _Pending) -> dict:
    """取一张新图（与当前 TWFID 会话绑定）。"""
    response = _get(p, f"{CAPTCHA_URL}?t={int(time.time() * 1000)}")
    media = response.headers.get("content-type", "image/gif").split(";")[0].strip()
    return {
        "image_b64": base64.b64encode(response.content).decode("ascii"),
        "media_type": media or "image/gif",
        "len": len(response.content),
    }


def _prepare(p: _Pending) -> dict:
    """走完登录页的前置三步，拿到 TwfID / CSRF_RAND_CODE / RSA 公钥，并出一张图。"""
    _get(p, ORIGIN + "/portal/")
    initial = auth_fields(_get(p, INIT).content)
    config = auth_fields(_get(p, CONFIG).content)
    fresh = auth_fields(_get(p, INIT).content)  # 提交前刷新一次全局参数（官方 authPsw 行为）
    if any(item.get("ErrorCode") != "1" for item in (initial, config, fresh)):
        raise _NeedCaptcha("webvpn_init_failed")
    p.fresh = fresh
    p.sid = fresh.get("TwfID", "")
    # 深信服是按 IP / 风险自适应决定要不要图形码的：服务器出口常常被判要，
    # 家宽常常不要。所以别写死，按学校这次真实返回的来决定出不出图。
    needs_captcha = (fresh.get("RndImg") != "0") or (config.get("USE_RAND_CODE") != "0")
    p.needs_captcha = needs_captcha
    return {
        "token": p.token,
        "needs_captcha": needs_captcha,
        "captcha": _captcha_image(p) if needs_captcha else None,
        "interval_seconds": _TTL,
    }


def start(user_id: str, user: str, password: str) -> dict:
    """开一次登录：给前端一张验证码图。"""
    with _lock:
        _sweep()
        old = _pending.pop(user_id, None)
        if old:
            old.close()
        p = _Pending(user_id)
        p.user, p.password = user, password
        try:
            info = _prepare(p)
        except Exception:
            p.close()
            raise
        _pending[user_id] = p
        return info


def refresh_captcha(user_id: str, token: str) -> dict:
    with _lock:
        p = _pending.get(user_id)
        if not p or p.token != token:
            raise _NeedCaptcha("expired")
        return {"token": p.token, "captcha": _captcha_image(p)}


def _state_of(p: _Pending) -> dict:
    cookies = []
    for c in p.client.cookies.jar:
        cookies.append({
            "name": c.name,
            "value": c.value,
            "domain": c.domain,
            "path": c.path,
            "expires": c.expires if c.expires else -1,
            "secure": bool(c.secure),
            "httpOnly": bool(c._rest.get("HttpOnly")),  # noqa: SLF001 - http.cookiejar 的公开约定字段
        })
    state = {"cookies": cookies, "origins": [], "webvpn_catalog_verified": True}
    try:
        route, _meta = oa_route(_get(p, CONF).content, _get(p, RESOURCES).content)
        if route:
            state["webvpn_oa_route"] = route
    except Exception:  # noqa: BLE001 - 目录拿不到不影响会话本身
        pass
    return state


def _finish(user_id: str, p: _Pending) -> dict:
    from .tenancy import tenant_scope

    state = _state_of(p)
    if not state["cookies"]:
        raise _NeedCaptcha("no_cookies")
    with tenant_scope(user_id) as tenant:
        vault = tenant.vault()
        vault.save("webvpn", state)
        try:
            p_store = vault.key_store
            if callable(p_store):
                p_store = p_store()
            p_store.set_password("webvpn", p.user.strip(), p.password)
        except Exception:  # noqa: BLE001 - 存不进去不影响本次登录
            pass
    with _lock:
        _pending.pop(user_id, None)
    p.close()
    return {"ok": True, "cookies": len(state["cookies"]),
            "has_route": "webvpn_oa_route" in state}


def submit_captcha(user_id: str, token: str, captcha: str) -> dict:
    """提交图码（顺带提交密码）。返回 `need: totp` 表示还要六位动态码。"""
    with _lock:
        p = _pending.get(user_id)
        if not p or p.token != token:
            raise _NeedCaptcha("expired")
    code = (captcha or "").strip()
    if not code and p.needs_captcha:
        raise WrongCaptcha("先把图里的验证码填上")
    try:
        nonce, encrypted = encrypt_password(p.fresh, p.password)
        reply = _post(p, PASSWORD, {
            "mitm_result": "",
            "svpn_req_randcode": nonce,
            "svpn_name": p.user.strip(),
            "svpn_password": encrypted,
            "svpn_rand_code": code,
        })
    except _NeedCaptcha:
        raise
    error = int(reply.get("ErrorCode", "-1"))
    if error in GRAPHICAL_CODES:
        raise WrongCaptcha("验证码不对或已过期")
    if error not in {1, 2, 20021}:
        raise WrongCaptcha(f"学校没接受这次登录（错误码 {error}），检查学号、密码和验证码")
    service = reply.get("NextService", "")
    if not service and error in {1, 20021}:
        p.sid = reply.get("TwfID") or p.sid
        return _finish(user_id, p)
    if service == "auth/token" and reply.get("NextServiceSubType") == "totp":
        if reply.get("TotpStatus") != "Bind":
            raise WrongCaptcha("这个账号还没按学校流程绑定令牌，先在学校页面绑好再来")
        p.sid = reply.get("TwfID") or p.sid
        return {"ok": False, "need": "totp", "token": p.token,
                "hint": "打开令牌 App，把当前的六位动态码填进来"}
    raise WrongCaptcha("学校要求了别的认证步骤，请在浏览器里直接登录 WebVPN")


def submit_totp(user_id: str, token: str, code: str) -> dict:
    with _lock:
        p = _pending.get(user_id)
        if not p or p.token != token:
            raise _NeedCaptcha("expired")
    value = (code or "").strip()
    if len(value) != 6 or not value.isdigit():
        raise WrongCaptcha("动态码是六位数字")
    reply = _post(p, TOTP, {"twfid": p.sid, "svpn_inputtoken": value})
    error = int(reply.get("ErrorCode", "-1"))
    if error in GRAPHICAL_CODES or error not in {1, 2, 20021}:
        raise WrongCaptcha(f"动态码不对或已过期（错误码 {error}），等下一个码再试")
    if reply.get("NextService"):
        raise WrongCaptcha("学校还要一步验证，请在浏览器里直接登录 WebVPN")
    return _finish(user_id, p)


def abort(user_id: str) -> None:
    with _lock:
        p = _pending.pop(user_id, None)
    if p:
        p.close()


# 供路由层翻译异常
class CaptchaError(Exception):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def wrong_captcha(exc: Exception) -> CaptchaError:
    text = str(exc) or "验证码不对"
    if text == "expired":
        return CaptchaError("webvpn_login_expired", "这次登录等太久了，重新点一次「连 WebVPN」。")
    return CaptchaError("captcha_rejected", text)
