"""Silent re-login for the school sources.

stu-mcp writes the school account and password into the tenant's own encrypted
keyring (`credentials.enc`, wrapped by the tenant's `master.key`) as part of the
bind flow. It used to be stored and never used again, so the moment 教务 dropped
the session a sync could only report ``login_expired`` and wait for the user to
reconnect by hand. This module turns that stored credential into a one-shot
re-login: rebuild the session, then let the caller retry the read.

Nothing leaves the tenant: the password is decrypted in-process, passed straight
to stu-mcp's login, and never logged, returned over the API, or written anywhere
else. If the school asks for a captcha / TOTP / QR scan, we stop instead of
guessing — that is a human decision.
"""
from __future__ import annotations

from .stucompat import install as _install_patches

_install_patches()


class _NoChallenge:
    """Any human-only challenge aborts the silent login with a clear code."""

    def __getattr__(self, name):
        def _ask(*_args, **_kwargs):
            from stu_mcp.runtime import AppError

            raise AppError("interaction_required", "学校登录要求人工验证（验证码/动态码），请重新连接。")

        return _ask


def stored_accounts(vault, service: str) -> list[tuple[str, str]]:
    """(account, password) pairs kept for this source in the tenant keyring."""
    store = vault.key_store
    if callable(store):  # stu-mcp exposes it as a method, not a property
        store = store()
    reader = getattr(store, "_read", None)
    if reader is None:
        return []
    try:
        data = reader()
    except Exception:  # noqa: BLE001 - unreadable keyring just means "no stored account"
        return []
    # FileKeyStore._read() 直接返回 entries（service -> {账号: 密码}），
    # 有的版本会连外层 {"version","entries"} 一起给，两种形状都吃。
    if isinstance(data, dict) and "entries" in data and isinstance(data.get("entries"), dict):
        data = data["entries"]
    accounts = ((data or {}).get(service) or {}) if isinstance(data, dict) else {}
    return [(user, pw) for user, pw in accounts.items()
            if isinstance(user, str) and isinstance(pw, str) and user and pw]


# 这些错误码不代表"密码错"，而是"这次网络没问清"：可以安全地重试一次，
# 因为登录本身是幂等的（同一账号再登一次只会刷新会话）。
_RETRYABLE = {
    "authentication_outcome_unknown",
    "authentication_network_error",
    "request_timeout",
    "network_error",
}


def session_live(vault, service: str) -> bool:
    """登录之后再核一次：真的能读到东西才算成功。

    学校登录流程常常是"请求发出去了、响应没回来"，上游就报
    authentication_outcome_unknown。用户看到的是"没能确认登录结果"，
    但其实会话可能已经建立。这里直接拿会话去请求一次真实页面来判断。
    """
    try:
        state = vault.load(service) or {}
        cookies = {str(c["name"]): str(c["value"]) for c in state.get("cookies", []) if c.get("name")}
        if not cookies:
            return False
        import httpx

        probe = {
            "jw": "http://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do",
            "mystu": "https://my.stu.edu.cn/v3/services/api/user/validate?ver=1.1",
        }.get(service)
        if not probe:
            return True
        with httpx.Client(timeout=20, verify=False, follow_redirects=True, cookies=cookies,
                          headers={"User-Agent": "Mozilla/5.0 Chrome/131.0.0.0"}) as client:
            res = client.get(probe)
            if res.status_code != 200:
                return False
            if service == "jw":
                return 'id="timetable"' in res.text
            return '"displayName"' in res.text or "displayName" in res.text
    except Exception:  # noqa: BLE001 - 探测失败不改变结论
        return False


def login_with(vault, service: str, account: str, password: str) -> None:
    """Log one source in with credentials the caller just supplied.

    Used by POST /v1/relogin: the password arrives from the user's own device,
    is used in this call only, and is never written to the database or a log.

    关键行为：上游报"结果未确认"时，不直接判失败，而是用刚拿到的会话去核一次。
    核通了就算成功——这正是用户反馈"一直没确认登录结果"的根因。
    """
    from stu_mcp.auth_http import Credentials, login_http
    from stu_mcp.runtime import AppError

    try:
        login_http(vault, service, credentials=Credentials(account, password),
                   interaction=_NoChallenge(), allow_http_callback=True)
        return
    except AppError as exc:
        if getattr(exc, "code", "") not in _RETRYABLE:
            raise
        first = exc
    except Exception as exc:  # noqa: BLE001 - 网络层异常同样按"未确认"处理
        first = exc

    # 先核一次：可能其实已经登上去了
    if session_live(vault, service):
        return

    # 真的没登上，再重试一次（幂等），仍失败才把错误交给界面
    login_http(vault, service, credentials=Credentials(account, password),
               interaction=_NoChallenge(), allow_http_callback=True)
    if not session_live(vault, service):
        raise first if isinstance(first, AppError) else AppError(
            "authentication_outcome_unknown", "登录没有完成，请再试一次。", service)


def relogin(vault, service: str) -> bool:
    """Try every stored account for `service`; True as soon as one session lands."""
    from stu_mcp.auth_http import Credentials, login_http

    for username, password in stored_accounts(vault, service):
        try:
            login_http(vault, service, credentials=Credentials(username, password),
                       interaction=_NoChallenge(), allow_http_callback=True)
            return True
        except Exception:  # noqa: BLE001 - wrong account / challenge / network: try none else
            continue
    return False


def relogin_auto(vault, service: str = "webvpn") -> bool:
    """配过令牌密钥时，让 stu-mcp 自己滚六位动态码去重登，全程不问人。

    这是「输一次就长期保持」的正路：`WebVPNConfig.configure()` 把账号、密码和
    otpauth 密钥一起加密存进本租户的凭据存储；之后会话过期时，
    `login_http(credentials=None)` 会自己取那份配置并算码，不需要用户在场。

    没配过就立刻返回 False，绝不去碰凭据。
    """
    if service != "webvpn":
        return False
    try:
        from stu_mcp.webvpn import WebVPNConfig

        if not WebVPNConfig(vault).marker().get("configured"):
            return False
    except Exception:  # noqa: BLE001 - 配置读不出来就当没配
        return False

    from stu_mcp.auth_http import login_http

    try:
        # credentials=None：让 stu-mcp 用保存的那份（含 TOTP 密钥）自己算码
        login_http(vault, service, credentials=None,
                   interaction=_NoChallenge(), allow_http_callback=True)
        return True
    except Exception:  # noqa: BLE001 - 图形码 / 密码策略 / 网络：交回上层判 need_input
        return False
