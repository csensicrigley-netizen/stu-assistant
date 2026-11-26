"""Regression tests for P0 server hardening and zero school-password persistence."""
from __future__ import annotations

import json
import sqlite3
import sys
import threading
import time
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest
from cryptography.fernet import Fernet
from fastapi.testclient import TestClient

from app import auth as authlib
from app import bind as bindmod
from app import config as configmod
from app import keepalive as keepalivemod
from app import ratelimit
from app import relogin as reloginmod
from app import tenancy as tenancymod
from app.db import DB


REPO_ROOT = Path(__file__).resolve().parents[2]
APP_DIR = REPO_ROOT / "backend" / "app"


class _SpyKeyStore:
    """Wraps SessionOnlyKeyStore to assert no caller ever attempts set_password."""

    def __init__(self, inner: tenancymod.SessionOnlyKeyStore) -> None:
        self._inner = inner
        self.set_calls: list[tuple[str, str]] = []

    def get_password(self, service: str, username: str) -> str | None:
        return self._inner.get_password(service, username)

    def set_password(self, service: str, username: str, password: str) -> None:
        self.set_calls.append((service, username))
        self._inner.set_password(service, username, password)

    def delete_password(self, service: str, username: str) -> None:
        self._inner.delete_password(service, username)


class _FakeVault:
    def __init__(self, home: Path, key_file: Path) -> None:
        self.runtime = SimpleNamespace(home=home)
        self._store = _SpyKeyStore(tenancymod.SessionOnlyKeyStore(home, key_file))
        self.saved_sessions: dict[str, dict] = {}

    def key_store(self) -> _SpyKeyStore:
        return self._store

    def save(self, service: str, state: dict) -> None:
        self.saved_sessions[service] = state
        sessions_dir = self.runtime.home / "sessions"
        sessions_dir.mkdir(parents=True, exist_ok=True)
        (sessions_dir / f"{service}.enc").write_bytes(b"fake-encrypted-session")

    def load(self, service: str) -> dict:
        return self.saved_sessions.get(service, {})


@pytest.fixture()
def env(monkeypatch, tmp_path):
    db_path = tmp_path / "app.db"
    jwt_secret = "test-only-hardening-signing-key-0123456789abcdef"
    fake_settings = configmod.Settings(
        root=tmp_path,
        db_path=db_path,
        jwt_secret=jwt_secret,
        jwt_ttl_seconds=3600,
        web_dir=None,
        cors_origins=("http://localhost:5180",),
        announcements_file=None,
        demo=False,
    )
    monkeypatch.setattr(configmod, "settings", fake_settings)
    monkeypatch.setattr(tenancymod, "settings", fake_settings)
    from app import main as mainmod

    monkeypatch.setattr(mainmod, "settings", fake_settings)
    monkeypatch.setattr(authlib, "settings", fake_settings)
    monkeypatch.setattr(mainmod, "bind_limiter", ratelimit.SlidingLimiter(limit=50, window_seconds=300))
    monkeypatch.setattr(mainmod, "login_account_limiter", ratelimit.SlidingLimiter(limit=50, window_seconds=300))
    monkeypatch.setattr(mainmod, "login_ip_limiter", ratelimit.SlidingLimiter(limit=50, window_seconds=300))

    db = DB(db_path)
    return SimpleNamespace(db=db, client=TestClient(mainmod.app), mainmod=mainmod, settings=fake_settings)


def _make_user(db: DB, email: str = "hardening@example.invalid") -> tuple[str, str]:
    user = db.create_user(email, "not-a-real-hash")
    return user["id"], authlib.issue_token(user["id"], ver=0)


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


# --------------------------------------------------------------------------
# 1. Zero password persistence on bind / relogin / webvpn_login / KeyStore
# --------------------------------------------------------------------------

def test_bind_and_relogin_never_write_credentials_enc(env, monkeypatch):
    if "stu_mcp" not in sys.modules:
        fake_pkg = ModuleType("stu_mcp")
        fake_runtime = ModuleType("stu_mcp.runtime")

        class _FakeAppError(Exception):
            def __init__(self, code: str = "", message: str = "", service: str = "") -> None:
                super().__init__(message)
                self.code = code

        fake_runtime.AppError = _FakeAppError
        monkeypatch.setitem(sys.modules, "stu_mcp", fake_pkg)
        monkeypatch.setitem(sys.modules, "stu_mcp.runtime", fake_runtime)

    uid, token = _make_user(env.db)
    vaults: list[_FakeVault] = []

    def _fake_vault(self):
        v = _FakeVault(self.home, self.key)
        vaults.append(v)
        return v

    monkeypatch.setattr(tenancymod.Tenant, "vault", _fake_vault)
    monkeypatch.setattr("app.sync.start_sync", lambda *a, **kw: False)

    login_calls: list[tuple[str, str]] = []

    def _fake_login_with(vault, service: str, account: str, password: str) -> None:
        login_calls.append((service, account))
        vault.save(service, {"cookies": [{"name": "JSESSIONID", "value": "revokable-session"}]})

    monkeypatch.setattr(reloginmod, "login_with", _fake_login_with)

    # 1) POST /v1/bind runs BindManager._run -> must save session only, never credentials.enc
    res = env.client.post(
        "/v1/bind",
        headers=_auth(token),
        json={"services": ["jw", "mystu"], "student_id": "2023100001", "password": "MySecretPassword!1"},
    )
    assert res.status_code == 200
    job_id = res.json()["job_id"]

    deadline = time.time() + 5.0
    snap = res.json()
    while snap["state"] == "running" and time.time() < deadline:
        time.sleep(0.05)
        snap = env.client.get(f"/v1/bind/{job_id}", headers=_auth(token)).json()
    assert snap["state"] == "ok", snap
    assert snap["result"]["bound"] == ["jw", "mystu"]

    creds_file = env.settings.tenant_home(uid) / "credentials.enc"
    assert not creds_file.exists(), "bind must never create credentials.enc"
    assert vaults and all(not v._store.set_calls for v in vaults)

    # 2) POST /v1/relogin uses caller-supplied password in memory only, never writes credentials.enc
    r_relogin = env.client.post(
        "/v1/relogin",
        headers=_auth(token),
        json={"services": ["jw"], "student_id": "2023100001", "password": "MySecretPassword!1"},
    )
    assert r_relogin.status_code == 200
    assert r_relogin.json()["ok"] is True
    assert r_relogin.json()["services"] == ["jw"]
    assert not creds_file.exists(), "relogin must never create credentials.enc"
    assert all(not v._store.set_calls for v in vaults)
    assert ("jw", "2023100001") in login_calls


def test_session_only_keystore_blocks_passwords_and_never_creates_credentials_enc(tmp_path):
    home = tmp_path / "stu-mcp"
    home.mkdir(parents=True)
    key_file = tmp_path / "master.key"
    master_key = Fernet.generate_key()
    key_file.write_bytes(master_key)

    store = tenancymod.SessionOnlyKeyStore(home, key_file)

    # Session encryption key resolves directly from master.key without creating credentials.enc
    assert store.get_password("stu-mcp.session-key", "acct123") == master_key.decode("ascii")
    assert not (home / "credentials.enc").exists()

    # Non-session services (school passwords / WebVPN TOTP) can never be read or written
    for svc in ("jw", "mystu", "webvpn", "repair", "stu-mcp.webvpn-auto-login"):
        assert store.get_password(svc, "2023100001") is None
        with pytest.raises(PermissionError, match="server_password_persistence_disabled"):
            store.set_password(svc, "2023100001", "secret")
    assert not (home / "credentials.enc").exists()


def test_webvpn_login_finish_does_not_persist_password(env, monkeypatch):
    # Ensure app.webvpn_login can be imported even when stu_mcp is absent in test env
    if "stu_mcp" not in sys.modules:
        fake_pkg = ModuleType("stu_mcp")
        fake_auth_http = ModuleType("stu_mcp.auth_http")
        fake_webvpn = ModuleType("stu_mcp.auth_http.webvpn")
        for name, val in {
            "CONF": "https://webvpn.stu.edu.cn/por/conf.csp",
            "CONFIG": "https://webvpn.stu.edu.cn/public/psw_config",
            "GRAPHICAL_CODES": frozenset({20023}),
            "INIT": "https://webvpn.stu.edu.cn/por/login_auth.csp",
            "ORIGIN": "https://webvpn.stu.edu.cn",
            "PASSWORD": "https://webvpn.stu.edu.cn/por/login_psw.csp",
            "RESOURCES": "https://webvpn.stu.edu.cn/por/rclist.csp",
            "TOTP": "https://webvpn.stu.edu.cn/auth/token",
            "WEB_HEADERS": {"User-Agent": "test"},
            "auth_fields": lambda b: {},
            "encrypt_password": lambda f, p: ("n", "e"),
            "oa_route": lambda a, b: ("", {}),
        }.items():
            setattr(fake_webvpn, name, val)
        monkeypatch.setitem(sys.modules, "stu_mcp", fake_pkg)
        monkeypatch.setitem(sys.modules, "stu_mcp.auth_http", fake_auth_http)
        monkeypatch.setitem(sys.modules, "stu_mcp.auth_http.webvpn", fake_webvpn)

    from app import webvpn_login as wv

    uid, _ = _make_user(env.db, "wv@example.invalid")
    created_vaults: list[_FakeVault] = []

    def _fake_vault(self):
        v = _FakeVault(self.home, self.key)
        created_vaults.append(v)
        return v

    monkeypatch.setattr(tenancymod.Tenant, "vault", _fake_vault)
    monkeypatch.setattr(
        wv,
        "_state_of",
        lambda _p: {"cookies": [{"name": "TWFID", "value": "sess-cookie"}], "origins": []},
    )

    pending = wv._Pending(uid)
    pending.user = "2023100001"
    pending.password = "NeverPersistThisPassword!"
    result = wv._finish(uid, pending)

    assert result["ok"] is True
    assert pending.password == ""
    assert not (env.settings.tenant_home(uid) / "credentials.enc").exists()
    assert created_vaults and not created_vaults[0]._store.set_calls
    assert "webvpn" in created_vaults[0].saved_sessions


def test_relogin_module_removes_silent_stored_password_relogin():
    assert callable(reloginmod.login_with)
    assert not hasattr(reloginmod, "stored_accounts")
    assert not hasattr(reloginmod, "relogin")
    assert not hasattr(reloginmod, "relogin_auto")
    assert not hasattr(bindmod, "_remember_credentials")


# --------------------------------------------------------------------------
# 2. BINDABLE narrowed to ("jw", "mystu") (no "repair", no "webvpn")
# --------------------------------------------------------------------------

def test_bindable_excludes_repair_and_rejects_repair_requests(env):
    assert bindmod.BINDABLE == ("jw", "mystu")
    assert "repair" not in bindmod.BINDABLE
    assert "webvpn" not in bindmod.BINDABLE

    _, token = _make_user(env.db, "bindable@example.invalid")
    status_res = env.client.get("/v1/bind", headers=_auth(token))
    assert status_res.status_code == 200
    assert status_res.json()["bindable"] == ["jw", "mystu"]

    # Attempting to bind "repair" or "webvpn" only is rejected by validation
    for forbidden in ("repair", "webvpn"):
        bad = env.client.post(
            "/v1/bind",
            headers=_auth(token),
            json={"services": [forbidden], "student_id": "2023100001", "password": "pw"},
        )
        assert bad.status_code == 422


# --------------------------------------------------------------------------
# 3. PRAGMA secure_delete=1 & med_sessions.token Fernet encryption + legacy read
# --------------------------------------------------------------------------

def test_sqlite_secure_delete_enabled_on_every_connection(tmp_path):
    db = DB(tmp_path / "app.db")
    with db.conn() as c:
        assert c.execute("PRAGMA secure_delete").fetchone()[0] == 1
        assert c.execute("PRAGMA foreign_keys").fetchone()[0] == 1


def test_med_session_token_encrypted_at_rest_and_legacy_plaintext_readable(tmp_path):
    db_path = tmp_path / "app.db"
    db = DB(db_path)
    uid1 = db.create_user("med1@example.invalid", "hash1")["id"]
    uid2 = db.create_user("med2@example.invalid", "hash2")["id"]

    secret_token = "med-jwt-super-secret-session-token-987654321"
    db.med_session_save(uid1, username="2023med01", name="医学生甲", token=secret_token)

    # Independent key file is created alongside app.db
    key_file = tmp_path / ".med_token.key"
    assert key_file.is_file()

    # Raw SQLite row must store Fernet ciphertext prefixed with "enc:", never plaintext
    with sqlite3.connect(db_path) as raw_conn:
        raw_val = raw_conn.execute(
            "SELECT token FROM med_sessions WHERE user_id=?", (uid1,)
        ).fetchone()[0]
    assert raw_val.startswith("enc:")
    assert secret_token not in raw_val
    assert secret_token.encode("utf-8") not in db_path.read_bytes()

    # Application read transparently decrypts the token
    loaded = db.med_session_get(uid1)
    assert loaded is not None
    assert loaded["token"] == secret_token
    assert loaded["username"] == "2023med01"
    assert loaded["name"] == "医学生甲"

    # Backward compatibility: historical plaintext row without "enc:" still reads cleanly
    legacy_plain = "legacy-plaintext-med-token-abcdef"
    with db.conn() as c:
        c.execute(
            "INSERT INTO med_sessions(user_id, username, name, token, updated_at) VALUES(?,?,?,?,?)",
            (uid2, "2022med02", "老用户", legacy_plain, 1700000000.0),
        )
    loaded_legacy = db.med_session_get(uid2)
    assert loaded_legacy is not None
    assert loaded_legacy["token"] == legacy_plain

    # Re-saving upgrades the row to encrypted format
    db.med_session_save(uid2, username="2022med02", name="老用户", token=legacy_plain)
    with sqlite3.connect(db_path) as raw_conn:
        upgraded = raw_conn.execute(
            "SELECT token FROM med_sessions WHERE user_id=?", (uid2,)
        ).fetchone()[0]
    assert upgraded.startswith("enc:")
    assert legacy_plain not in upgraded
    assert db.med_session_get(uid2)["token"] == legacy_plain

    # Unreadable ciphertext (e.g. rotated key) degrades cleanly to None instead of 500
    other_key = tmp_path / ".other_med.key"
    other_key.write_bytes(Fernet.generate_key())
    db_wrong_key = DB(db_path, med_key_path=other_key)
    assert db_wrong_key.med_session_get(uid1) is None


# --------------------------------------------------------------------------
# 4. /v1/webvpn/auto disabled + cleans legacy config; keepalive thread disabled
# --------------------------------------------------------------------------

def test_webvpn_auto_disabled_and_purges_legacy_files(env):
    uid, token = _make_user(env.db, "wvauto@example.invalid")

    # Seed legacy TOTP marker and legacy credentials.enc containing a password + totp entry
    with tenancymod.tenant_scope(uid) as tenant:
        marker = tenant.home / "webvpn-auto.json"
        marker.write_text('{"configured": true, "enabled": true}', encoding="utf-8")
        cipher = Fernet(tenant.key.read_bytes().strip())
        legacy_creds = tenant.home / "credentials.enc"
        legacy_payload = json.dumps(
            {
                "version": 1,
                "entries": {
                    "jw": {"2023100001": "old-password"},
                    "stu-mcp.webvpn-auto-login": {"acct": '{"totp":"JBSWY3DPEHPK3PXP"}'},
                },
            }
        ).encode("utf-8")
        legacy_creds.write_bytes(cipher.encrypt(legacy_payload))

    # GET /v1/webvpn/auto -> 410 Gone
    r_get = env.client.get("/v1/webvpn/auto", headers=_auth(token))
    assert r_get.status_code == 410
    assert r_get.json()["detail"] == "webvpn_auto_disabled"

    # POST /v1/webvpn/auto -> 410 Gone AND cleans legacy config without writing new seed
    r_post = env.client.post(
        "/v1/webvpn/auto",
        headers=_auth(token),
        json={"secret": "JBSWY3DPEHPK3PXP"},
    )
    assert r_post.status_code == 410
    assert r_post.json()["detail"] == "webvpn_auto_disabled"
    assert not (env.settings.tenant_home(uid) / "webvpn-auto.json").exists()
    assert not (env.settings.tenant_home(uid) / "credentials.enc").exists()

    # DELETE /v1/webvpn/auto remains idempotent and reports disabled
    r_del = env.client.delete("/v1/webvpn/auto", headers=_auth(token))
    assert r_del.status_code == 200
    assert r_del.json()["configured"] is False
    assert r_del.json()["enabled"] is False
    assert r_del.json()["status"] == "disabled"

    # /v1/webvpn/login/start never falls back to stored passwords
    r_start = env.client.post("/v1/webvpn/login/start", headers=_auth(token), json={})
    assert r_start.status_code == 409
    assert r_start.json()["detail"] == "need_credentials"


def test_keepalive_background_thread_disabled(env):
    assert keepalivemod.ENABLED is False
    before = {t.name for t in threading.enumerate()}
    keepalivemod.start()
    after = {t.name for t in threading.enumerate()}
    assert "stu-webvpn-keepalive" not in after
    assert after == before
    assert keepalivemod.tick() == {}
    assert keepalivemod.state() == {}
    # No startup handler registered on FastAPI app to launch keepalive
    startup_names = [getattr(fn, "__name__", "") for fn in env.mainmod.app.router.on_startup]
    assert "_start_webvpn_keepalive" not in startup_names


# --------------------------------------------------------------------------
# 5. TLS verification, static source audit, deploy LF & version metadata
# --------------------------------------------------------------------------

def test_no_verify_false_or_password_persistence_calls_in_backend_app():
    for py_file in sorted(APP_DIR.rglob("*.py")):
        text = py_file.read_text(encoding="utf-8")
        assert "verify=False" not in text, f"TLS verification disabled in {py_file.name}"
        assert "_remember_credentials" not in text, f"_remember_credentials left in {py_file.name}"
        if py_file.name != "tenancy.py":
            assert "set_password(" not in text, f"set_password call left in {py_file.name}"


def test_deploy_configs_hardened_and_lf_only():
    backup_raw = (REPO_ROOT / "deploy" / "stu-backup.sh").read_bytes()
    service_raw = (REPO_ROOT / "deploy" / "stu-assistant.service").read_bytes()
    assert b"\r\n" not in backup_raw, "deploy/stu-backup.sh must use LF line endings"
    assert b"\r\n" not in service_raw, "deploy/stu-assistant.service must use LF line endings"

    backup_text = backup_raw.decode("utf-8")
    assert "tar -C /var/lib/stu-assistant" not in backup_text
    assert "tenants-$STAMP.tar.gz" not in backup_text

    service_text = service_raw.decode("utf-8")
    assert "LimitCORE=0" in service_text


def test_latest_version_metadata_updated_to_1_2_1():
    meta = json.loads((APP_DIR / "latest_version.json").read_text(encoding="utf-8"))
    assert meta["channel"] == "product"
    assert meta["versionName"] == "1.2.1"
    assert meta["versionCode"] == 69
    assert meta["releasedAt"] == "2026-11-27T00:30:00+08:00"
    assert isinstance(meta["notes"], list) and len(meta["notes"]) >= 2
