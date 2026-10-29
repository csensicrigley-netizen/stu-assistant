"""Bind flow: turn stu-mcp's terminal-oriented login into a resumable HTTP job.

stu-mcp's login_http(vault, service, credentials=...) never asks the terminal for
the account once credentials are supplied. What it may still need from a human is
a captcha image, a TOTP code, or a QR scan. Those arrive through an
`interaction` object whose methods block until an answer exists.

This module runs the login on a worker thread and parks each challenge in a job
that the HTTP layer polls. The user's password lives only in the worker's local
variable; it is never written to the database, the job object, or a log line.
"""
from __future__ import annotations

import base64
import threading
import uuid
import time
from dataclasses import dataclass, field
from typing import Any

from .tenancy import tenant_scope

# Services the App exposes on the bind screen.
#
# webvpn 也要绑：OA 通知走 WebVPN 会话，而 WebVPN 登录会要求「数盾动态令牌」，
# 走的是同一个 challenge 通道（kind=totp），由学生本人在绑定页里现输。
# 会话与密码都落在租户自己的加密 vault 里，服务重启不用重登。
BINDABLE = ("jw", "mystu", "webvpn", "repair")

_LOGIN_SLOTS = threading.BoundedSemaphore(4)

# 「学校要你本人出手」这一类错误码。命中就不能当成失败——绑定现场本人就在，
# 应该把验证码 / 数盾六位动态码挂到 job 上等他输（POST /v1/bind/{id}/input）。
_INTERACTIVE_CODES = frozenset({
    "interaction_required",
    "auto_login_interaction_required",
    "invalid_totp",
    "captcha_required",
    "totp_required",
})


class _Cancelled(Exception):
    pass


@dataclass
class Challenge:
    id: str
    kind: str            # captcha | qr | totp
    payload: dict[str, Any]


@dataclass
class BindJob:
    id: str
    user_id: str
    services: list[str]
    state: str = "running"       # running | waiting_input | ok | failed | cancelled
    phase: str = ""
    challenges: list[Challenge] = field(default_factory=list)
    answers: dict[str, str] = field(default_factory=dict)
    result: dict[str, Any] | None = None
    error: dict[str, str] | None = None
    created_at: float = field(default_factory=time.time)
    _cv: threading.Condition = field(default_factory=threading.Condition, repr=False)
    _cancelled: bool = field(default=False, repr=False)

    # ---- called by HTTP layer -------------------------------------------

    def snapshot(self) -> dict:
        with self._cv:
            return {
                "job_id": self.id,
                "state": self.state,
                "phase": self.phase,
                "challenges": [
                    {"id": c.id, "kind": c.kind, **c.payload} for c in self.challenges
                ],
                "result": self.result,
                "error": self.error,
            }

    def submit(self, challenge_id: str, value: str) -> None:
        with self._cv:
            self.answers[challenge_id] = value
            self._cv.notify_all()

    def cancel(self) -> None:
        with self._cv:
            self._cancelled = True
            self.state = "cancelled"
            self._cv.notify_all()

    # ---- called by worker -----------------------------------------------

    def _check(self) -> None:
        if self._cancelled:
            raise _Cancelled()

    def _push_challenge(self, kind: str, **payload) -> Challenge:
        with self._cv:
            ch = Challenge(id=uuid.uuid4().hex[:12], kind=kind, payload=payload)
            self.challenges.append(ch)
            self.state = "waiting_input"
            self.phase = kind
            self._cv.notify_all()
            return ch

    def _wait_answer(self, ch: Challenge, timeout: float = 240.0) -> str:
        deadline = time.time() + timeout
        with self._cv:
            while ch.id not in self.answers:
                self._check()
                remaining = deadline - time.time()
                if remaining <= 0:
                    raise _Cancelled()
                self._cv.wait(remaining)
            value = self.answers.pop(ch.id)
            self.challenges = [c for c in self.challenges if c.id != ch.id]
            self.state = "running"
            return value

    def _settle(self, state: str, *, result: dict | None = None, error: dict | None = None) -> None:
        with self._cv:
            self.state = state
            self.result = result
            self.error = error
            self.challenges.clear()
            self._cv.notify_all()


class _Interaction:
    """Implements the duck-typed interface login_http expects."""

    def __init__(self, job: BindJob, username: str, password: str):
        self.job, self._u, self._p = job, username, password

    def credentials(self):
        from stu_mcp.auth_http import Credentials

        return Credentials(self._u, self._p)

    def captcha(self, image: bytes, *, media_type: str = "image/jpeg") -> str:
        ch = self.job._push_challenge(
            "captcha",
            image_b64=base64.b64encode(image).decode("ascii"),
            media_type=media_type,
        )
        return self.job._wait_answer(ch)

    def code(self) -> str:
        ch = self.job._push_challenge("totp", label="学校要求已绑定令牌的当前动态码")
        return self.job._wait_answer(ch)

    def show_qr(self, content: str) -> None:
        # A QR scan needs no typed answer: the login itself keeps polling.
        self.job._push_challenge("qr", content=content, label="请用手机扫码并在原 App 确认")


class BindManager:
    def __init__(self):
        self._jobs: dict[str, BindJob] = {}
        self._guard = threading.Lock()

    def get(self, job_id: str, user_id: str) -> BindJob | None:
        job = self._jobs.get(job_id)
        if job is None or job.user_id != user_id:
            return None
        return job

    def start(self, user_id: str, student_id: str, password: str,
              services: list[str] | None = None) -> BindJob:
        services = [s for s in (services or list(BINDABLE)) if s in BINDABLE]
        if not services:
            services = list(BINDABLE)
        job = BindJob(id=uuid.uuid4().hex[:16], user_id=user_id, services=services)
        with self._guard:
            # iOS 上用户常常切后台/锁屏几分钟再回来，15 分钟太短：回来时 job 已被回收，
            # 客户端只能拿到 404，界面就永远转圈。留 1 小时。
            cutoff = time.time() - 3600
            for old in [k for k, j in self._jobs.items() if j.created_at < cutoff]:
                self._jobs.pop(old, None)
            self._jobs[job.id] = job

        from .config import settings

        if settings.demo:
            # Demo server: no school system is contacted and the password is
            # dropped unused. The session is "bound" so the App can be walked
            # through end to end.
            from .db import DB

            db = DB(settings.db_path)
            for service in services:
                db.mark_bound(user_id, service, _account_hash(student_id))
            job._settle("ok", result={"bound": services, "errors": {}, "demo": True})
            return job

        t = threading.Thread(target=self._run, args=(job, student_id, password), daemon=True)
        t.start()
        return job

    def start_webvpn(self, user_id: str) -> BindJob:
        """只连 WebVPN：学号密码用本租户 keyring 里已经存着的那份，用户只现输动态码。

        为什么要这个专用入口：绑 jw/mystu 时 `_remember_credentials` 已经把学校账号
        加密存进租户 keyring 了，用户没必要再对着表单重敲一遍学号和密码。
        他真正必须亲自做的只有一件事——把数盾那六位动态码说出来。
        """
        job = BindJob(id=uuid.uuid4().hex[:16], user_id=user_id, services=["webvpn"])
        with self._guard:
            cutoff = time.time() - 3600
            for old in [k for k, j in self._jobs.items() if j.created_at < cutoff]:
                self._jobs.pop(old, None)
            self._jobs[job.id] = job
        t = threading.Thread(target=self._run_webvpn, args=(job,), daemon=True)
        t.start()
        return job

    # ---- worker ---------------------------------------------------------

    def _run_webvpn(self, job: BindJob) -> None:
        # 这个 worker 必须自己兜住所有异常：线程里抛出去等于把 job 永远扔在
        # running，界面就只能一直转「连接中…」（1.0.25 就是这么翻的船，
        # 根因是这里漏了 settings 的导入，NameError 直接把线程炸掉）。
        from .config import settings
        from .db import DB
        from .relogin import stored_accounts

        interaction = None
        try:
            db = DB(settings.db_path)
            with _LOGIN_SLOTS, tenant_scope(job.user_id) as tenant:
                vault = tenant.vault()
                accounts: list[tuple[str, str]] = []
                for svc in ("webvpn", "jw", "mystu"):
                    accounts += stored_accounts(vault, svc)
                if not accounts:
                    job._settle("failed", error={"code": "need_bind_first",
                                                 "detail": {"webvpn": "这台设备还没有存过学校账号，先绑一次教务或 MySTU。"}})
                    return
                user, pw = accounts[0]
                interaction = _Interaction(job, user, pw)
                job.phase = "login:webvpn"
                self._login_one(vault, "webvpn", user, pw, interaction)
                db.mark_bound(job.user_id, "webvpn", _account_hash(user))
                _remember_credentials(vault, "webvpn", user, pw)
            job._settle("ok", result={"bound": ["webvpn"], "errors": {}})
        except _Cancelled:
            job._settle("cancelled")
        except BaseException as exc:  # noqa: BLE001 - 连 NameError 都得落成失败，不能让界面空转
            job._settle("failed", error={"code": _error_code(exc), "detail": {"webvpn": str(exc)[:120]}})
        finally:
            interaction = None

    def _run(self, job: BindJob, student_id: str, password: str) -> None:
        from .db import DB
        from .config import settings

        db = DB(settings.db_path)
        interaction = _Interaction(job, student_id, password)
        bound: list[str] = []
        errors: dict[str, str] = {}
        try:
            # a handful of school logins at once, site-wide; the rest queue here
            with _LOGIN_SLOTS, tenant_scope(job.user_id) as tenant:
                vault = tenant.vault()
                for service in job.services:
                    job.phase = f"login:{service}"
                    try:
                        self._login_one(vault, service, student_id, password, interaction)
                        bound.append(service)
                        db.mark_bound(job.user_id, service, _account_hash(student_id))
                        # 把学校密码加密存进这个租户自己的 keyring（master.key 包裹），
                        # 之后会话过期时服务端可以自己重登，不用再问用户。
                        _remember_credentials(vault, service, student_id, password)
                    except _Cancelled:
                        job._settle("cancelled")
                        return
                    except Exception as exc:  # noqa: BLE001 - surface code to the App
                        errors[service] = _error_code(exc)
            if not bound:
                job._settle("failed", error={"code": "login_failed", "detail": errors})
                return
            job._settle("ok", result={"bound": bound, "errors": errors})
            # 登录成功就开始同步：status 里的 need_login 会立刻被 0 覆盖，
            # App 顶上那条"断联请重新登录"的提示随之消失。
            try:
                from .sync import start_sync

                start_sync(job.user_id, list(bound), force=True)
            except Exception:  # noqa: BLE001
                pass
        except Exception as exc:  # noqa: BLE001
            job._settle("failed", error={"code": _error_code(exc)})
        finally:
            interaction = None  # drop the password reference as soon as possible

    @staticmethod
    def _login_one(vault, service: str, student_id: str, password: str, interaction) -> None:
        # 先走 relogin.login_with：它在"结果未确认"时会用会话去核一次、必要时重试一次，
        # 避免把"其实登上了"误判成失败（用户看到的"一直没确认登录结果"）。
        from .relogin import login_with
        from stu_mcp.runtime import AppError

        try:
            login_with(vault, service, student_id, password)
            return
        except AppError as exc:
            # login_with 里挂的是 _NoChallenge：学校一要人工验证就抛错。
            # 但绑定这一步本来就是本人在场——WebVPN 的六位数盾动态码只能本人现输。
            # 所以这类错误不往外抛，改走「把挑战挂到 job 上等 /v1/bind/{id}/input」。
            if getattr(exc, "code", "") not in _INTERACTIVE_CODES:
                raise
        BindManager._login_one_legacy(vault, service, student_id, password, interaction)

    @staticmethod
    def _login_one_legacy(vault, service: str, student_id: str, password: str, interaction) -> None:
        from stu_mcp.auth_http import Credentials, login_http

        # Passing credentials explicitly means login_http never asks the
        # interaction for them; the interaction is then only a challenge sink
        # (captcha / qr / totp). One service at a time, so a single failure does
        # not discard the sessions that already succeeded.
        login_http(
            vault,
            service,
            credentials=Credentials(student_id, password),
            interaction=interaction,
            allow_http_callback=True,
        )


def _remember_credentials(vault, service: str, student_id: str, password: str) -> None:
    """Encrypt the school password into the tenant keyring for silent re-login.

    只在绑定/重连成功时写；文件是 Fernet 加密的 credentials.enc，密钥是同租户的
    master.key（0600）。会话失效时 app.relogin 用它重登，密码不出租户目录、
    不进数据库、不进日志。
    """
    try:
        store = vault.key_store
        if callable(store):
            store = store()
        store.set_password(service, student_id.strip(), password)
    except Exception:  # noqa: BLE001 - 存不进去不影响本次登录
        pass


def _account_hash(student_id: str) -> str:
    import hashlib

    return hashlib.sha256(student_id.strip().encode()).hexdigest()[:32]


def _error_code(exc: Exception) -> str:
    code = getattr(exc, "code", None)
    if isinstance(code, str) and code:
        return code
    name = type(exc).__name__
    return {
        "AppError": "app_error",
        "TimeoutError": "network_error",
        "ConnectError": "network_error",
    }.get(name, "unknown_error")


bind_manager = BindManager()
