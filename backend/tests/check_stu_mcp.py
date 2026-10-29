"""Offline check that every stu-mcp entry point the backend calls exists with
the arguments we pass, and that a tenant vault opens with its own key store.

    python tests/check_stu_mcp.py

No network, no school login.
"""
from __future__ import annotations

import inspect
import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def params(fn) -> list[str]:
    return list(inspect.signature(fn).parameters)


def main() -> int:
    failures: list[str] = []

    def expect(cond: bool, what: str) -> None:
        print(("ok   " if cond else "FAIL ") + what)
        if not cond:
            failures.append(what)

    from stu_mcp.auth_http import Credentials, login_http
    from stu_mcp.charging_devices import Device
    from stu_mcp.credentials import FileKeyStore
    from stu_mcp.features.charging.adapter import ChargingClient
    from stu_mcp.features.jw.service import academic_timetable, jw
    from stu_mcp.features.mystu.service import mystu
    from stu_mcp.features.notices.adapter import NoticeAdapter
    from stu_mcp.runtime import Runtime
    from stu_mcp.sources import PUBLIC_LISTINGS
    from stu_mcp.vault import Vault

    p = params(login_http)
    expect({"vault", "service", "credentials", "interaction", "allow_http_callback"} <= set(p),
           f"login_http accepts credentials/interaction/allow_http_callback ({len(p)} params)")
    expect(params(academic_timetable)[:2] == ["vault", "semester"], "academic_timetable(vault, semester)")
    expect(params(jw)[:3] == ["vault", "limit", "semester"], "jw(vault, limit, semester)")
    expect(params(mystu)[:2] == ["vault", "limit"], "mystu(vault, limit)")
    for name in ("public_session", "public_listing", "anonymous_oa"):
        expect(hasattr(NoticeAdapter, name), f"NoticeAdapter.{name}")
    expect(len(PUBLIC_LISTINGS) == 4, f"PUBLIC_LISTINGS has {len(PUBLIC_LISTINGS)} listings")
    expect(hasattr(ChargingClient, "device"), "ChargingClient.device")
    expect({"code", "name", "location", "capacity"} <= set(params(Device)), "Device(code, name, location, capacity)")
    expect(set(params(Credentials)) >= {"username", "password"} or len(params(Credentials)) >= 2,
           "Credentials(username, password)")

    # the interaction object we hand to login_http must answer every call it makes
    from app.bind import _Interaction  # noqa: PLC0415

    for name in ("credentials", "captcha", "code", "show_qr"):
        expect(callable(getattr(_Interaction, name, None)), f"_Interaction.{name}")

    # tenant isolation: explicit Runtime + FileKeyStore, no process-global env
    with tempfile.TemporaryDirectory() as tmp:
        os.environ["STU_APP_HOME"] = tmp
        os.environ.pop("STU_MCP_HOME", None)
        os.environ.pop("STU_MCP_KEY_FILE", None)
        import importlib

        import app.config as config

        importlib.reload(config)
        import app.tenancy as tenancy

        importlib.reload(tenancy)
        with tenancy.tenant_scope("user-a") as a:
            va = a.vault()
            store = va.key_store()
            expect(isinstance(store, FileKeyStore), "tenant vault uses its own FileKeyStore")
            expect(va.runtime.home == a.home, "tenant vault runtime home is the tenant home")
            store.set_password("stu-mcp-check", "probe", "value-a")
        with tenancy.tenant_scope("user-b") as b:
            vb = b.vault()
            expect(vb.key_store().get_password("stu-mcp-check", "probe") is None,
                   "user-b cannot read user-a's key store")
        expect("STU_MCP_HOME" not in os.environ and "STU_MCP_KEY_FILE" not in os.environ,
               "no stu-mcp environment variables were set")
        with tenancy.tenant_scope("user-a") as a2:
            expect(a2.vault().key_store().get_password("stu-mcp-check", "probe") == "value-a",
                   "user-a reads back its own secret")

    print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
