"""Offline coverage of hot configuration, audience filtering and image boundaries."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import announcements as messages


@pytest.fixture()
def config_file(monkeypatch, tmp_path):
    monkeypatch.setattr(messages, "settings", SimpleNamespace(root=tmp_path, announcements_file=None))
    return tmp_path / "announcements.json"


def write(path: Path, entries=None, **options):
    path.write_text(json.dumps({"announcements": entries or [], **options}), encoding="utf-8")


def item(identity="test", **overrides):
    return {"id": identity, "title": "你好，同学", **overrides}


def client():
    app = FastAPI()
    app.include_router(messages.router)
    return TestClient(app)


def test_default_stars_official_repo_without_auth(config_file):
    result = client().get("/v1/announcements?platform=android&version_code=59")
    assert result.status_code == 200
    assert result.headers["Cache-Control"] == "no-store"
    entries = result.json()["announcements"]
    assert len(entries) == 1
    assert entries[0]["id"] == "github-star"
    assert entries[0]["display"] == "once"
    assert entries[0]["action_url"] == "https://github.com/csensicrigley-netizen/stu-assistant"
    assert "platforms" not in entries[0]


def test_live_edits_and_kill_switch_do_not_require_restart(config_file):
    c = client()
    write(config_file, [item("one")])
    assert c.get("/v1/announcements").json()["announcements"][0]["id"] == "one"
    write(config_file, [item("two", revision=2)])
    assert c.get("/v1/announcements").json()["announcements"][0]["revision"] == 2
    write(config_file, [item()], enabled=False)
    assert c.get("/v1/announcements").json() == {"announcements": []}
    config_file.write_text("[]", encoding="utf-8")
    assert c.get("/v1/announcements").json() == {"announcements": []}


def test_effects_are_optional_and_hot_configurable(config_file):
    write(config_file, [item()])
    assert messages.active_announcements(platform="android")[0]["effects"] == []
    write(config_file, [item(effects=["star-struck", "party-popper"])])
    assert messages.active_announcements(platform="android")[0]["effects"] == ["star-struck", "party-popper"]
    write(config_file, [item(effects=[])])
    assert messages.active_announcements(platform="android")[0]["effects"] == []


@pytest.mark.parametrize("effects", [["https://example.test/remote.json"], ["../private"], [1], "star-struck", ["party-popper"] * 6])
def test_effects_only_accept_bounded_bundled_identifiers(config_file, effects):
    write(config_file, [item(effects=effects)])
    assert messages.active_announcements(platform="android") == []


@pytest.mark.parametrize("contents", ["{", "null", '"hello"', '{"announcements": "wrong"}',
    '{"enabled":"false"}', '{"announcements":[{"id":"x","title":"ok","display":"hourly"}]}',
    '{"announcements":[{"id":"x","title":"ok","revision":true}]}',
    '{"announcements":[{"id":"x","title":"ok","unexpected":1}]}',
    ' ' * (messages.MAX_CONFIG_BYTES + 1)],
    ids=["broken-json", "null", "string", "wrong-list", "non-boolean", "display", "revision", "unknown-field", "oversize"])
def test_invalid_config_never_resurrects_default(config_file, contents):
    write(config_file, [item("old")])
    assert messages.active_announcements(platform="web")
    config_file.write_text(contents, encoding="utf-8")
    assert messages.active_announcements(platform="web") == []


def test_explicit_missing_file_fails_closed(config_file, monkeypatch):
    monkeypatch.setattr(messages, "settings", SimpleNamespace(root=config_file.parent,
                         announcements_file=config_file.parent / "custom.json"))
    assert messages.active_announcements(platform="android") == []


def test_windows_bom_is_supported(config_file):
    config_file.write_text(json.dumps([item()]), encoding="utf-8-sig")
    assert messages.active_announcements(platform="web")[0]["id"] == "test"


def test_audience_time_and_priority_filtering(config_file):
    at = datetime(2026, 11, 15, 0, tzinfo=timezone.utc)
    write(config_file, [
        item("default"), item("disabled", enabled=False),
        item("web-only", platforms=["web"]),
        item("future", starts_at="2026-11-15T08:00:01+08:00"),
        item("expired", ends_at="2026-11-15T08:00:00+08:00"),
        item("old-client", max_version_code=58),
        item("new-client", min_version_code=60),
        item("priority", priority=5, min_version_code=59, max_version_code=59,
             starts_at="2026-11-15T08:00:00+08:00", ends_at="2026-11-16T08:00:00+08:00"),
        item("priority-tie", priority=5),
    ])
    rows = messages.active_announcements(platform="android", version_code=59, at=at)
    assert [row["id"] for row in rows] == ["priority", "priority-tie", "default"]
    assert [row["id"] for row in messages.active_announcements(platform="android", at=at)] == ["priority-tie", "default"]
    assert "web-only" in {row["id"] for row in messages.active_announcements(platform="web", at=at)}


@pytest.mark.parametrize("overrides", [
    {"starts_at": "2026-11-15T08:00:00"},
    {"starts_at": "2026-11-16T08:00:00+08:00", "ends_at": "2026-11-15T08:00:00+08:00"},
    {"min_version_code": 60, "max_version_code": 59},
    {"action_url": "javascript:alert(1)"}, {"action_url": "http://github.com/test"},
    {"action_url": "https://user:password@example.org/"},
    {"action_url": "https://example.org\n/path"},
    {"image_url": "//example.org/image.png"}, {"image_url": "file:///tmp/secret"},
    {"image_url": "/v1/announcements/assets/../secret.png"},
    {"image_url": "/v1/announcements/assets/picture.svg"},
])
def test_invalid_entry_fails_closed(config_file, overrides):
    write(config_file, [item(**overrides)])
    assert messages.active_announcements(platform="web") == []


def test_duplicate_ids_fail_closed(config_file):
    write(config_file, [item(), item(revision=2)])
    assert messages.active_announcements(platform="android") == []


def test_image_routes_are_public_and_confined(config_file):
    assets = config_file.parent / "announcements-assets"
    assets.mkdir()
    png = b"\x89PNG\r\n\x1a\nimage fixture"
    (assets / "welcome.png").write_bytes(png)
    secret = config_file.parent / "secret.png"
    secret.write_bytes(b"secret")
    c = client()
    result = c.get("/v1/announcements/assets/welcome.png")
    assert result.status_code == 200
    assert result.content == png
    assert result.headers["content-type"] == "image/png"
    assert result.headers["Cache-Control"] == "no-store"
    assert result.headers["X-Content-Type-Options"] == "nosniff"
    for name in ("missing.png", "welcome.svg", "%2e%2e%2fsecret.png", "..%5csecret.png", ".hidden.png"):
        assert c.get("/v1/announcements/assets/" + name).status_code == 404
    write(config_file, [item(image_url="/v1/announcements/assets/welcome.png", image_alt="欢迎")])
    assert c.get("/v1/announcements").json()["announcements"][0]["image_alt"] == "欢迎"


def test_asset_symlink_cannot_escape_folder(config_file):
    assets = config_file.parent / "announcements-assets"
    assets.mkdir()
    secret = config_file.parent / "secret.png"
    secret.write_bytes(b"secret")
    try:
        (assets / "escape.png").symlink_to(secret)
    except OSError:
        pytest.skip("symlink creation is not available for this Windows account")
    assert client().get("/v1/announcements/assets/escape.png").status_code == 404


def test_invalid_queries_are_rejected(config_file):
    c = client()
    for query in ("platform=ios", "version_code=-1", "version_code=bad"):
        assert c.get("/v1/announcements?" + query).status_code == 422
