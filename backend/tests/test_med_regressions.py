import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from app import med_jw, med_parse

SEMESTER = "2026-2027-1"


def timetable(rooms):
    rows = [{"kcmc": "Course", "kcbh": "BIO1", "jcdm2": "03,04", "xq": 1,
             "zc": week, "jxcdmc": room} for week, room in enumerate(rooms, 1)]
    return {"code": 200, "kbList": [{"03": rows}]}


def test_room_changes_remain_separate_even_when_legacy_hashes_collide():
    seen = {}
    for i in range(10001):
        room = f"Room {i}"
        bucket = abs(hash(room)) % 10000
        if bucket in seen:
            first, second = seen[bucket], room
            break
        seen[bucket] = room
    else:
        raise AssertionError("10001 names must share at least one of 10000 buckets")
    doc = med_parse.parse_timetable(timetable([first, second, first]), semester=SEMESTER)
    meetings = doc["courses"][0]["meetings"]
    assert len(meetings) == 2
    assert {m["location"]: m["weeks"] for m in meetings} == {first: [1, 3], second: [2]}
    assert len({m["id"] for m in meetings}) == 2


def test_meeting_identity_is_stable_across_python_processes():
    script = ("import json; from app.med_parse import parse_timetable; "
              f"p = json.loads({json.dumps(timetable(['Room A']))!r}); "
              f"print(parse_timetable(p, semester={SEMESTER!r})['courses'][0]['meetings'][0]['id'])")
    backend = Path(__file__).resolve().parents[1]
    ids = [subprocess.check_output([sys.executable, "-B", "-c", script], cwd=backend,
                                  env={**os.environ, "PYTHONHASHSEED": str(seed)}, text=True).strip()
           for seed in (1, 2)]
    assert ids[0] == ids[1]


def test_distinct_non_contiguous_periods_have_distinct_meeting_ids():
    rows = [{"kcmc": "Course", "kcbh": "BIO1", "jcdm2": periods, "xq": 1,
             "zc": week, "jxcdmc": "Room A"}
            for week, periods in enumerate(("01,02,04", "01,03,04"), 1)]
    doc = med_parse.parse_timetable({"code": 200, "kbList": [{"01": rows}]}, semester=SEMESTER)
    meetings = doc["courses"][0]["meetings"]
    assert {tuple(m["periods"]) for m in meetings} == {(1, 2, 4), (1, 3, 4)}
    assert len(meetings) == len({m["id"] for m in meetings}) == 2


@pytest.mark.parametrize("body", [b"", b"<html>login required</html>", b'{}', b'{"code":200,"kbList":[]}'])
def test_http_401_is_expired_regardless_of_body(monkeypatch, body):
    monkeypatch.setattr(med_jw, "_raw_request", lambda *a, **kw: (401, body))
    with pytest.raises(med_jw.MedError) as error:
        med_jw.fetch_timetable("fake", semester=SEMESTER)
    assert error.value.code == "med_expired"


def test_business_401_still_reports_expired(monkeypatch):
    monkeypatch.setattr(med_jw, "_raw_request", lambda *a, **kw: (200, b'{"code":401}'))
    with pytest.raises(med_jw.MedError) as error:
        med_jw.fetch_timetable("fake", semester=SEMESTER)
    assert error.value.code == "med_expired"


@pytest.mark.parametrize("status", [403, 429, 500, 501])
def test_http_failure_is_not_a_valid_empty_timetable_or_expired_token(monkeypatch, status):
    monkeypatch.setattr(med_jw, "_raw_request", lambda *a, **kw: (status, b'{"code":200,"kbList":[]}'))
    with pytest.raises(med_jw.MedError) as error:
        med_jw.fetch_timetable("fake", semester=SEMESTER)
    assert error.value.code == "med_failed"


def test_successful_http_payload_still_parses(monkeypatch):
    payload = json.dumps(timetable(["Room A"])).encode()
    monkeypatch.setattr(med_jw, "_raw_request", lambda *a, **kw: (200, payload))
    doc = med_jw.fetch_timetable("fake", semester=SEMESTER)
    assert doc["courses"][0]["meetings"][0]["location"] == "Room A"
