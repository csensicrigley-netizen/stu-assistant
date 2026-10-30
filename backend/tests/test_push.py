"""实时活动推送适配器的离线测试。

httpx 整体换成替身，一个包都不出网；断言两件事：
  ① 没凭据 → skipped=dry_run，且没有任何请求发出去；
  ② 有凭据 → messages:send / unicast 的请求体里 live_view 与 live 完全一致。
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.db import DB  # noqa: E402
from app.push import huawei, oppo, router  # noqa: E402
from app.push.models import LiveUpdate  # noqa: E402

LIVE = {
    "title": "城际班车 C3",
    "detail": "15:40 东门上车点 · 预计 2 站后到",
    "starts_at": 1761810000000,
    "ends_at": 1761813600000,
    "phase": "before",
}
HUAWEI_ENV = {
    "HUAWEI_APP_ID": "100000001",
    "HUAWEI_CLIENT_ID": "client-id",
    "HUAWEI_CLIENT_SECRET": "client-secret",
}
OPPO_ENV = {"OPPO_APP_KEY": "oppo-key", "OPPO_MASTER_SECRET": "oppo-secret"}
ALL_ENV = {**HUAWEI_ENV, **OPPO_ENV}


class _Resp:
    def __init__(self, status_code: int, payload: dict):
        self.status_code = status_code
        self._payload = payload
        self.text = json.dumps(payload)

    def json(self) -> dict:
        return self._payload


class _FakeClient:
    """替身 httpx.Client：记录每次 post，oauth 回 access_token，其它回成功码。"""

    def __init__(self, calls: list, timeout: float | None = None, **kwargs):
        self.calls = calls
        self.timeout = timeout

    def __enter__(self) -> "_FakeClient":
        return self

    def __exit__(self, *exc) -> bool:
        return False

    def post(self, url: str, data=None, json=None, headers=None, **kwargs) -> _Resp:
        self.calls.append({"url": url, "data": data, "json": json,
                           "headers": headers or {}, "timeout": self.timeout})
        if "oauth2" in url:
            return _Resp(200, {"access_token": "AT-1", "expires_in": 3600})
        if "oppomobile" in url:
            return _Resp(200, {"code": 0, "message": "success"})
        return _Resp(200, {"code": "80000000", "msg": "Success"})


def _install(monkeypatch, calls: list) -> None:
    fake = SimpleNamespace(Client=lambda **kw: _FakeClient(calls, **kw))
    monkeypatch.setattr(huawei, "httpx", fake)
    monkeypatch.setattr(oppo, "httpx", fake)


def _client() -> TestClient:
    app = FastAPI()
    app.include_router(router.build_router(lambda: "u-test"))
    return TestClient(app)


@pytest.fixture()
def no_env(monkeypatch):
    for name in ALL_ENV:
        monkeypatch.delenv(name, raising=False)


@pytest.fixture()
def db_file(monkeypatch, tmp_path):
    path = tmp_path / "app.db"
    db = DB(path)
    with db.conn() as c:
        c.execute("INSERT INTO users(id,email,pass_hash,created_at) VALUES(?,?,?,?)",
                  ("u-test", "push@example.invalid", "unused", 1))
    monkeypatch.setattr(router, "_db_path", lambda: path)
    return path


def test_missing_credentials_are_dry_run(monkeypatch, no_env, db_file):
    calls: list = []
    _install(monkeypatch, calls)

    resp = _client().post("/v1/push/live", json={"targets": ["huawei", "oppo"], "live": LIVE})

    assert resp.status_code == 200
    body = resp.json()
    assert body["providers"]["huawei"] == {"skipped": "dry_run"}
    assert body["providers"]["oppo"] == {"skipped": "dry_run"}
    assert calls == []  # 一个包都没发
    assert body["live"] == LiveUpdate(**LIVE).as_dict()


def test_huawei_payload_carries_live_view(monkeypatch, no_env, db_file):
    for name, value in HUAWEI_ENV.items():
        monkeypatch.setenv(name, value)
    DB(db_file).add_device("u-test", "android", "device-token-1")
    calls: list = []
    _install(monkeypatch, calls)

    resp = _client().post("/v1/push/live", json={"targets": ["huawei"], "live": LIVE})

    assert resp.status_code == 200
    result = resp.json()["providers"]["huawei"]
    assert result["sent"] == 1 and result["failed"] == 0

    token_req = next(c for c in calls if "oauth2" in c["url"])
    assert token_req["url"] == huawei.TOKEN_URL
    assert token_req["data"]["grant_type"] == "client_credentials"
    assert token_req["data"]["client_id"] == HUAWEI_ENV["HUAWEI_CLIENT_ID"]
    assert token_req["data"]["client_secret"] == HUAWEI_ENV["HUAWEI_CLIENT_SECRET"]
    assert token_req["timeout"] == 30.0

    send = next(c for c in calls if "messages:send" in c["url"])
    assert send["url"] == "https://push-api.cloud.huawei.com/v1/100000001/messages:send"
    assert send["headers"]["Authorization"] == "Bearer AT-1"

    message = send["json"]["message"]
    assert message["token"] == ["device-token-1"]
    assert message["notification"] == {"title": LIVE["title"], "body": LIVE["detail"]}
    assert message["android"] == {"urgency": "NORMAL", "category": "TRANSPORT"}

    data = json.loads(message["data"])  # 华为要求 data 是 JSON 字符串
    assert data["live_view"] == LiveUpdate(**LIVE).as_dict()


def test_oppo_signed_unicast_carries_live_view(monkeypatch, no_env, db_file):
    for name, value in OPPO_ENV.items():
        monkeypatch.setenv(name, value)
    DB(db_file).add_device("u-test", "android", "device-token-1")
    calls: list = []
    _install(monkeypatch, calls)

    resp = _client().post("/v1/push/live", json={"targets": ["oppo"], "live": LIVE})

    assert resp.status_code == 200
    result = resp.json()["providers"]["oppo"]
    assert result["sent"] == 1 and result["failed"] == 0

    send = next(c for c in calls if "unicast" in c["url"])
    assert send["url"] == oppo.SEND_URL
    timestamp = send["data"]["timestamp"]
    expected = hashlib.sha256(
        f"{OPPO_ENV['OPPO_APP_KEY']}{timestamp}{OPPO_ENV['OPPO_MASTER_SECRET']}".encode()
    ).hexdigest()
    assert send["data"]["auth_token"] == expected
    assert send["headers"]["auth_token"] == expected

    message = json.loads(send["data"]["message"])
    assert message["target_type"] == 2
    assert message["target_value"] == ["device-token-1"]
    notification = message["notification"]
    assert notification["title"] == LIVE["title"]
    assert notification["sub_title"] == LIVE["detail"]
    assert notification["content"] == LIVE["detail"]
    assert notification["style"] == 2
    assert notification["starts_at"] == LIVE["starts_at"]
    assert notification["ends_at"] == LIVE["ends_at"]
    assert notification["phase"] == LIVE["phase"]
    parsed = json.loads(notification["action_parameters"])
    assert parsed["live_view"] == LiveUpdate(**LIVE).as_dict()


def test_configured_but_no_tokens_is_no_tokens(monkeypatch, no_env, db_file):
    for name, value in HUAWEI_ENV.items():
        monkeypatch.setenv(name, value)
    calls: list = []
    _install(monkeypatch, calls)

    resp = _client().post("/v1/push/live", json={"live": LIVE})

    assert resp.status_code == 200
    providers = resp.json()["providers"]
    assert providers["huawei"] == {"skipped": "no_tokens"}
    assert providers["oppo"] == {"skipped": "dry_run"}
    assert calls == []


def test_provider_failure_never_500(monkeypatch, no_env, db_file):
    for name, value in HUAWEI_ENV.items():
        monkeypatch.setenv(name, value)
    DB(db_file).add_device("u-test", "android", "device-token-1")

    class _BoomClient(_FakeClient):
        def post(self, url: str, **kwargs) -> _Resp:
            self.calls.append({"url": url, "data": kwargs.get("data"), "json": kwargs.get("json"),
                               "headers": kwargs.get("headers") or {}, "timeout": self.timeout})
            raise RuntimeError("network down")

    calls: list = []
    fake = SimpleNamespace(Client=lambda **kw: _BoomClient(calls, **kw))
    monkeypatch.setattr(huawei, "httpx", fake)
    monkeypatch.setattr(oppo, "httpx", fake)

    resp = _client().post("/v1/push/live", json={"targets": ["huawei"], "live": LIVE})

    assert resp.status_code == 200
    result = resp.json()["providers"]["huawei"]
    assert result["sent"] == 0
    assert result["failed"] == 1
    assert result["reason"].startswith("token_failed")
    assert len(calls) == 2  # token 失败重试一次


def test_unknown_target_and_phase_fallback(monkeypatch, no_env, db_file):
    calls: list = []
    _install(monkeypatch, calls)
    payload = dict(LIVE, phase="someday")

    resp = _client().post("/v1/push/live", json={"targets": ["fcm"], "live": payload})

    assert resp.status_code == 200
    body = resp.json()
    assert body["providers"]["fcm"] == {"skipped": "unknown_target"}
    assert body["live"]["phase"] == "before"
    assert calls == []
