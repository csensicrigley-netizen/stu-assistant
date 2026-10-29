"""WebVPN / 教务会话保活。

爸爸 2026-10-29 的要求：用户自己输一次「数盾动态码」之后，WebVPN 要能一直在线，
不用每次都回去重新登录，通知页也就不会空窗。

做法（照抄汕头大学机那套 oa_session.py 的思路，搬进本站后端）：
  1. 每次巡检用租户自己的 WebVPN 会话去打 OA 通知列表——这一步本身就是保活，
     因为 WebVPN 的空闲计时是"有请求就不算空闲"；
  2. 真的被判过期，先用已存密码静默重登（relogin），多数情况不需要人；
  3. 学校要动态码/验证码时才停下来，把状态记成 need_input，前端据此提示本人现输一次；
  4. 状态落盘到 webvpn_state.json，进程重启不丢。

边界：密码只在进程内解密后用一次，不写日志、不出接口；动态码永远不由服务器生成。
"""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from pathlib import Path

log = logging.getLogger("stu.keepalive")

STATE_PATH = Path(os.environ.get("STU_WEBVPN_STATE", "/var/lib/stu-assistant/webvpn_state.json"))
INTERVAL_MIN = int(os.environ.get("STU_KEEPALIVE_MIN", "12"))

_lock = threading.Lock()
_started = False


def _load() -> dict:
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save(data: dict) -> None:
    try:
        STATE_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = STATE_PATH.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, STATE_PATH)
    except OSError:
        pass


def state() -> dict:
    """给 /v1/webvpn/state 用的只读快照。"""
    return _load()


def _probe(vault) -> None:
    """一次极轻的 OA 读取：既是保活，也是"会话还活着吗"的探针。

    读之前必须先确认「OA HTTP 兼容」是开的：学校 OA 的目录路由是 http / 8118，
    不开这个开关 stu-mcp 会直接抛 oa_http_compat_required，
    看起来像"会话过期"，其实会话好好的。
    """
    from stu_mcp.features.notices.adapter import NoticeAdapter

    try:
        runtime = vault.runtime
        if not runtime.preferences().get("oa_http_compat"):
            runtime.save_preferences({"oa_http_compat": True})
    except Exception:  # noqa: BLE001 - 开关失败就让下面真实报错，不掩盖
        pass
    NoticeAdapter().protected_oa(vault, 1)


def _tenants() -> list[str]:
    """只挑「有 webvpn 会话」的租户——没有会话的去打 OA 只会拿到登录页，白跑一趟。"""
    from .config import settings

    try:
        root = settings.tenants
        out = []
        for p in root.iterdir():
            if not p.is_dir():
                continue
            d = p / "stu-mcp" / "sessions"
            if (d / "webvpn.enc").is_file() or (d / "webvpn.bin").is_file():
                out.append(p.name)
        return out
    except OSError:
        return []


def tick() -> dict:
    from . import tenancy

    out = _load()
    changed = False
    for uid in _tenants():
        try:
            with tenancy.tenant_scope(uid) as t:
                if not t.session_exists("webvpn"):
                    continue
                rec = out.setdefault(uid, {})
                vault = t.vault()
                detail = ""
                try:
                    _probe(vault)
                    rec.update({"ok": True, "need_input": False, "at": _now(),
                                "detail": "会话活着（保活成功）"})
                    # 每一轮都把结论写进日志：不写就永远只能靠猜
                    log.info("keepalive %s alive", uid[:6])
                except Exception as exc:  # noqa: BLE001
                    code = getattr(exc, "code", "") or type(exc).__name__
                    detail = str(exc)[:200]
                    rec.update({"ok": False, "at": _now(), "detail": detail})
                    log.info("keepalive %s probe_failed code=%s", uid[:6], code)
                    # 先试静默重登：① 配过令牌密钥的走 stu-mcp 的自动算码；
                    # ② 退回到「只用存着的密码」那条路。
                    try:
                        from .relogin import relogin, relogin_auto

                        if relogin_auto(vault, "webvpn") or relogin(vault, "webvpn"):
                            _probe(t.vault())
                            rec.update({"ok": True, "need_input": False,
                                        "detail": "过期后已自动重登成功"})
                            log.info("keepalive %s relogin_ok", uid[:6])
                        else:
                            rec["need_input"] = True
                            log.info("keepalive %s need_input relogin_failed", uid[:6])
                    except Exception as exc2:  # noqa: BLE001
                        # 图形验证码 / 数盾动态码 / interaction_required：只能本人来
                        rec["need_input"] = True
                        rec["detail"] = ("%s / %s" % (detail, str(exc2)[:160]))[:240]
                        log.info("keepalive %s need_input code=%s",
                                 uid[:6], getattr(exc2, "code", "") or type(exc2).__name__)
                changed = True
        except Exception as exc:  # noqa: BLE001
            log.warning("keepalive %s 失败 %s", uid[:6], type(exc).__name__)
    if changed:
        _save(out)
    return out


def _now() -> str:
    import datetime

    return datetime.datetime.now().isoformat(timespec="seconds")


def _loop() -> None:
    # 启动先等 60 秒，别和服务起来的那些一次性请求抢
    time.sleep(60)
    while True:
        try:
            with _lock:
                res = tick()
            need = [k for k, v in res.items() if v.get("need_input")]
            log.info("keepalive 巡检完成：%d 个租户，其中 %d 个等本人输码", len(res), len(need))
        except Exception as exc:  # noqa: BLE001
            log.warning("keepalive 轮次异常 %s", type(exc).__name__)
        time.sleep(max(300, INTERVAL_MIN * 60))


def start() -> None:
    global _started
    if _started:
        return
    _started = True
    threading.Thread(target=_loop, name="stu-webvpn-keepalive", daemon=True).start()
    log.info("WebVPN 保活线程已启动，间隔 %d 分钟", INTERVAL_MIN)
