"""On-demand in-memory login for school sources (zero server-side password storage).

School passwords are NEVER stored on disk (`credentials.enc` is never written)
and the server never performs silent re-login from saved credentials. When a
session expires, the client supplies the user's password for a single request
via `login_with`, which uses it in memory to establish a revocable session
cookie and drops it immediately.
"""
from __future__ import annotations

from .stucompat import install as _install_patches

_install_patches()


class _NoChallenge:
    """Any human-only challenge aborts non-interactive login with a clear code."""

    def __getattr__(self, name):
        def _ask(*_args, **_kwargs):
            from stu_mcp.runtime import AppError

            raise AppError("interaction_required", "学校登录要求人工验证（验证码/动态码），请重新连接。")

        return _ask


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
        with httpx.Client(timeout=20, verify=True, follow_redirects=True, cookies=cookies,
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

    Used by POST /v1/bind and POST /v1/relogin: the password arrives from the
    user's own device, is used in this call only, and is never written to disk,
    the database, or a log.

    关键行为：上游报"结果未确认"时，不直接判失败，而是用刚拿到的会话去核一次。
    核通了就算成功——这正是用户反馈"一直没确认登录结果"的根因。
    """
    from stu_mcp.auth_http import Credentials, login_http
    from stu_mcp.runtime import AppError

    try:
        login_http(vault, service, credentials=Credentials(account, password),
                   interaction=_NoChallenge(), allow_http_callback=True,
                   use_saved_credentials=False)
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
               interaction=_NoChallenge(), allow_http_callback=True,
               use_saved_credentials=False)
    if not session_live(vault, service):
        raise first if isinstance(first, AppError) else AppError(
            "authentication_outcome_unknown", "登录没有完成，请再试一次。", service)
