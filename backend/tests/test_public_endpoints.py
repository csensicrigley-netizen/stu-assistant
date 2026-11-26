"""Tests for unauthenticated pure-public endpoints and backward compatibility."""
from __future__ import annotations

import json
import time
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app import auth as authlib
from app import bus as busmod
from app import charging as chargingmod
from app import collectors
from app import config as configmod
from app import ratelimit
from app import timetable_import as imp
from app import today_export
from app.db import DB


@pytest.fixture()
def env(monkeypatch, tmp_path):
    db_path = tmp_path / "app.db"
    jwt_secret = "test-only-public-endpoints-signing-key-0123456789abcdef"
    fake_settings = SimpleNamespace(
        root=tmp_path,
        db_path=db_path,
        jwt_secret=jwt_secret,
        jwt_ttl_seconds=3600,
        web_dir=None,
        cors_origins=("http://localhost:5180",),
        announcements_file=None,
        demo=False,
        validate=lambda: None,
    )
    monkeypatch.setattr(configmod, "settings", fake_settings)
    from app import main as mainmod

    monkeypatch.setattr(mainmod, "settings", fake_settings)
    monkeypatch.setattr(authlib, "settings", fake_settings)
    monkeypatch.setattr(mainmod, "today_export_limiter", ratelimit.SlidingLimiter(limit=20, window_seconds=300))
    monkeypatch.setattr(mainmod, "today_claim_limiter", ratelimit.SlidingLimiter(limit=30, window_seconds=300))
    monkeypatch.setattr(mainmod, "public_notices_limiter", ratelimit.SlidingLimiter(limit=180, window_seconds=300))
    monkeypatch.setattr(mainmod, "public_bus_limiter", ratelimit.SlidingLimiter(limit=180, window_seconds=300))
    monkeypatch.setattr(mainmod, "public_charging_limiter", ratelimit.SlidingLimiter(limit=180, window_seconds=300))
    monkeypatch.setattr(mainmod, "public_today_export_limiter", ratelimit.SlidingLimiter(limit=30, window_seconds=300))
    monkeypatch.setattr(collectors, "_public_cache", None)
    monkeypatch.setattr(busmod, "UPSTREAM", "")
    db = DB(db_path)
    return SimpleNamespace(db=db, client=TestClient(mainmod.app), mainmod=mainmod, settings=fake_settings)


def _make_user(db: DB, email: str = "web.student@example.invalid") -> tuple[str, str]:
    user = db.create_user(email, "not-a-real-hash", student_id_h="sid-hash-web")
    token = authlib.issue_token(user["id"], ver=0)
    return user["id"], token


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _sample_timetable(**overrides) -> dict:
    base = {
        "semester": "2026-2027-1",
        "campus": "main",
        "checked_at": "2026-11-26T10:00:00Z",
        "courses": [
            {
                "id": "local-c1",
                "title": "数据结构与算法",
                "course_code": "CS201",
                "class_name": "计科1班",
                "teachers": ["周教授", "陈助教"],
                "meetings": [
                    {
                        "id": "local-m1",
                        "weekday": 2,
                        "weeks": [1, 3, 2, 2],
                        "periods": [4, 3],
                        "location": "致知楼 C304",
                        "raw_time": "周二 第3-4节",
                    }
                ],
            }
        ],
    }
    base.update(overrides)
    return base


# --------------------------------------------------------------------------
# GET /v1/public/notices
# --------------------------------------------------------------------------

def test_public_notices_no_auth_uses_shared_cache_and_excludes_oa(env, monkeypatch):
    calls: list[dict] = []

    def fake_collect_notices(vault, *, limit=collectors.OA_DAILY_LIMIT, include_oa=True):
        calls.append({"vault": vault, "limit": limit, "include_oa": include_oa})
        records = [
            {
                "id": f"public:notice:{i}",
                "kind": "notice",
                "source": "public",
                "title": f"学校官网通知 {i}",
                "category": "学生通知",
                "published_at": f"2026-11-{26 - (i % 20):02d}",
                "url": f"https://www.stu.edu.cn/info/{i}.htm",
            }
            for i in range(45)
        ]
        # Defense-in-depth check: even if an OA-tagged item slipped in, endpoint strips it.
        records.append(
            {
                "id": "oa:secret:1",
                "kind": "notice",
                "source": "oa",
                "title": "内部OA私密通知",
                "published_at": "2026-11-26",
                "url": "https://oa.stu.edu.cn/secret",
            }
        )
        return collectors.CollectResult("public", collectors.CODE_OK, "4/4 个栏目", {"notice": records[:limit]})

    monkeypatch.setattr(collectors, "collect_notices", fake_collect_notices)

    # First call warms shared public cache (no Authorization header).
    r1 = env.client.get("/v1/public/notices")
    assert r1.status_code == 200
    assert r1.headers.get("Cache-Control") == "no-store"
    body1 = r1.json()
    assert body1["count"] == 30
    assert len(body1["items"]) == 30
    assert len(calls) == 1
    assert calls[0] == {"vault": None, "limit": collectors.PUBLIC_NOTICE_LIMIT, "include_oa": False}

    item0 = body1["items"][0]
    assert set(item0.keys()) == {"id", "kind", "source", "title", "updated_at", "detail"}
    assert item0["id"] == "public:notice:0"
    assert item0["kind"] == "notice"
    assert item0["source"] == "public"
    assert item0["title"] == "学校官网通知 0"
    assert item0["detail"]["category"] == "学生通知"
    assert item0["detail"]["url"] == "https://www.stu.edu.cn/info/0.htm"
    assert "id" not in item0["detail"]

    # Second call with limit=100 hits cache (no extra scrape) and filters out OA record.
    r2 = env.client.get("/v1/public/notices?limit=100")
    assert r2.status_code == 200
    assert len(calls) == 1
    body2 = r2.json()
    assert body2["count"] == 45
    assert all(row["source"] == "public" for row in body2["items"])
    assert all("OA" not in row["title"] for row in body2["items"])

    # Invalid limits are rejected.
    assert env.client.get("/v1/public/notices?limit=0").status_code == 422
    assert env.client.get("/v1/public/notices?limit=101").status_code == 422


def test_public_notices_demo_and_ratelimit(env, monkeypatch):
    # Stale cache fallback when upstream is temporarily unreachable after TTL expires.
    cached_res = collectors.CollectResult(
        "public",
        collectors.CODE_OK,
        "ok",
        {"notice": [{"id": "public:notice:stale", "source": "public", "title": "缓存兜底通知", "published_at": "2026-11-25"}]},
    )
    monkeypatch.setattr(collectors, "_public_cache", (time.time() - collectors._PUBLIC_TTL - 30, cached_res))
    monkeypatch.setattr(
        collectors,
        "collect_notices",
        lambda vault, **kw: collectors.CollectResult("public", collectors.CODE_MAINTENANCE, "unreachable"),
    )
    r_stale = env.client.get("/v1/public/notices")
    assert r_stale.status_code == 200
    assert r_stale.json()["count"] == 1
    assert r_stale.json()["items"][0]["id"] == "public:notice:stale"

    # Empty cache + unreachable upstream degrades cleanly to empty list.
    monkeypatch.setattr(collectors, "_public_cache", None)
    r_empty = env.client.get("/v1/public/notices")
    assert r_empty.status_code == 200
    assert r_empty.json() == {"items": [], "count": 0}

    # Demo mode serves synthetic public notices with zero upstream calls.
    demo_settings = SimpleNamespace(**{**env.settings.__dict__, "demo": True})
    monkeypatch.setattr(env.mainmod, "settings", demo_settings)
    r_demo = env.client.get("/v1/public/notices?limit=2")
    assert r_demo.status_code == 200
    assert r_demo.json()["count"] == 2
    assert all(row["source"] == "public" for row in r_demo.json()["items"])

    monkeypatch.setattr(env.mainmod, "public_notices_limiter", ratelimit.SlidingLimiter(limit=2, window_seconds=300))
    assert env.client.get("/v1/public/notices").status_code == 200
    assert env.client.get("/v1/public/notices").status_code == 200
    throttled = env.client.get("/v1/public/notices")
    assert throttled.status_code == 429
    assert throttled.json()["detail"] == "too_many_attempts"


# --------------------------------------------------------------------------
# GET /v1/public/bus
# --------------------------------------------------------------------------

def test_public_bus_no_auth_matches_legacy_schema_and_ratelimits(env, monkeypatch):
    r_pub = env.client.get("/v1/public/bus")
    assert r_pub.status_code == 200
    assert r_pub.headers.get("Cache-Control") == "no-store"
    pub_json = r_pub.json()
    assert pub_json["ok"] is True
    assert pub_json["stop"] == "汕头大学（桑浦山校区）"
    assert isinstance(pub_json["lines"], list) and len(pub_json["lines"]) >= 1

    # Compare exact key structure with legacy /v1/bus.
    _, token = _make_user(env.db)
    r_leg = env.client.get("/v1/bus", headers=_auth(token))
    assert r_leg.status_code == 200
    assert set(pub_json.keys()) == set(r_leg.json().keys())
    assert set(pub_json["lines"][0].keys()) == set(r_leg.json()["lines"][0].keys())

    # Rate limiting.
    monkeypatch.setattr(env.mainmod, "public_bus_limiter", ratelimit.SlidingLimiter(limit=2, window_seconds=300))
    assert env.client.get("/v1/public/bus").status_code == 200
    assert env.client.get("/v1/public/bus").status_code == 200
    throttled = env.client.get("/v1/public/bus")
    assert throttled.status_code == 429
    assert throttled.json()["detail"] == "too_many_attempts"


# --------------------------------------------------------------------------
# GET /v1/public/charging
# --------------------------------------------------------------------------

def test_public_charging_no_auth_matches_legacy_schema_and_ratelimits(env, monkeypatch):
    fake_devices = [
        {
            "device": chargingmod.DEVICES[0],
            "state": {
                "ports": [{"num": 1, "state": 1, "label": "空闲"}],
                "counts": {"total": 20, "free": 1, "occupied": 19, "fault": 0},
                "collected_at": "2026-11-26T10:00:00+00:00",
                "stale": False,
            },
        }
    ]
    monkeypatch.setattr(chargingmod, "has_client", lambda: True)
    monkeypatch.setattr(chargingmod, "overview", lambda: fake_devices)

    r_pub = env.client.get("/v1/public/charging")
    assert r_pub.status_code == 200
    assert r_pub.headers.get("Cache-Control") == "no-store"
    r_leg = env.client.get("/v1/charging")
    assert r_leg.status_code == 200
    assert r_pub.json() == r_leg.json()
    assert r_pub.json()["devices"] == fake_devices
    assert r_pub.json()["verified_on"] == chargingmod.REGISTRY_VERIFIED_ON

    # Backend missing propagates 503 identically.
    monkeypatch.setattr(chargingmod, "has_client", lambda: False)
    r_503 = env.client.get("/v1/public/charging")
    assert r_503.status_code == 503
    assert r_503.json()["detail"] == "charging_backend_missing"

    # Rate limiting.
    monkeypatch.setattr(chargingmod, "has_client", lambda: True)
    monkeypatch.setattr(env.mainmod, "public_charging_limiter", ratelimit.SlidingLimiter(limit=2, window_seconds=300))
    assert env.client.get("/v1/public/charging").status_code == 200
    assert env.client.get("/v1/public/charging").status_code == 200
    throttled = env.client.get("/v1/public/charging")
    assert throttled.status_code == 429
    assert throttled.json()["detail"] == "too_many_attempts"


# --------------------------------------------------------------------------
# POST /v1/public/export/today + POST /v1/export/today/claim
# --------------------------------------------------------------------------

def test_public_export_today_roundtrip_single_use_and_med_camelcase(env):
    # 1. Direct main-campus payload without Authorization header.
    res = env.client.post("/v1/public/export/today", json=_sample_timetable())
    assert res.status_code == 200
    assert res.headers.get("Cache-Control") == "no-store"
    ticket = res.json()
    assert set(ticket.keys()) == {"code", "expires_in", "deep_link", "semester", "campus", "course_count"}
    code = ticket["code"]
    assert len(code) == 12
    assert set(code) <= set(today_export.CODE_ALPHABET)
    assert ticket["expires_in"] == 300
    assert ticket["deep_link"] == f"today://sheet/stu-import?code={code}"
    assert ticket["semester"] == "2026-2027-1"
    assert ticket["campus"] == "main"
    assert ticket["course_count"] == 1

    # Claim via existing /v1/export/today/claim.
    claim = env.client.post("/v1/export/today/claim", json={"code": f"{code[:4].lower()}-{code[4:8]} {code[8:]}"})
    assert claim.status_code == 200
    snap = claim.json()
    assert snap["version"] == 1
    assert snap["semester"] == "2026-2027-1"
    assert snap["campus"] == "main"
    assert snap["week1_monday"] == "2026-09-07"
    assert snap["total_weeks"] == 20
    assert snap["checked_at"] == "2026-11-26T10:00:00Z"
    assert snap["courses"][0]["meetings"][0] == {
        "id": "local-m1",
        "weekday": 2,
        "weeks": [1, 2, 3],
        "periods": [3, 4],
        "start": "10:00",
        "end": "11:40",
        "location": "致知楼 C304",
        "teacher": "周教授、陈助教",
        "raw_time": "周二 第3-4节",
    }

    # Single-use replay must fail atomically.
    replay = env.client.post("/v1/export/today/claim", json={"code": code})
    assert replay.status_code == 404
    assert replay.json()["detail"] == "ticket_invalid_or_expired"

    # Expired anonymous ticket (TTL = 300 s) must be rejected.
    t_exp = today_export.create_anonymous_export_ticket(env.db, _sample_timetable(), now_ts=1000)
    assert t_exp is not None
    assert today_export.claim_export_ticket(env.db, t_exp["code"], now_ts=1301) is None

    # 2. Wrapped medical camelCase + custom rawTime override payload.
    med_payload = {
        "campus": "med",
        "timetable": {
            "semester": "2026-2027-2",
            "courses": [
                {
                    "id": "med-c1",
                    "title": "临床诊断学",
                    "code": "MED108",
                    "className": "临床1班",
                    "teacher": "林主任",
                    "meetings": [
                        {
                            "weekday": 4,
                            "weeks": [2, 4],
                            "periods": [1, 2],
                            "rawTime": "周四 08:15-11:30",
                            "onlineInfo": "附一临床技能中心",
                        }
                    ],
                }
            ],
        },
    }
    res_med = env.client.post("/v1/public/export/today", json=med_payload)
    assert res_med.status_code == 200
    assert res_med.json()["campus"] == "med"
    claimed_med = env.client.post("/v1/export/today/claim", json={"code": res_med.json()["code"]}).json()
    assert claimed_med["campus"] == "med"
    assert claimed_med["week1_monday"] == "2027-03-01"
    assert claimed_med["total_weeks"] == 18
    m = claimed_med["courses"][0]["meetings"][0]
    assert m["start"] == "08:15" and m["end"] == "11:30"
    assert m["location"] == "附一临床技能中心"


def test_public_export_today_strips_all_pii_from_db_and_claim(env):
    pii_tokens = [
        "secret-student@stu.edu.cn",
        "2023100888",
        "super-secret-password-999",
        "eyJhbGciOiJIUzI1NiJ9.secret-jwt",
        "JSESSIONID=secret-cookie-value",
        "CASTGC=TGT-999-secret",
        "med-token-abcdef",
        "张三真名",
        "13800138000",
    ]
    polluted = {
        "semester": "2026-2027-1",
        "campus": "secret-student@stu.edu.cn",  # spoofed -> must normalize to "manual"
        "checked_at": "2023100888-not-iso",     # spoofed -> must become None
        "email": pii_tokens[0],
        "student_id": pii_tokens[1],
        "studentId": pii_tokens[1],
        "password": pii_tokens[2],
        "token": pii_tokens[3],
        "cookie": pii_tokens[4],
        "cookies": {"CASTGC": pii_tokens[5]},
        "med_token": pii_tokens[6],
        "user": {"id": "uid-123", "name": pii_tokens[7], "phone": pii_tokens[8]},
        "courses": [
            {
                "id": "c-clean",
                "title": "生物信息学",
                "course_code": "BIO301",
                "class_name": "生信1班",
                "teacher": "吴教授",
                "email": pii_tokens[0],
                "student_id": pii_tokens[1],
                "token": pii_tokens[3],
                "cookie": pii_tokens[4],
                "meetings": [
                    {
                        "id": "m-clean",
                        "weekday": 1,
                        "weeks": [1, 2],
                        "periods": [1, 2],
                        "location": "实验楼 302",
                        "email": pii_tokens[0],
                        "student_id": pii_tokens[1],
                        "password": pii_tokens[2],
                        "cookie": pii_tokens[4],
                        "notes": pii_tokens[7],
                    }
                ],
            }
        ],
    }

    res = env.client.post("/v1/public/export/today", json=polluted)
    assert res.status_code == 200
    body = res.json()
    assert body["campus"] == "manual"
    code = body["code"]

    # Check raw SQLite storage: zero PII and zero plaintext code.
    with env.db.conn() as c:
        stored = c.execute("SELECT * FROM today_export_tickets").fetchall()
    assert len(stored) == 1
    raw_row = json.dumps(dict(stored[0]), ensure_ascii=False)
    assert code not in raw_row
    for secret in pii_tokens:
        assert secret not in raw_row
    for forbidden_key in ("email", "student_id", "studentId", "password", "cookie", "cookies", "med_token", "phone"):
        assert forbidden_key not in raw_row

    # Check claimed snapshot: zero PII and spoofed fields sanitized.
    claim = env.client.post("/v1/export/today/claim", json={"code": code})
    assert claim.status_code == 200
    snap = claim.json()
    assert snap["campus"] == "manual"
    assert snap["checked_at"] is None
    assert set(snap.keys()) == {"version", "semester", "campus", "week1_monday", "total_weeks", "checked_at", "courses"}
    assert set(snap["courses"][0].keys()) == {"id", "title", "course_code", "class_name", "teacher", "teachers", "meetings"}
    assert set(snap["courses"][0]["meetings"][0].keys()) == {
        "id", "weekday", "weeks", "periods", "start", "end", "location", "teacher", "raw_time"
    }
    for secret in pii_tokens:
        assert secret not in claim.text


def test_public_export_today_rejects_empty_malformed_and_oversized_payloads(env):
    # Empty body / empty courses / courses with no valid meetings -> 404 no_timetable.
    assert env.client.post("/v1/public/export/today", content=b"").status_code == 404
    for empty_body in (
        {},
        {"semester": "2026-2027-1", "courses": []},
        {"semester": "2026-2027-1", "courses": [{"title": "无节次课程", "meetings": []}]},
    ):
        r = env.client.post("/v1/public/export/today", json=empty_body)
        assert r.status_code == 404
        assert r.json()["detail"] == "no_timetable"

    # Malformed JSON / non-object JSON -> 400.
    bad_json = env.client.post(
        "/v1/public/export/today",
        content=b"{not-json",
        headers={"Content-Type": "application/json"},
    )
    assert bad_json.status_code == 400
    assert bad_json.json()["detail"] == "invalid_json"

    not_obj = env.client.post("/v1/public/export/today", json=["not", "an", "object"])
    assert not_obj.status_code == 400
    assert not_obj.json()["detail"] == "invalid_timetable"

    # Raw JSON > 128 KB -> 413 payload_too_large (both HTTP and direct helper).
    huge_payload = _sample_timetable(padding="x" * (today_export.MAX_ANONYMOUS_JSON_BYTES + 256))
    r_huge = env.client.post("/v1/public/export/today", json=huge_payload)
    assert r_huge.status_code == 413
    assert r_huge.json()["detail"] == "payload_too_large"
    with pytest.raises(today_export.AnonymousExportError) as exc_info:
        today_export.create_anonymous_export_ticket(env.db, huge_payload)
    assert exc_info.value.code == "payload_too_large"
    assert exc_info.value.status_code == 413

    # Courses > 80 -> 413 too_many_courses (while 80 passes).
    def _tiny_course(i: int) -> dict:
        return {"title": f"C{i}", "meetings": [{"weekday": 1, "weeks": [1], "periods": [1]}]}

    r_80 = env.client.post("/v1/public/export/today", json={"courses": [_tiny_course(i) for i in range(80)]})
    assert r_80.status_code == 200
    assert r_80.json()["course_count"] == 80

    r_81 = env.client.post("/v1/public/export/today", json={"courses": [_tiny_course(i) for i in range(81)]})
    assert r_81.status_code == 413
    assert r_81.json()["detail"] == "too_many_courses"

    # Meetings per course > 30 -> 413 too_many_meetings (while 30 passes).
    def _tiny_meeting(i: int) -> dict:
        return {"weekday": (i % 7) + 1, "weeks": [1], "periods": [1]}

    r_30m = env.client.post(
        "/v1/public/export/today",
        json={"courses": [{"title": "C", "meetings": [_tiny_meeting(i) for i in range(30)]}]},
    )
    assert r_30m.status_code == 200

    r_31m = env.client.post(
        "/v1/public/export/today",
        json={"courses": [{"title": "C", "meetings": [_tiny_meeting(i) for i in range(31)]}]},
    )
    assert r_31m.status_code == 413
    assert r_31m.json()["detail"] == "too_many_meetings"


def test_public_export_today_ratelimit_is_independent(env, monkeypatch):
    monkeypatch.setattr(
        env.mainmod,
        "public_today_export_limiter",
        ratelimit.SlidingLimiter(limit=2, window_seconds=300),
    )

    assert env.client.post("/v1/public/export/today", json=_sample_timetable()).status_code == 200
    assert env.client.post("/v1/public/export/today", json=_sample_timetable()).status_code == 200
    throttled = env.client.post("/v1/public/export/today", json=_sample_timetable())
    assert throttled.status_code == 429
    assert throttled.json()["detail"] == "too_many_attempts"

    # Exhausting public_today_export_limiter must NOT throttle authenticated /v1/export/today.
    uid, token = _make_user(env.db)
    doc = imp.to_timetable_doc(_sample_timetable()["courses"], semester="2026-2027-1")
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="main")
    authed = env.client.post("/v1/export/today", headers=_auth(token))
    assert authed.status_code == 200


# --------------------------------------------------------------------------
# Legacy web endpoints remain 100% backward compatible
# --------------------------------------------------------------------------

def test_legacy_authenticated_endpoints_unaffected(env, monkeypatch):
    # Legacy endpoints still require authentication.
    assert env.client.get("/v1/notices").status_code == 401
    assert env.client.get("/v1/bus").status_code == 401
    assert env.client.post("/v1/export/today").status_code == 401

    uid, token = _make_user(env.db)
    # Seed 15 notices for this user: /v1/notices still enforces daily_cap=10.
    env.db.upsert_items(
        uid,
        "public",
        "notice",
        [
            {"id": f"n-{i}", "title": f"通知 {i}", "category": "学生通知", "published_at": "2026-11-26"}
            for i in range(15)
        ],
    )
    r_notices = env.client.get("/v1/notices?limit=50", headers=_auth(token))
    assert r_notices.status_code == 200
    assert r_notices.json()["count"] == 10
    assert r_notices.json()["daily_cap"] == 10

    r_bus = env.client.get("/v1/bus", headers=_auth(token))
    assert r_bus.status_code == 200
    assert r_bus.json()["ok"] is True

    monkeypatch.setattr(chargingmod, "has_client", lambda: True)
    monkeypatch.setattr(chargingmod, "overview", lambda: [])
    r_charging = env.client.get("/v1/charging")
    assert r_charging.status_code == 200
    assert r_charging.json()["devices"] == []

    doc = imp.to_timetable_doc(_sample_timetable()["courses"], semester="2026-2027-1")
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="main")
    r_export = env.client.post("/v1/export/today", headers=_auth(token))
    assert r_export.status_code == 200
    assert r_export.json()["course_count"] == 1
    assert r_export.json()["campus"] == "main"
