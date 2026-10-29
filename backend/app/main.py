"""HTTP API. Thin layer: validate, delegate, shape the response.

Design rules carried over from PLAN.md:
  * the App never sees a raw stack trace or an internal code name;
  * every sync answer carries the B-machine code so the UI can branch;
  * no endpoint returns the school password, ever.
"""
from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field, field_validator

from . import auth as authlib
from . import demo
from . import version as versionlib
from .bind import BINDABLE, bind_manager
from .collectors import CODE_MAINTENANCE, CODE_NEED_INPUT, CODE_OK
from .config import settings
from .db import DB, now
from .push.router import build_router
from .stucompat import install as install_compat_patches
from .ratelimit import bind_limiter, login_account_limiter, login_ip_limiter, register_limiter
from .sync import is_syncing, start_sync
from .tenancy import purge_tenant

log = logging.getLogger("stu_app")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

if not settings.demo:
    # Refuse to start with an empty or short signing secret: every token would
    # be forgeable. Demo mode generates a throwaway one (config._jwt_secret).
    settings.validate()

# scrypt 注册/登录是 CPU 密集（2^14 迭代），FastAPI 默认线程池只有 40 个槽位。
# 数千人同时注册时会被这里排队卡住，把槽位放大到 120（4 核够用）。
try:
    import anyio.to_thread

    anyio.to_thread.current_default_thread_limiter().total_tokens = 120
except Exception:  # noqa: BLE001 - 老版本 anyio 没有这个接口
    pass

install_compat_patches()

app = FastAPI(title="STU 事项助手", version="0.2.0", docs_url="/v1/docs" if settings.demo else None,
              redoc_url=None, openapi_url="/v1/openapi.json" if settings.demo else None)

# The browser version is served from this same origin and needs no CORS. Only
# the packaged App's WebView origins are allowed; tokens travel in the
# Authorization header, never in cookies.
from . import keepalive as _keepalive  # noqa: E402  WebVPN 会话保活


@app.on_event("startup")
def _start_webvpn_keepalive() -> None:
    """用户输过一次数盾动态码之后，靠后台巡检把 WebVPN 会话续住。"""
    _keepalive.start()


# /v1/webvpn/state 要认人，注册在下面的 current_user 之后（不能整包吐给任意登录用户）。


app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.cors_origins),
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["Authorization", "Content-Type", "Accept"],
    max_age=86400,
)


@app.middleware("http")
async def _security_headers(request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    response.headers.setdefault("X-Frame-Options", "SAMEORIGIN")
    if request.url.path.startswith("/v1/"):
        response.headers.setdefault("Cache-Control", "no-store")
    return response


# --------------------------------------------------------------------------
# auth dependency
# --------------------------------------------------------------------------

def current_user(authorization: str = Header(default="")) -> str:
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "missing_bearer")
    uid = authlib.verify_token(authorization[7:].strip())
    if not uid:
        raise HTTPException(401, "invalid_token")
    db = DB(settings.db_path)
    if not db.user_by_id(uid):
        raise HTTPException(401, "user_gone")
    return uid


# 实时活动推送（Huawei / OPPO 实况窗）。注册在 /v1 通配 404 之前，路由才不会被兜住。
app.include_router(build_router(current_user))


@app.get("/v1/webvpn/state")
def webvpn_state(uid: str = Depends(current_user)) -> dict:
    """本人 WebVPN 会话状态：ok / need_input。

    只回本人这一条——保活状态里存的是全部租户，整包外发等于泄露别人的流水。
    前端据此决定通知页要不要提示「点这里现输一次数盾动态码」。
    """
    rec = _keepalive.state().get(uid) or {}
    return {
        "ok": bool(rec.get("ok")),
        "need_input": bool(rec.get("need_input")),
        "detail": rec.get("detail", ""),
        "at": rec.get("at", ""),
        "interval_minutes": _keepalive.INTERVAL_MIN,
    }


@app.post("/v1/webvpn/connect")
def webvpn_connect(uid: str = Depends(current_user)) -> dict:
    """旧入口（1.0.26/27）：走 stu-mcp 的协议版自动登录。

    留着只为老客户端不 404。学校现在开了图形验证码，协议版在提交密码之前就放弃
    （RndImg=1 / USE_RAND_CODE=1），这条路基本走不通——新客户端走
    `/v1/webvpn/login/*` 那组，人工看图形码 + 现输动态码。
    """
    job = bind_manager.start_webvpn(uid)
    return job.snapshot() | {"job_id": job.id}


# --------------------------------------------------------------------------
# WebVPN 人工验证登录（图形码 + 数盾动态码）
# --------------------------------------------------------------------------

class WebvpnLoginIn(BaseModel):
    student_id: str | None = Field(default=None, max_length=40)
    password: str | None = Field(default=None, max_length=200)


class WebvpnCaptchaIn(BaseModel):
    token: str = Field(min_length=4, max_length=64)
    captcha: str = Field(min_length=1, max_length=12)


class WebvpnCodeIn(BaseModel):
    token: str = Field(min_length=4, max_length=64)
    code: str = Field(min_length=2, max_length=12)


def _stored_school_account(uid: str) -> tuple[str, str] | None:
    from .relogin import stored_accounts
    from .tenancy import tenant_scope

    with tenant_scope(uid) as tenant:
        vault = tenant.vault()
        for svc in ("webvpn", "jw", "mystu"):
            accounts = stored_accounts(vault, svc)
            if accounts:
                return accounts[0]
    return None


@app.post("/v1/webvpn/login/start")
def webvpn_login_start(body: WebvpnLoginIn, uid: str = Depends(current_user)) -> dict:
    """开始一次带人工验证的 WebVPN 登录：先给他一张图形验证码。

    学号密码优先用服务器上存着的那份（绑 jw/MySTU 时存下的）；
    没存过就必须在请求里带上，否则 409 need_credentials。
    """
    from . import webvpn_login as wv

    user = (body.student_id or "").strip()
    password = body.password or ""
    if not user or not password:
        stored = _stored_school_account(uid)
        if not stored:
            raise HTTPException(409, "need_credentials")
        user, password = stored
    try:
        info = wv.start(uid, user, password)
    except Exception:  # noqa: BLE001 - 学校入口打不开
        raise HTTPException(502, "webvpn_unreachable") from None
    return {"ok": True, "stage": "captcha", **info}


@app.get("/v1/webvpn/login/captcha")
def webvpn_login_new_captcha(token: str = Query(min_length=4, max_length=64),
                             uid: str = Depends(current_user)) -> dict:
    """换一张验证码图（看不清或超时重来）。"""
    from . import webvpn_login as wv

    try:
        return {"ok": True, **wv.refresh_captcha(uid, token)}
    except Exception:  # noqa: BLE001
        raise HTTPException(410, "webvpn_login_expired") from None


@app.post("/v1/webvpn/login/captcha")
def webvpn_login_captcha(body: WebvpnCaptchaIn, uid: str = Depends(current_user)) -> dict:
    """交图形验证码。回 ok / need=totp / 图码不对（同时附一张新图，让他立刻重输）。"""
    from . import webvpn_login as wv

    try:
        return wv.submit_captcha(uid, body.token, body.captcha)
    except wv.WrongCaptcha as exc:
        out: dict[str, Any] = {"ok": False, "code": "captcha_rejected", "message": str(exc)}
        try:
            out.update({"stage": "captcha", **wv.refresh_captcha(uid, body.token)})
        except Exception:  # noqa: BLE001 - 会话过期就别硬撑
            out["code"] = "webvpn_login_expired"
            out["message"] = "这次登录等太久了，重新点一次「连 WebVPN」。"
        return out


@app.post("/v1/webvpn/login/totp")
def webvpn_login_totp(body: WebvpnCodeIn, uid: str = Depends(current_user)) -> dict:
    """交数盾六位动态码。错了就让他等下一个码再输，会话不销毁。"""
    from . import webvpn_login as wv

    try:
        return wv.submit_totp(uid, body.token, body.code)
    except wv.WrongCaptcha as exc:
        return {"ok": False, "code": "totp_rejected", "message": str(exc), "token": body.token}


@app.post("/v1/webvpn/login/abort")
def webvpn_login_abort(uid: str = Depends(current_user)) -> dict:
    from . import webvpn_login as wv

    wv.abort(uid)
    return {"ok": True}


# --------------------------------------------------------------------------
# WebVPN 自动续期（导入数盾令牌密钥）
# --------------------------------------------------------------------------

class WebvpnSecretIn(BaseModel):
    """数盾令牌的 otpauth 地址，或纯 Base32 密钥。"""

    secret: str = Field(min_length=8, max_length=400)


@app.get("/v1/webvpn/auto")
def webvpn_auto_status(uid: str = Depends(current_user)) -> dict:
    """自动续期配置状态。configured=true 表示服务器能自己滚动态码。"""
    from stu_mcp.webvpn import WebVPNConfig

    from .tenancy import tenant_scope

    try:
        with tenant_scope(uid) as tenant:
            return WebVPNConfig(tenant.vault()).status()
    except Exception:  # noqa: BLE001
        return {"configured": False, "enabled": False, "status": "unknown"}


@app.post("/v1/webvpn/auto")
def webvpn_auto_enable(body: WebvpnSecretIn, uid: str = Depends(current_user)) -> dict:
    """把数盾令牌密钥交给服务器，之后会话过期由它自己算码续上。

    这一条是「输一次就不用再管」的关键：没有密钥时，服务器过不了学校的动态码那一关，
    每次过期都必须有人在场；有了密钥，`relogin_auto()` 就能自己滚码重登。
    凭据用本租户的 master.key 加密，只存在这个租户自己的目录里。
    """
    from stu_mcp.webvpn import WebVPNConfig

    from .tenancy import tenant_scope

    stored = _stored_school_account(uid)
    if not stored:
        raise HTTPException(409, "need_credentials")
    user, password = stored
    try:
        with tenant_scope(uid) as tenant:
            return WebVPNConfig(tenant.vault()).configure({
                "enabled": True,
                "username": user,
                "password": password,
                "totp": body.secret.strip(),
                "encoding": "auto",
            })
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001
        code = getattr(exc, "code", "") or "invalid_secret"
        raise HTTPException(400, code) from None


@app.delete("/v1/webvpn/auto")
def webvpn_auto_disable(uid: str = Depends(current_user)) -> dict:
    from stu_mcp.webvpn import WebVPNConfig

    from .tenancy import tenant_scope

    try:
        with tenant_scope(uid) as tenant:
            return WebVPNConfig(tenant.vault()).remove()
    except Exception:  # noqa: BLE001
        raise HTTPException(400, "auto_remove_failed") from None


# --------------------------------------------------------------------------
# request models
# --------------------------------------------------------------------------

class RegisterIn(BaseModel):
    email: str = Field(min_length=3, max_length=200)
    passphrase: str = Field(min_length=8, max_length=200)

    @field_validator("email")
    @classmethod
    def _email_shape(cls, value: str) -> str:
        value = value.strip().lower()
        if "@" not in value or value.startswith("@") or value.endswith("@"):
            raise ValueError("邮箱格式不正确")
        return value


class LoginIn(BaseModel):
    email: str
    passphrase: str


class BindIn(BaseModel):
    services: list[str] = Field(default_factory=lambda: list(BINDABLE))
    student_id: str = Field(min_length=3, max_length=40)
    password: str = Field(min_length=1, max_length=200)

    @field_validator("services")
    @classmethod
    def _known_services(cls, value: list[str]) -> list[str]:
        kept = [s for s in value if s in BINDABLE]
        if not kept:
            raise ValueError("至少选择一个服务")
        return kept


class BindInputIn(BaseModel):
    challenge_id: str
    value: str = Field(max_length=200)


class DeviceIn(BaseModel):
    platform: str
    token: str = Field(min_length=8, max_length=4096)
    # 装机版本：注册设备时顺手报一次，服务器才知道这台要不要催更新。
    # 老客户端不发这两个字段，照样能注册。
    app_version: str | None = Field(default=None, max_length=32)
    app_code: int | None = Field(default=None, ge=0, le=10_000_000)


# --------------------------------------------------------------------------
# auth endpoints
# --------------------------------------------------------------------------

def _client_ip(request: Request) -> str:
    # uvicorn runs with --proxy-headers behind Caddy, so this is the real peer
    return request.client.host if request.client else "unknown"


@app.post("/v1/auth/register")
def register(body: RegisterIn, request: Request) -> dict:
    # 学校/运营商 NAT 会让成百上千个学生共用同一个出口 IP，按 IP 限 40 次/小时会把
    # 正常注册全挡掉。注册本身就是低风险写操作，这里放宽到 600/小时，防的是脚本刷量。
    if not register_limiter.allow(_client_ip(request)):
        raise HTTPException(429, "too_many_attempts")
    db = DB(settings.db_path)
    if db.user_by_email(body.email):
        raise HTTPException(409, "email_taken")
    user = db.create_user(body.email, authlib.hash_password(body.passphrase))
    return {"token": authlib.issue_token(user["id"]), "user": {"id": user["id"],
                                                              "email": user["email"]}}


@app.post("/v1/auth/login")
def login(body: LoginIn, request: Request) -> dict:
    ip = _client_ip(request)
    if not login_ip_limiter.allow(ip) or not login_account_limiter.allow(f"{ip}|{body.email.strip().lower()}"):
        raise HTTPException(429, "too_many_attempts")
    db = DB(settings.db_path)
    user = db.user_by_email(body.email)
    if not user or not authlib.verify_password(body.passphrase, user["pass_hash"]):
        raise HTTPException(401, "bad_credentials")
    if user["status"] != "active":
        raise HTTPException(403, "account_disabled")
    return {"token": authlib.issue_token(user["id"]), "user": {"id": user["id"],
                                                              "email": user["email"]}}


# --------------------------------------------------------------------------
# bind (login to campus systems)
# --------------------------------------------------------------------------

@app.post("/v1/bind")
def bind_start(body: BindIn, uid: str = Depends(current_user)) -> dict:
    # Throttle repeated campus logins: the school SSO locks accounts after too
    # many failures, and a wrong password typed six times should stop here.
    if not bind_limiter.allow(uid):
        raise HTTPException(429, "login_throttled")
    # 连 WebVPN 就是要去读 OA：没有本人授权，这一步不往下走。
    # 数盾动态口令仍然要由本人在下一步现输（bind.py 的 totp challenge），
    # 服务器不代输、不保存。
    if "webvpn" in (body.services or []) and not DB(settings.db_path).has_consent(uid, "oa"):
        raise HTTPException(403, "consent_required")
    job = bind_manager.start(uid, body.student_id, body.password, body.services)
    return job.snapshot()


# --------------------------------------------------------------------------
# 本人授权（OA 通知）
# --------------------------------------------------------------------------

class ConsentIn(BaseModel):
    scope: str = Field(default="oa", pattern="^oa$")
    version: str = Field(default="1", max_length=8)


@app.get("/v1/consent")
def consent_list(uid: str = Depends(current_user)) -> dict:
    return {"consents": DB(settings.db_path).consents_of(uid)}


@app.post("/v1/consent")
def consent_grant(body: ConsentIn, uid: str = Depends(current_user)) -> dict:
    """记录「本人同意」这件事：谁、什么范围、什么时候、同意的文案版本。

    只记这三样，不记 IP、不记设备、不记任何生物特征。
    """
    record = DB(settings.db_path).grant_consent(uid, body.scope, body.version)
    log.info("CONSENT %s scope=%s v=%s", uid[:8], body.scope, body.version)
    return {"ok": True, **record}


@app.delete("/v1/consent")
def consent_withdraw(scope: str = Query(default="oa"), uid: str = Depends(current_user)) -> dict:
    DB(settings.db_path).withdraw_consent(uid, scope)
    return {"ok": True, "withdrawn": scope}


@app.get("/v1/bind/{job_id}")
def bind_state(job_id: str, uid: str = Depends(current_user)) -> dict:
    job = bind_manager.get(job_id, uid)
    if job is None:
        raise HTTPException(404, "job_not_found")
    return job.snapshot()


@app.post("/v1/relogin")
def relogin_now(body: BindIn, request: Request, uid: str = Depends(current_user)) -> dict:
    """Re-authenticate with a password the user's own device kept.

    学校会话会自己过期。密码留在设备上，掉线时由设备发这一次重登请求；服务端只在
    这个请求的内存里用一次，不写库、不进日志、不回传。
    """
    if not login_account_limiter.allow(body.student_id) or not login_ip_limiter.allow(_client_ip(request)):
        raise HTTPException(429, "login_throttled")
    from .relogin import login_with
    from .tenancy import tenant_scope

    done: list[str] = []
    errors: dict[str, str] = {}
    with tenant_scope(uid) as tenant:
        vault = tenant.vault()
        for service in body.services:
            try:
                login_with(vault, service, body.student_id, body.password)
                # 重连成功后同样把密码加密留在租户 keyring 里，下次过期服务端自己续
                from .bind import _remember_credentials

                _remember_credentials(vault, service, body.student_id, body.password)
                done.append(service)
            except Exception as exc:  # noqa: BLE001 - report per service
                errors[service] = getattr(exc, "code", None) or type(exc).__name__
    if not done:
        raise HTTPException(401, "login_failed")
    # 登录刚成功，立刻同步一轮：否则 /v1/status 里还是上一次的 need_login，
    # App 顶部那条"断联请重新登录"的提示就不会消失。
    try:
        from .sync import start_sync

        start_sync(uid, list(done), force=True)
    except Exception:  # noqa: BLE001 - 同步排队失败也不影响"已登录"这个结论
        pass
    return {"ok": True, "services": done, "errors": errors}


@app.post("/v1/bind/{job_id}/input")
def bind_input(job_id: str, body: BindInputIn, uid: str = Depends(current_user)) -> dict:
    job = bind_manager.get(job_id, uid)
    if job is None:
        raise HTTPException(404, "job_not_found")
    job.submit(body.challenge_id, body.value)
    return job.snapshot()


@app.delete("/v1/bind/{job_id}")
def bind_cancel(job_id: str, uid: str = Depends(current_user)) -> dict:
    job = bind_manager.get(job_id, uid)
    if job is None:
        raise HTTPException(404, "job_not_found")
    job.cancel()
    return job.snapshot()


@app.get("/v1/bind")
def bind_status(uid: str = Depends(current_user)) -> dict:
    db = DB(settings.db_path)
    return {"bound": db.bound_services(uid), "bindable": list(BINDABLE),
            "demo": settings.demo}


@app.get("/v1/me")
def me(uid: str = Depends(current_user)) -> dict:
    """Who is signed in, and which campus services are connected."""
    db = DB(settings.db_path)
    user = db.user_by_id(uid) or {}
    return {
        "id": uid,
        "email": user.get("email", ""),
        "created_at": user.get("created_at"),
        "bound": sorted(b["service"] for b in db.bound_services(uid)),
        "demo": settings.demo,
    }


# --------------------------------------------------------------------------
# sync
# --------------------------------------------------------------------------

@app.post("/v1/sync")
def sync(force: bool = Query(default=False),
         sources: str = Query(default="public,jw,mystu"),
         uid: str = Depends(current_user)) -> dict:
    """Start a background sync and return at once; poll /v1/status for progress."""
    wanted = [s.strip() for s in sources.split(",") if s.strip() in {"public", "jw", "mystu"}]
    if settings.demo:
        _seed_demo(uid)
        return {"demo": True, "started": False, "syncing": False}
    started = start_sync(uid, wanted, force=force)
    return {"demo": False, "started": started, "syncing": True}


def _seed_demo(uid: str) -> None:
    from .sync import _flatten_timetable

    db = DB(settings.db_path)
    db.replace_kind(uid, "jw", "timetable", _flatten_timetable([demo.demo_timetable()]))
    db.upsert_items(uid, "public", "notice", demo.demo_notices())
    db.upsert_items(uid, "jw", "grade", demo.demo_grades())
    db.upsert_items(uid, "jw", "exam", demo.demo_exams())
    db.upsert_items(uid, "mystu", "task", demo.demo_tasks())
    for source in ("public", "jw", "mystu"):
        db.finish_run(db.start_run(uid, source), CODE_OK, "演示数据")


# --------------------------------------------------------------------------
# read endpoints
# --------------------------------------------------------------------------

@app.get("/v1/feed")
def feed(kinds: str = Query(default=""),
         since: float | None = Query(default=None),
         limit: int = Query(default=60, ge=1, le=200),
         uid: str = Depends(current_user)) -> dict:
    db = DB(settings.db_path)
    if settings.demo and not db.feed(uid, limit=1):
        _seed_demo(uid)
    kind_list = [k.strip() for k in kinds.split(",") if k.strip()] or None
    rows = db.feed(uid, kinds=kind_list, since=since, limit=limit)
    return {"items": [_shape_item(r) for r in rows], "count": len(rows)}


def _shape_item(row: dict) -> dict:
    payload = row.get("payload") or {}
    return {
        "id": row["id"],
        "kind": row["kind"],
        "source": row["source"],
        "title": row.get("title") or payload.get("title") or "",
        "updated_at": row["updated_at"],
        "detail": {k: v for k, v in payload.items()
                   if k not in {"external_id", "id"}},
    }


@app.get("/v1/timetable")
def timetable(semester: str = Query(default=""), uid: str = Depends(current_user)) -> dict:
    db = DB(settings.db_path)
    if settings.demo and not db.items_of(uid, "timetable"):
        _seed_demo(uid)
    docs = db.items_of(uid, "timetable", limit=20)
    if not docs:
        return {"semester": semester, "courses": [], "empty": True}
    if semester:
        match = [d for d in docs if d["payload"].get("semester") == semester]
        doc = (match or docs)[0]["payload"]
    else:
        doc = docs[0]["payload"]
    return {
        "semester": doc.get("semester", ""),
        "status": doc.get("status", "ok"),
        "checked_at": doc.get("checked_at"),
        "courses": doc.get("courses", []),
        "unarranged": doc.get("unarranged", []),
        "unplaced_details": doc.get("unplaced_details", []),
        "pending": doc.get("pending", []),
        "coverage": doc.get("coverage", {}),
        "empty": False,
    }


@app.get("/v1/bus")
def bus(uid: str = Depends(current_user)) -> dict:
    """到桑浦山校区的公交到站：有上游就用实时，没有就按班次推算。"""
    from .bus import snapshot

    return snapshot()


@app.get("/v1/notices")
def notices(limit: int = Query(default=10, ge=1, le=200),
            uid: str = Depends(current_user)) -> dict:
    """学校官网消息（2026-10-29 起只管这个）。

    OA 那条线整体下线：不再读 OA、不再要 WebVPN 会话、不再要本人授权。
    这里返回的就是学校官网各公开栏目抓到的那几条。
    """
    limit = min(limit, 10)
    db = DB(settings.db_path)
    if settings.demo and not db.items_of(uid, "notice"):
        _seed_demo(uid)
    rows = db.items_of(uid, "notice", limit=limit)
    return {"items": [_shape_item(r) for r in rows], "count": len(rows), "daily_cap": 10}


@app.get("/v1/facts")
def daily_facts(uid: str = Depends(current_user)) -> dict:
    """今天 5 条冷知识：搞笑、有趣、鲜为人知。同一天固定，隔天换一批。"""
    from . import facts as factslib

    try:
        rows = factslib.daily(uid)
    except Exception as exc:  # noqa: BLE001 - 冷知识不该让通知页崩
        log.warning("facts 生成失败 %s", type(exc).__name__)
        return {"day": "", "items": [], "count": 0, "error": "facts_unavailable"}
    return factslib.payload_json(rows)


@app.get("/v1/grades")
def grades(semester: str = Query(default=""), uid: str = Depends(current_user)) -> dict:
    db = DB(settings.db_path)
    if settings.demo and not db.items_of(uid, "grade"):
        _seed_demo(uid)
    rows = db.items_of(uid, "grade", source="jw", limit=200)
    if semester:
        rows = [r for r in rows if r["payload"].get("semester") == semester]
    return {"rows": [_shape_item(r)["detail"] for r in rows], "count": len(rows)}


@app.get("/v1/exams")
def exams(semester: str = Query(default=""), uid: str = Depends(current_user)) -> dict:
    db = DB(settings.db_path)
    if settings.demo and not db.items_of(uid, "exam"):
        _seed_demo(uid)
    rows = db.items_of(uid, "exam", source="jw", limit=200)
    if semester:
        rows = [r for r in rows if r["payload"].get("semester") == semester]
    return {"rows": [_shape_item(r)["detail"] for r in rows], "count": len(rows)}


@app.get("/v1/status")
def status(uid: str = Depends(current_user)) -> dict:
    """Per-source health, using the B-machine code vocabulary."""
    db = DB(settings.db_path)
    out = []
    for source in ("public", "jw", "mystu"):
        last = db.last_run(uid, source)
        out.append({
            "source": source,
            "code": last["code"] if last else None,
            "state": _code_text(last["code"]) if last else "never",
            "detail": (last or {}).get("detail", ""),
            # 排队中：这一轮没拿到执行槽位，App 上显示"排队中"而不是失败
            "queued": "queued" in str((last or {}).get("detail", "")),
            "at": last["started_at"] if last else None,
            "items": (last or {}).get("items_seen", 0),
        })
    return {"sources": out, "bound": db.bound_services(uid), "demo": settings.demo,
            "syncing": is_syncing(uid)}


def _code_text(code: int | None) -> str:
    return {
        CODE_OK: "ok",
        CODE_MAINTENANCE: "maintenance",
        CODE_NEED_INPUT: "need_login",
        1: "failed",
        4: "keystore",
        -1: "running",
    }.get(code, "unknown")


# --------------------------------------------------------------------------
# devices / account
# --------------------------------------------------------------------------

@app.post("/v1/devices")
def add_device(body: DeviceIn, uid: str = Depends(current_user)) -> dict:
    if body.platform not in {"ios", "android"}:
        raise HTTPException(400, "bad_platform")
    name, code = versionlib.parse_pair(body.app_version, body.app_code)
    DB(settings.db_path).add_device(uid, body.platform, body.token, name, code)
    # 顺手告诉这台设备：你装的是不是旧版。客户端拿到 update 就直接弹提示。
    return {"ok": True, "update": versionlib.hint_for(name, code)}


@app.delete("/v1/devices")
def del_device(body: DeviceIn, uid: str = Depends(current_user)) -> dict:
    DB(settings.db_path).remove_device(uid, body.token)
    return {"ok": True}


@app.post("/v1/account/logout")
def logout(uid: str = Depends(current_user)) -> dict:
    # Sessions stay on disk (they belong to the campus systems); only the App
    # token dies, because the App holds no server-side session state.
    return {"ok": True}


@app.delete("/v1/account")
def delete_account(uid: str = Depends(current_user)) -> dict:
    purge_tenant(uid)
    DB(settings.db_path).delete_user(uid)
    return {"ok": True}


# --------------------------------------------------------------------------
# charging (public, shared cache). Realtime bus arrivals are deliberately not
# served: the only provider found (车来了) forbids third-party access, and a
# made-up countdown must never reach a student waiting at a stop.
# --------------------------------------------------------------------------

@app.get("/v1/charging")
def charging_overview() -> dict:
    """Every registered device in one call, served from a shared 60 s cache."""
    from . import charging

    if not charging.has_client():
        raise HTTPException(503, "charging_backend_missing")
    return {
        "devices": charging.overview(),
        # 清单口径跟着 charging.py 走，别再在这里写死日期和台数
        "scope": f"已核验 {len(charging.DEVICES)} 台设备（{charging.REGISTRY_VERIFIED_ON}），不代表全校",
        "verified_on": charging.REGISTRY_VERIFIED_ON,
        "method": charging.REGISTRY_METHOD,
        "provider": "第三方充电平台",
    }


@app.get("/v1/charging/devices")
def charging_devices(q: str = Query(default="")) -> dict:
    from . import charging

    return {"devices": charging.search(q), "live": charging.has_client()}


@app.get("/v1/charging/{code}")
def charging_ports(code: str) -> dict:
    from . import charging

    dev = charging.BY_CODE.get(code)
    if dev is None:
        raise HTTPException(404, "unknown_device")
    try:
        state = charging.cached_ports(code)
    except ImportError:
        raise HTTPException(503, "charging_backend_missing") from None
    except Exception as exc:  # noqa: BLE001 - upstream failures are expected
        raise HTTPException(502, getattr(exc, "code", "charging_unavailable")) from None
    return {"device": dev, "state": state, "state_labels": charging.STATE_LABELS}


@app.get("/v1/health")
def health() -> dict:
    return {"ok": True, "ts": now(), "demo": settings.demo}


@app.get("/v1/meta/version")
def meta_version(name: str | None = Query(default=None), code: int | None = Query(default=None)) -> dict:
    """公开的版本登记：最新版本是什么、我这一版要不要更新。

    不要求登录——装在手机里的旧包如果登录流程本身坏了，也得能先看到更新提示。
    服务端登记在 `app/latest_version.json`，发版时用 `python -m app.version bump` 改。
    """
    cur = versionlib.latest()
    client_name, client_code = versionlib.parse_pair(name, code)
    hint = versionlib.hint_for(client_name, client_code)
    return {
        "latest": {
            "versionName": cur["versionName"],
            "versionCode": cur["versionCode"],
            "apk": cur.get("apk") or "/download/stu-assistant.apk",
            "notes": cur.get("notes") or [],
            "mandatory": bool(cur.get("mandatory")),
            "releasedAt": cur.get("releasedAt"),
        },
        "update": hint,
        "outdated": hint is not None,
    }


@app.api_route("/v1/{rest:path}", methods=["GET", "POST", "DELETE"], include_in_schema=False)
def api_not_found(rest: str) -> None:
    # Unknown API paths must answer JSON 404, never fall through to index.html.
    raise HTTPException(404, "not_found")


# --------------------------------------------------------------------------
# the browser version (web/dist), same origin as the API
# --------------------------------------------------------------------------

if settings.web_dir and settings.web_dir.is_dir():
    import mimetypes

    from fastapi.responses import FileResponse

    mimetypes.add_type("application/vnd.android.package-archive", ".apk")
    mimetypes.add_type("application/manifest+json", ".webmanifest")

    import os

    _WEB = os.path.abspath(str(settings.web_dir))
    _INDEX = os.path.join(_WEB, "index.html")

    def _inside_web(path: str) -> str | None:
        """The file a URL path names inside the web folder, or None.

        Pure path arithmetic (no filesystem resolve): `..` segments, other
        drives and absolute paths can never leave the folder, and a file that
        is briefly locked (antivirus scanning a fresh APK) still matches.
        """
        if not path or "\x00" in path:
            return None
        candidate = os.path.normpath(os.path.join(_WEB, path))
        try:
            if os.path.commonpath([_WEB, candidate]) != _WEB:
                return None
        except ValueError:  # another drive on Windows
            return None
        return candidate if os.path.isfile(candidate) else None

    @app.api_route("/{path:path}", methods=["GET", "HEAD"], include_in_schema=False)
    def web_app(path: str) -> FileResponse:
        target = _inside_web(path)
        if target:
            # hashed bundles never change; everything else must revalidate so a
            # deploy reaches users on their next visit
            immutable = path.startswith("assets/")
            cache = "public, max-age=31536000, immutable" if immutable else "no-cache"
            if target.endswith(".apk"):
                return FileResponse(target, filename=os.path.basename(target), headers={"Cache-Control": cache})
            return FileResponse(target, headers={"Cache-Control": cache})
        if path.startswith("download/"):
            raise HTTPException(404, "not_found")
        return FileResponse(_INDEX, headers={"Cache-Control": "no-cache"})
