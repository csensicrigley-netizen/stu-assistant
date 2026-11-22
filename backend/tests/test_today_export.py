"""Tests for one-time timetable export tickets for the `today` companion app."""
from __future__ import annotations

import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app import auth as authlib
from app import config as configmod
from app import med_parse
from app import ratelimit
from app import timetable_import as imp
from app import today_export
from app.db import DB


@pytest.fixture()
def env(monkeypatch, tmp_path):
    db_path = tmp_path / "app.db"
    jwt_secret = "test-only-today-export-signing-key-0123456789abcdef"
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
    db = DB(db_path)
    return SimpleNamespace(db=db, client=TestClient(mainmod.app), mainmod=mainmod)


def _make_user(db: DB, email: str = "student@example.invalid") -> tuple[str, str]:
    user = db.create_user(email, "not-a-real-hash", student_id_h="sid-hash-9999")
    token = authlib.issue_token(user["id"], ver=0)
    return user["id"], token


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def test_main_campus_export_and_claim_roundtrip(env):
    uid, token = _make_user(env.db, "main.student@stu.example.invalid")
    env.db.mark_bound(uid, "jw", "sid-hash-main")
    env.db.replace_kind(
        uid,
        "jw",
        "timetable",
        [
            {
                "external_id": "doc:2026-2027-1",
                "title": "2026-2027-1 课表",
                "semester": "2026-2027-1",
                "status": "ok",
                "checked_at": "2026-11-21T04:00:00Z",
                "courses": [
                    {
                        "id": "jw-c1",
                        "title": "高等数学",
                        "course_code": "MATH101",
                        "class_name": "高数01班",
                        "teachers": ["张教授", "李助教"],
                        "meetings": [
                            {
                                "id": "jw-m1",
                                "weekday": 1,
                                "weeks": [3, 1, 5, 3],
                                "periods": [2, 1],
                                "teacher": "",
                                "location": "桑浦山 E305",
                                "raw_time": "周一 第1-2节",
                            },
                            {
                                "id": "jw-m2",
                                "weekday": 4,
                                "weeks": [1, 2, 3, 4],
                                "periods": [11, 12, 13],
                                "teacher": "李助教",
                                "building": "东海岸",
                                "room": "D201",
                                "location": "",
                                "raw_time": "",
                            },
                        ],
                    }
                ],
            }
        ],
    )

    # Empty body must be accepted.
    res = env.client.post("/v1/export/today", headers=_auth(token))
    assert res.status_code == 200
    assert res.headers.get("Cache-Control") == "no-store"
    exported = res.json()
    code = exported["code"]
    assert len(code) == 12
    assert set(code) <= set(today_export.CODE_ALPHABET)
    assert exported["expires_in"] == 300
    assert exported["deep_link"] == f"today://sheet/stu-import?code={code}"
    assert exported["semester"] == "2026-2027-1"
    assert exported["campus"] == "main"
    assert exported["course_count"] == 1

    # Claim WITHOUT Bearer token, with lowercase + spaces + hyphens.
    spaced = f" {code[:4].lower()}-{code[4:8]} {code[8:].lower()} "
    claim = env.client.post("/v1/export/today/claim", json={"code": spaced})
    assert claim.status_code == 200
    assert claim.headers.get("Cache-Control") == "no-store"
    snap = claim.json()
    assert snap["version"] == 1
    assert snap["semester"] == "2026-2027-1"
    assert snap["campus"] == "main"
    assert snap["week1_monday"] == "2026-09-07"
    assert snap["total_weeks"] == 20
    assert snap["checked_at"] == "2026-11-21T04:00:00Z"
    assert len(snap["courses"]) == 1

    c = snap["courses"][0]
    assert c["id"] == "jw-c1"
    assert c["title"] == "高等数学"
    assert c["course_code"] == "MATH101"
    assert c["class_name"] == "高数01班"
    assert c["teacher"] == "张教授、李助教"
    assert c["teachers"] == ["张教授", "李助教"]
    assert len(c["meetings"]) == 2

    m1, m2 = c["meetings"]
    assert m1 == {
        "id": "jw-m1",
        "weekday": 1,
        "weeks": [1, 3, 5],
        "periods": [1, 2],
        "start": "08:00",
        "end": "09:40",
        "location": "桑浦山 E305",
        "teacher": "张教授、李助教",
        "raw_time": "周一 第1-2节",
    }
    assert m2 == {
        "id": "jw-m2",
        "weekday": 4,
        "weeks": [1, 2, 3, 4],
        "periods": [11, 12, 13],
        "start": "19:20",
        "end": "21:55",
        "location": "东海岸 D201",
        "teacher": "李助教",
        "raw_time": "",
    }


def test_medical_camelcase_and_custom_rawtime_override(env):
    uid, token = _make_user(env.db, "med.student@stu.example.invalid")
    env.db.med_session_save(uid, username="2023med001", name="医学生", token="secret-med-session-token-xyz")
    kb_payload = {
        "code": 200,
        "kbList": [
            {
                "01": [
                    {
                        "kcmc": "人体解剖学实验",
                        "teaxms": "王主任",
                        "jxcdmc": "附一临床技能中心302",
                        "szxqmc": "医学院",
                        "kcbh": "MED201",
                        "jxbmc": "临床五年制1班",
                        "xq": 1,
                        "zc": 1,
                        "jcdm2": "01,02",
                        # Non-standard clinical/lab timing must override the 08:00-09:40 period table.
                        "qssj": "08:15:00",
                        "jssj": "11:30:00",
                    },
                    {
                        "kcmc": "人体解剖学实验",
                        "teaxms": "陈医生",
                        "jxcdmc": "附一临床技能中心302",
                        "szxqmc": "医学院",
                        "kcbh": "MED201",
                        "jxbmc": "临床五年制1班",
                        "xq": 1,
                        "zc": 3,
                        "jcdm2": "01,02",
                        "qssj": "08:15:00",
                        "jssj": "11:30:00",
                    },
                ],
            },
            {},
            {
                "06": [
                    {
                        "kcmc": "人体解剖学实验",
                        "teaxms": "王主任",
                        "jxcdmc": "科教楼205",
                        "szxqmc": "医学院",
                        "kcbh": "MED201",
                        "jxbmc": "临床五年制1班",
                        "xq": 3,
                        "zc": 2,
                        "jcdm2": "06,07",
                        # No qssj/jssj -> falls back to STU period table (14:00-15:40).
                        "qssj": "",
                        "jssj": "",
                    }
                ]
            },
        ],
    }
    med_doc = med_parse.parse_timetable(kb_payload, semester="2026-2027-2")
    imp.save_manual(env.db, uid, "2026-2027-2", med_doc, school="med", note="医学院自动同步")

    res = env.client.post("/v1/export/today", headers=_auth(token), json={"semester": "2026-2027-2"})
    assert res.status_code == 200
    body = res.json()
    assert body["campus"] == "med"
    assert body["semester"] == "2026-2027-2"
    assert body["course_count"] == 1

    claimed = env.client.post("/v1/export/today/claim", json={"code": body["code"]})
    assert claimed.status_code == 200
    snap = claimed.json()
    assert snap["campus"] == "med"
    assert snap["week1_monday"] == "2027-03-01"
    assert snap["total_weeks"] == 18

    course = snap["courses"][0]
    assert course["title"] == "人体解剖学实验"
    assert course["course_code"] == "MED201"
    assert course["class_name"] == "临床五年制1班"
    assert len(course["meetings"]) == 2

    m_lab, m_theory = course["meetings"]
    assert m_lab["weekday"] == 1
    assert m_lab["weeks"] == [1, 3]
    assert m_lab["periods"] == [1, 2]
    assert m_lab["start"] == "08:15"
    assert m_lab["end"] == "11:30"
    assert "08:15-11:30" in m_lab["raw_time"]
    assert m_lab["location"] == "医学院 附一临床技能中心302"
    assert "王主任" in m_lab["teacher"] and "陈医生" in m_lab["teacher"]

    assert m_theory["weekday"] == 3
    assert m_theory["weeks"] == [2]
    assert m_theory["periods"] == [6, 7]
    assert m_theory["start"] == "14:00"
    assert m_theory["end"] == "15:40"


def test_single_use_replay_and_invalid_codes(env):
    uid, token = _make_user(env.db)
    doc = imp.to_timetable_doc(
        [
            {
                "id": "c1",
                "title": "数据结构",
                "teacher": "刘老师",
                "meetings": [
                    {
                        "id": "m1",
                        "weekday": 2,
                        "weeks": [1, 2],
                        "periods": [3, 4],
                        "location": "E101",
                        "teacher": "刘老师",
                        "raw_time": "",
                    }
                ],
            }
        ],
        semester="2025-2026-2",
    )
    imp.save_manual(env.db, uid, "2025-2026-2", doc, school="other")

    exported = env.client.post("/v1/export/today", headers=_auth(token), json={}).json()
    assert exported["campus"] == "manual"
    code = exported["code"]

    first = env.client.post("/v1/export/today/claim", json={"code": code})
    assert first.status_code == 200
    assert first.json()["week1_monday"] is None
    assert first.json()["total_weeks"] is None

    # Replay must be rejected atomically.
    second = env.client.post("/v1/export/today/claim", json={"code": code})
    assert second.status_code == 404
    assert second.json()["detail"] == "ticket_invalid_or_expired"

    # Nonexistent or malformed codes must return the same 404.
    for bad in ("", "   ", "222222222222", "short", "0000OOOO1111"):
        r = env.client.post("/v1/export/today/claim", json={"code": bad})
        assert r.status_code == 404
        assert r.json()["detail"] == "ticket_invalid_or_expired"


def test_expired_ticket_rejected_and_old_tickets_pruned(env):
    uid, _ = _make_user(env.db)
    doc = imp.to_timetable_doc(
        [
            {
                "id": "c1",
                "title": "计算机网络",
                "teacher": "周老师",
                "meetings": [
                    {
                        "id": "m1",
                        "weekday": 5,
                        "weeks": [1],
                        "periods": [6, 7],
                        "location": "E202",
                        "teacher": "周老师",
                    }
                ],
            }
        ],
        semester="2026-2027-1",
    )
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="main")

    # Ticket created at t=1000, expires at t=1300.
    t1 = today_export.create_export_ticket(env.db, uid, now_ts=1000)
    assert t1 is not None
    assert t1["campus"] == "main"
    # At t=1301 it is expired.
    assert today_export.claim_export_ticket(env.db, t1["code"], now_ts=1301) is None

    # Creating a ticket at t=1950 (> 10 minutes past expires_at=1300) prunes t1.
    t2 = today_export.create_export_ticket(env.db, uid, now_ts=1950)
    assert t2 is not None
    with env.db.conn() as c:
        rows = c.execute("SELECT code_hash FROM today_export_tickets").fetchall()
    hashes = {r["code_hash"] for r in rows}
    assert today_export.hash_code(t1["code"]) not in hashes
    assert today_export.hash_code(t2["code"]) in hashes


def test_empty_or_missing_timetable_returns_404(env):
    uid, token = _make_user(env.db)

    # Requires authentication.
    unauth = env.client.post("/v1/export/today")
    assert unauth.status_code == 401

    # No timetable yet -> 404 no_timetable.
    missing = env.client.post("/v1/export/today", headers=_auth(token))
    assert missing.status_code == 404
    assert missing.json()["detail"] == "no_timetable"

    # Empty courses list -> 404 no_timetable.
    env.db.replace_kind(
        uid,
        "jw",
        "timetable",
        [{"external_id": "doc:2026-2027-1", "title": "空课表", "semester": "2026-2027-1", "courses": []}],
    )
    empty = env.client.post("/v1/export/today", headers=_auth(token))
    assert empty.status_code == 404
    assert empty.json()["detail"] == "no_timetable"


def test_multi_source_campus_prefers_winning_record(env):
    uid, token = _make_user(env.db)
    course = {
        "id": "c1",
        "title": "生物化学",
        "teacher": "吴老师",
        "meetings": [
            {"id": "m1", "weekday": 1, "weeks": [1, 2], "periods": [1, 2], "location": "A101", "teacher": "吴老师"}
        ],
    }
    # First save a medical manual timetable for 2026-2027-1...
    imp.save_manual(env.db, uid, "2026-2027-1", imp.to_timetable_doc([course], semester="2026-2027-1"), school="med")
    # ...then overwrite with a newer jw sync for the same semester.
    with env.db.conn() as c:
        c.execute("UPDATE items SET updated_at = updated_at - 100 WHERE user_id=? AND source='manual'", (uid,))
    env.db.replace_kind(
        uid,
        "jw",
        "timetable",
        [
            {
                "external_id": "doc:2026-2027-1",
                "title": "2026-2027-1 课表",
                "semester": "2026-2027-1",
                "courses": [course],
            }
        ],
    )
    res_jw = env.client.post("/v1/export/today", headers=_auth(token))
    assert res_jw.status_code == 200
    assert res_jw.json()["campus"] == "main"

    # Now make the medical record newer again -> campus must flip to "med".
    with env.db.conn() as c:
        c.execute("UPDATE items SET updated_at = updated_at + 500 WHERE user_id=? AND source='manual'", (uid,))
    res_med = env.client.post("/v1/export/today", headers=_auth(token))
    assert res_med.status_code == 200
    assert res_med.json()["campus"] == "med"


def test_ticket_and_claim_contain_zero_user_pii(env):
    secret_email = "private-user-email@stu.example.invalid"
    secret_med_user = "2024999999"
    secret_med_token = "med-live-token-do-not-leak"
    uid, token = _make_user(env.db, secret_email)
    env.db.med_session_save(uid, username=secret_med_user, name="隐秘姓名", token=secret_med_token)
    doc = imp.to_timetable_doc(
        [
            {
                "id": "c1",
                "title": "生理学",
                "teacher": "孙老师",
                "meetings": [
                    {"id": "m1", "weekday": 2, "weeks": [1], "periods": [3, 4], "location": "医学院 B201", "teacher": "孙老师"}
                ],
            }
        ],
        semester="2026-2027-1",
    )
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="med")

    exported = env.client.post("/v1/export/today", headers=_auth(token)).json()
    code = exported["code"]

    with env.db.conn() as c:
        cols = {row["name"] for row in c.execute("PRAGMA table_info(today_export_tickets)")}
        assert cols == {"code_hash", "payload_json", "created_at", "expires_at", "claimed_at"}
        stored = c.execute("SELECT * FROM today_export_tickets").fetchall()
    assert len(stored) == 1
    raw_row = json.dumps(dict(stored[0]), ensure_ascii=False)
    # Raw code must never be stored in plaintext; only its SHA-256 hash.
    assert code not in raw_row
    for forbidden in (uid, secret_email, secret_med_user, secret_med_token, token, "隐秘姓名", "sid-hash-9999"):
        assert forbidden not in raw_row

    claimed = env.client.post("/v1/export/today/claim", json={"code": code})
    assert claimed.status_code == 200
    claimed_text = claimed.text
    for forbidden in (uid, secret_email, secret_med_user, secret_med_token, token, "隐秘姓名", "sid-hash-9999"):
        assert forbidden not in claimed_text


def test_rate_limits_on_export_and_claim(env, monkeypatch):
    monkeypatch.setattr(env.mainmod, "today_export_limiter", ratelimit.SlidingLimiter(limit=2, window_seconds=300))
    monkeypatch.setattr(env.mainmod, "today_claim_limiter", ratelimit.SlidingLimiter(limit=2, window_seconds=300))
    uid, token = _make_user(env.db)
    doc = imp.to_timetable_doc(
        [
            {
                "id": "c1",
                "title": "概率论",
                "teacher": "郑老师",
                "meetings": [
                    {"id": "m1", "weekday": 3, "weeks": [1], "periods": [1, 2], "location": "E102", "teacher": "郑老师"}
                ],
            }
        ],
        semester="2026-2027-1",
    )
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="main")

    assert env.client.post("/v1/export/today", headers=_auth(token)).status_code == 200
    assert env.client.post("/v1/export/today", headers=_auth(token)).status_code == 200
    throttled_export = env.client.post("/v1/export/today", headers=_auth(token))
    assert throttled_export.status_code == 429
    assert throttled_export.json()["detail"] == "too_many_attempts"

    assert env.client.post("/v1/export/today/claim", json={"code": "222222222222"}).status_code == 404
    assert env.client.post("/v1/export/today/claim", json={"code": "222222222222"}).status_code == 404
    throttled_claim = env.client.post("/v1/export/today/claim", json={"code": "222222222222"})
    assert throttled_claim.status_code == 429
    assert throttled_claim.json()["detail"] == "too_many_attempts"


def test_online_info_fallback_and_empty_periods_rawtime_preserved(env):
    uid, token = _make_user(env.db, "online.med@stu.example.invalid")
    doc = {
        "semester": "2026-2027-1",
        "status": "ok",
        "checked_at": "2026-11-21T05:00:00Z",
        "courses": [
            {
                "id": "med-online-1",
                "title": "临床见习与线上讨论",
                "code": "MED305",
                "className": "临床1班",
                "teacher": "林主任",
                "meetings": [
                    {
                        "id": "m-online-snake",
                        "weekday": 2,
                        "weeks": [1, 2],
                        "periods": [1, 2],
                        "location": "",
                        "online_info": "线上腾讯会议",
                        "raw_time": "",
                    },
                    {
                        "id": "m-online-camel-no-periods",
                        "weekday": 1,
                        "weeks": [3, 5],
                        "periods": [],
                        "location": "",
                        "onlineInfo": "雨课堂直播",
                        "rawTime": "周一 08:30-10:10",
                    },
                    {
                        "id": "m-physical-wins",
                        "weekday": 4,
                        "weeks": [1],
                        "periods": [3, 4],
                        "location": "医学院 教学楼201",
                        "onlineInfo": "同步录播",
                        "rawTime": "",
                    },
                ],
            }
        ],
    }
    imp.save_manual(env.db, uid, "2026-2027-1", doc, school="med", note="医学院自动同步")

    exported = env.client.post("/v1/export/today", headers=_auth(token)).json()
    assert exported["campus"] == "med"
    assert exported["course_count"] == 1

    claimed = env.client.post("/v1/export/today/claim", json={"code": exported["code"]})
    assert claimed.status_code == 200
    meetings = claimed.json()["courses"][0]["meetings"]
    assert len(meetings) == 3

    by_id = {m["id"]: m for m in meetings}
    assert by_id["m-online-snake"]["location"] == "线上腾讯会议"
    assert by_id["m-online-snake"]["start"] == "08:00"
    assert by_id["m-online-snake"]["end"] == "09:40"

    assert by_id["m-online-camel-no-periods"]["periods"] == []
    assert by_id["m-online-camel-no-periods"]["start"] == "08:30"
    assert by_id["m-online-camel-no-periods"]["end"] == "10:10"
    assert by_id["m-online-camel-no-periods"]["location"] == "雨课堂直播"

    # Explicit physical location must take precedence over onlineInfo.
    assert by_id["m-physical-wins"]["location"] == "医学院 教学楼201"

