"""One-time timetable export tickets for the companion `today` app.

Security & privacy contract:
  * Tickets store ONLY normalized timetable snapshots (never email, student_id,
    password, JWT, or session tokens).
  * Codes are 12 chars from an unambiguous URL-safe uppercase alphabet
    (32^12 = 60 bits of entropy), hashed with SHA-256 at rest, valid for
    300 seconds, and atomically single-use.
  * Normalizes all three timetable sources identically:
      - main campus (`jw`, snake_case)
      - SUMC medical school (`manual` + `school='med'`, camelCase + `rawTime`)
      - manually imported timetables (`manual`)
"""
from __future__ import annotations

import hashlib
import re
import secrets
import sqlite3
from typing import Any

from .db import DB

CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
_CODE_SET = frozenset(CODE_ALPHABET)
CODE_LENGTH = 12
TICKET_TTL_SECONDS = 300
DEEP_LINK_TEMPLATE = "today://sheet/stu-import?code={code}"

# Authoritative STU 13-period schedule (shared by main campus & SUMC).
PERIOD_TIMES: dict[int, tuple[str, str]] = {
    1: ("08:00", "08:45"),
    2: ("08:55", "09:40"),
    3: ("10:00", "10:45"),
    4: ("10:55", "11:40"),
    5: ("11:50", "12:35"),
    6: ("14:00", "14:45"),
    7: ("14:55", "15:40"),
    8: ("16:00", "16:45"),
    9: ("16:55", "17:40"),
    10: ("17:50", "18:35"),
    11: ("19:20", "20:05"),
    12: ("20:15", "21:00"),
    13: ("21:10", "21:55"),
}

# Authoritative STU academic calendar anchors (week 1 Monday, total weeks).
TERM_ANCHORS: dict[str, tuple[str, int]] = {
    "2026-2027-1": ("2026-09-07", 20),
    "2026-2027-2": ("2027-03-01", 18),
}

_TIME_RANGE_RE = re.compile(
    r"(?<!\d)(\d{1,2}):(\d{2})(?::\d{2})?\s*[-–—~至到]\s*(\d{1,2}):(\d{2})(?::\d{2})?(?!\d)"
)


def _text(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def _to_int(value: Any) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    raw = _text(value)
    if raw and raw.lstrip("-").isdigit():
        return int(raw)
    return None


def _int_list(values: Any, *, lo: int, hi: int) -> list[int]:
    if not isinstance(values, list):
        return []
    out: set[int] = set()
    for item in values:
        num = _to_int(item)
        if num is not None and lo <= num <= hi:
            out.add(num)
    return sorted(out)


def extract_time_range(raw_time: str) -> tuple[str, str] | None:
    """Extract the first legal HH:MM-HH:MM range (with end > start) from raw_time."""
    if not raw_time:
        return None
    for match in _TIME_RANGE_RE.finditer(raw_time):
        sh, sm, eh, em = (int(match.group(i)) for i in (1, 2, 3, 4))
        if not (0 <= sh <= 23 and 0 <= sm <= 59 and 0 <= eh <= 23 and 0 <= em <= 59):
            continue
        if (eh * 60 + em) <= (sh * 60 + sm):
            continue
        return f"{sh:02d}:{sm:02d}", f"{eh:02d}:{em:02d}"
    return None


def periods_to_time_range(periods: list[int]) -> tuple[str, str] | None:
    """Map sorted STU periods (1..13) to first period start .. last period end."""
    if not periods:
        return None
    first = PERIOD_TIMES.get(periods[0])
    last = PERIOD_TIMES.get(periods[-1])
    if not first or not last:
        return None
    return first[0], last[1]


def generate_code() -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(CODE_LENGTH))


def normalize_code(raw: str) -> str:
    cleaned = re.sub(r"[\s\-–—]+", "", str(raw or "")).upper()
    return cleaned


def hash_code(code: str) -> str:
    return hashlib.sha256(code.encode("ascii")).hexdigest()


def select_timetable_row(db: DB, uid: str, *, semester: str = "") -> dict | None:
    """Select the winning timetable row in `items`, matching GET /v1/timetable."""
    docs = db.items_of(uid, "timetable", limit=20)
    if not docs:
        return None
    wanted = _text(semester)
    if wanted:
        matched = [d for d in docs if _text((d.get("payload") or {}).get("semester")) == wanted]
        return (matched or docs)[0]
    return docs[0]


def detect_campus(db: DB, uid: str, row_source: str, semester: str) -> str:
    """Identify campus strictly from the winning timetable record."""
    src = _text(row_source).lower()
    if src == "jw":
        return "main"
    if src == "manual":
        manual = db.tt_manual_get(uid, semester) if semester else None
        if manual is not None:
            school = _text(manual.get("school")).lower()
            if school == "med":
                return "med"
            if school == "main":
                return "main"
    return "manual"


def normalize_meeting(raw: Any, *, course_id: str, course_teacher: str) -> dict | None:
    if not isinstance(raw, dict):
        return None
    weekday = _to_int(raw.get("weekday"))
    if weekday is None or not (1 <= weekday <= 7):
        return None

    weeks = _int_list(raw.get("weeks"), lo=1, hi=52)
    if not weeks:
        return None

    periods = _int_list(raw.get("periods"), lo=1, hi=13)
    raw_time = _text(raw.get("raw_time") or raw.get("rawTime"))

    span = extract_time_range(raw_time) or periods_to_time_range(periods)
    if span is None:
        return None
    start, end = span

    location = _text(raw.get("location"))
    if not location:
        building = _text(raw.get("building"))
        room = _text(raw.get("room"))
        location = f"{building} {room}".strip() if (building or room) else ""
    if not location:
        location = _text(raw.get("online_info") or raw.get("onlineInfo"))

    teacher = _text(raw.get("teacher")) or course_teacher
    mid = _text(raw.get("id"))
    if not mid:
        sig = f"{course_id}|{weekday}|{periods}|{start}|{end}|{location}|{weeks}"
        mid = "m-" + hashlib.sha256(sig.encode("utf-8")).hexdigest()[:16]

    return {
        "id": mid,
        "weekday": weekday,
        "weeks": weeks,
        "periods": periods,
        "start": start,
        "end": end,
        "location": location,
        "teacher": teacher,
        "raw_time": raw_time,
    }


def normalize_course(raw: Any) -> dict | None:
    if not isinstance(raw, dict):
        return None
    title = _text(raw.get("title"))
    if not title:
        return None

    course_code = _text(raw.get("course_code") or raw.get("code"))
    class_name = _text(raw.get("class_name") or raw.get("className"))

    teachers: list[str] = []
    seen_teachers: set[str] = set()
    raw_teachers = raw.get("teachers")
    if isinstance(raw_teachers, list):
        for entry in raw_teachers:
            name = _text(entry)
            if name and name not in seen_teachers:
                seen_teachers.add(name)
                teachers.append(name)

    teacher = _text(raw.get("teacher")) or "、".join(teachers)
    if not teachers and teacher:
        for part in teacher.split("、"):
            name = part.strip()
            if name and name not in seen_teachers:
                seen_teachers.add(name)
                teachers.append(name)

    cid = _text(raw.get("id"))
    if not cid:
        sig = f"{title}|{course_code}|{class_name}"
        cid = "c-" + hashlib.sha256(sig.encode("utf-8")).hexdigest()[:16]

    raw_meetings = raw.get("meetings")
    meetings: list[dict] = []
    if isinstance(raw_meetings, list):
        for item in raw_meetings:
            norm = normalize_meeting(item, course_id=cid, course_teacher=teacher)
            if norm is not None:
                meetings.append(norm)

    if not meetings:
        return None

    return {
        "id": cid,
        "title": title,
        "course_code": course_code,
        "class_name": class_name,
        "teacher": teacher,
        "teachers": teachers,
        "meetings": meetings,
    }


def build_snapshot(db: DB, uid: str, *, semester: str = "") -> dict | None:
    """Build a strictly whitelisted, PII-free timetable snapshot for today."""
    chosen = select_timetable_row(db, uid, semester=semester)
    if chosen is None:
        return None
    payload = chosen.get("payload") if isinstance(chosen.get("payload"), dict) else {}
    raw_courses = payload.get("courses")
    if not isinstance(raw_courses, list) or not raw_courses:
        return None

    courses: list[dict] = []
    for raw in raw_courses:
        norm = normalize_course(raw)
        if norm is not None:
            courses.append(norm)
    if not courses:
        return None

    resolved_semester = _text(payload.get("semester"))
    campus = detect_campus(db, uid, str(chosen.get("source") or ""), resolved_semester)
    anchor = TERM_ANCHORS.get(resolved_semester)
    week1_monday = anchor[0] if anchor else None
    total_weeks = anchor[1] if anchor else None

    checked_raw = payload.get("checked_at")
    checked_at = _text(checked_raw) or None

    return {
        "version": 1,
        "semester": resolved_semester,
        "campus": campus,
        "week1_monday": week1_monday,
        "total_weeks": total_weeks,
        "checked_at": checked_at,
        "courses": courses,
    }


def create_export_ticket(
    db: DB,
    uid: str,
    *,
    semester: str = "",
    now_ts: int | None = None,
) -> dict | None:
    """Create a one-time export ticket (300 s TTL) or return None if no timetable."""
    snapshot = build_snapshot(db, uid, semester=semester)
    if snapshot is None:
        return None

    for _ in range(4):
        code = generate_code()
        try:
            db.today_ticket_create(
                hash_code(code),
                snapshot,
                ttl_seconds=TICKET_TTL_SECONDS,
                now_ts=now_ts,
            )
            return {
                "code": code,
                "expires_in": TICKET_TTL_SECONDS,
                "deep_link": DEEP_LINK_TEMPLATE.format(code=code),
                "semester": snapshot["semester"],
                "campus": snapshot["campus"],
                "course_count": len(snapshot["courses"]),
            }
        except sqlite3.IntegrityError:
            continue
    raise RuntimeError("failed_to_allocate_ticket")


def claim_export_ticket(db: DB, raw_code: str, *, now_ts: int | None = None) -> dict | None:
    """Atomically claim a one-time export ticket by raw code."""
    code = normalize_code(raw_code)
    if len(code) != CODE_LENGTH or any(ch not in _CODE_SET for ch in code):
        return None
    return db.today_ticket_claim(hash_code(code), now_ts=now_ts)
