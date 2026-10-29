"""Server self-check. Run on the server after installing:

    sudo -u stu-assistant /opt/stu-assistant/venv/bin/python -m app.doctor

Checks configuration, storage, and whether THIS machine can reach the school
systems (a server outside mainland China, or one the school's firewall does
not like, may be able to serve the website but not log students in).
No school account is used; the only real reads are anonymous public pages.
"""
from __future__ import annotations

import os
import socket
import ssl
import sys
import time
from pathlib import Path

OK, WARN, BAD = "通过", "注意", "失败"
results: list[tuple[str, str, str]] = []


def report(state: str, item: str, detail: str) -> None:
    results.append((state, item, detail))
    print(f"[{state}] {item}：{detail}")


def tls_probe(host: str, port: int = 443, timeout: float = 8.0) -> tuple[bool, str]:
    started = time.time()
    try:
        with socket.create_connection((host, port), timeout=timeout) as raw:
            if port == 80:
                return True, f"{(time.time() - started) * 1000:.0f} ms"
            with ssl.create_default_context().wrap_socket(raw, server_hostname=host):
                return True, f"{(time.time() - started) * 1000:.0f} ms"
    except socket.gaierror:
        return False, "域名解析失败"
    except socket.timeout:
        return False, "连接超时"
    except ssl.SSLError as exc:
        return False, f"TLS 失败（{exc.reason or exc}）"
    except OSError as exc:
        return False, f"连接失败（{exc.strerror or exc}）"


def main() -> int:
    print("STU 事项助手 · 服务器自检\n")

    # ---- configuration
    secret = os.environ.get("STU_APP_JWT_SECRET", "")
    report(OK if len(secret) >= 32 else BAD, "登录签名密钥", "已设置" if len(secret) >= 32 else "缺失或太短")
    report(WARN if os.environ.get("STU_APP_DEMO") else OK, "运行模式",
           "演示模式（不会连接学校系统）" if os.environ.get("STU_APP_DEMO") else "正式模式")

    home = Path(os.environ.get("STU_APP_HOME", "/data/stu-app"))
    try:
        home.mkdir(parents=True, exist_ok=True)
        probe = home / ".doctor-write-test"
        probe.write_text("ok")
        probe.unlink()
        report(OK, "数据目录", f"{home} 可写")
    except OSError as exc:
        report(BAD, "数据目录", f"{home} 不可写（{exc}）")

    web = os.environ.get("STU_APP_WEB_DIR", "")
    if web and (Path(web) / "index.html").is_file():
        apk = Path(web) / "download" / "stu-assistant.apk"
        report(OK, "网页版文件", f"{web}" + ("，含安卓安装包" if apk.is_file() else "，未找到安卓安装包"))
    else:
        report(BAD, "网页版文件", f"STU_APP_WEB_DIR 未指向网页版（{web or '未设置'}）")

    try:
        import importlib.metadata as meta

        report(OK, "stu-mcp", f"版本 {meta.version('stu-mcp')}")
    except Exception:  # noqa: BLE001
        report(BAD, "stu-mcp", "没有安装")

    # ---- can this server reach the school?
    print()
    targets = [
        ("sso.stu.edu.cn", 443, "学校统一认证（连接校园账号必需）"),
        ("jw.stu.edu.cn", 443, "教务系统（课表、成绩、考试）"),
        ("my.stu.edu.cn", 443, "MySTU（作业）"),
        ("www.stu.edu.cn", 443, "学校官网（通知）"),
        ("oa.stu.edu.cn", 80, "OA 通知"),
        ("wx.isfdz.com", 443, "充电平台"),
    ]
    for host, port, what in targets:
        ok, detail = tls_probe(host, port)
        essential = host in {"sso.stu.edu.cn", "jw.stu.edu.cn"}
        report(OK if ok else (BAD if essential else WARN), f"{what} {host}", detail)

    # ---- one anonymous read of public data
    print()
    try:
        from .collectors import collect_notices

        res = collect_notices(None, limit=3, include_oa=False)
        n = len(res.kind_records.get("notice", []))
        report(OK if res.code == 0 and n else WARN, "读取学校公开通知", f"{n} 条（{res.detail}）")
    except Exception as exc:  # noqa: BLE001
        report(WARN, "读取学校公开通知", type(exc).__name__)
    try:
        from . import charging

        state = charging.ports("4GR00008491", 20)
        report(OK, "读取充电桩", f"图书馆三 空闲 {state['counts']['free']}/{state['counts']['total']}")
    except Exception as exc:  # noqa: BLE001
        report(WARN, "读取充电桩", str(getattr(exc, "code", "") or type(exc).__name__))

    bad = [r for r in results if r[0] == BAD]
    warn = [r for r in results if r[0] == WARN]
    print()
    if bad:
        print(f"有 {len(bad)} 项失败，需要先处理。")
    elif warn:
        print(f"可以使用，另有 {len(warn)} 项需要注意。")
    else:
        print("全部通过，可以开始使用。")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
