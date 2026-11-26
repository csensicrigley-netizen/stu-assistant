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
import json
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

# Strict size / shape bounds for unauthenticated anonymous timetable export.
MAX_ANONYMOUS_JSON_BYTES = 128 * 1024
MAX_ANONYMOUS_COURSES = 80
MAX_ANONYMOUS_MEETINGS_PER_COURSE = 30
_MAX_ID_LEN = 64
_MAX_SEMESTER_LEN = 32
_MAX_TITLE_LEN = 120
_MAX_CODE_LEN = 64
_MAX_CLASS_LEN = 120
_MAX_TEACHER_LEN = 120
_MAX_TEACHERS_COUNT = 20
_MAX_LOCATION_LEN = 160
_MAX_RAW_TIME_LEN = 160

_ISO_TS_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?(?:[Zz]|[+-]\d{2}:?\d{2})?)?$"
)
_CTRL_CHARS_RE = re.compile(r"[\x00-\x1f\x7f]+")


class AnonymousExportError(ValueError):
    """Validation / size error for unauthenticated anonymous timetable export."""

    def __init__(self, code: str, *, status_code: int = 400):
        super().__init__(code)
        self.code = code
        self.status_code = status_code

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
    explicit_start = _text(raw.get("start"))
    explicit_end = _text(raw.get("end"))

    span = (
        extract_time_range(raw_time)
        or (extract_time_range(f"{explicit_start}-{explicit_end}") if explicit_start and explicit_end else None)
        or periods_to_time_range(periods)
    )
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


def _clean_text(value: Any, *, max_len: int) -> str:
    cleaned = _CTRL_CHARS_RE.sub(" ", _text(value)).strip()
    if len(cleaned) > max_len:
        return cleaned[:max_len].strip()
    return cleaned


def _normalize_campus(raw_campus: Any, raw_school: Any = None, raw_source: Any = None) -> str:
    for candidate in (raw_campus, raw_school, raw_source):
        val = _text(candidate).lower()
        if val in {"main", "jw"}:
            return "main"
        if val == "med":
            return "med"
        if val == "manual":
            return "manual"
    return "manual"


def _sanitize_checked_at(raw: Any) -> str | None:
    text = _text(raw)
    if not text or len(text) > 64 or not _ISO_TS_RE.fullmatch(text):
        return None
    return text


def _bound_course(course: dict) -> dict | None:
    title = _clean_text(course.get("title"), max_len=_MAX_TITLE_LEN)
    if not title:
        return None
    course_code = _clean_text(course.get("course_code"), max_len=_MAX_CODE_LEN)
    class_name = _clean_text(course.get("class_name"), max_len=_MAX_CLASS_LEN)

    teachers: list[str] = []
    seen: set[str] = set()
    for name in course.get("teachers") or []:
        cleaned = _clean_text(name, max_len=_MAX_TEACHER_LEN)
        if cleaned and cleaned not in seen:
            seen.add(cleaned)
            teachers.append(cleaned)
            if len(teachers) >= _MAX_TEACHERS_COUNT:
                break
    teacher = _clean_text(course.get("teacher"), max_len=_MAX_TEACHER_LEN) or "、".join(teachers)[:_MAX_TEACHER_LEN]

    cid = _clean_text(course.get("id"), max_len=_MAX_ID_LEN)
    if not cid:
        sig = f"{title}|{course_code}|{class_name}"
        cid = "c-" + hashlib.sha256(sig.encode("utf-8")).hexdigest()[:16]

    meetings: list[dict] = []
    for m in course.get("meetings") or []:
        mid = _clean_text(m.get("id"), max_len=_MAX_ID_LEN)
        location = _clean_text(m.get("location"), max_len=_MAX_LOCATION_LEN)
        m_teacher = _clean_text(m.get("teacher"), max_len=_MAX_TEACHER_LEN)
        raw_time = _clean_text(m.get("raw_time"), max_len=_MAX_RAW_TIME_LEN)
        if not mid:
            sig = f"{cid}|{m['weekday']}|{m['periods']}|{m['start']}|{m['end']}|{location}|{m['weeks']}"
            mid = "m-" + hashlib.sha256(sig.encode("utf-8")).hexdigest()[:16]
        meetings.append({
            "id": mid,
            "weekday": int(m["weekday"]),
            "weeks": list(m["weeks"]),
            "periods": list(m["periods"]),
            "start": str(m["start"]),
            "end": str(m["end"]),
            "location": location,
            "teacher": m_teacher,
            "raw_time": raw_time,
        })
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


def build_anonymous_snapshot(raw_timetable: Any) -> dict | None:
    """Validate bounds and build a strictly whitelisted, PII-free snapshot from client JSON.

    Any non-whitelist keys (email, student_id, token, cookie, password, etc.) at
    any nesting level are stripped and never persisted.
    """
    if not isinstance(raw_timetable, dict):
        raise AnonymousExportError("invalid_timetable", status_code=400)

    try:
        encoded = json.dumps(raw_timetable, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    except (TypeError, ValueError):
        raise AnonymousExportError("invalid_timetable", status_code=400) from None
    if len(encoded) > MAX_ANONYMOUS_JSON_BYTES:
        raise AnonymousExportError("payload_too_large", status_code=413)

    # Support both direct timetable payload {"semester", "campus", "courses": [...]}
    # and wrapped {"timetable": {...}, "campus": ...}.
    doc = raw_timetable
    outer = raw_timetable
    if "courses" not in raw_timetable and isinstance(raw_timetable.get("timetable"), dict):
        doc = raw_timetable["timetable"]

    raw_courses = doc.get("courses")
    if raw_courses is None:
        return None
    if not isinstance(raw_courses, list):
        raise AnonymousExportError("invalid_timetable", status_code=400)
    if not raw_courses:
        return None
    if len(raw_courses) > MAX_ANONYMOUS_COURSES:
        raise AnonymousExportError("too_many_courses", status_code=413)

    courses: list[dict] = []
    for raw_course in raw_courses:
        if isinstance(raw_course, dict):
            raw_meetings = raw_course.get("meetings")
            if isinstance(raw_meetings, list) and len(raw_meetings) > MAX_ANONYMOUS_MEETINGS_PER_COURSE:
                raise AnonymousExportError("too_many_meetings", status_code=413)
        norm = normalize_course(raw_course)
        if norm is not None:
            bounded = _bound_course(norm)
            if bounded is not None:
                courses.append(bounded)
    if not courses:
        return None

    resolved_semester = _clean_text(
        doc.get("semester") if doc.get("semester") is not None else outer.get("semester"),
        max_len=_MAX_SEMESTER_LEN,
    )
    campus = _normalize_campus(
        doc.get("campus") or outer.get("campus"),
        doc.get("school") or outer.get("school"),
        doc.get("source") or outer.get("source"),
    )
    anchor = TERM_ANCHORS.get(resolved_semester)
    week1_monday = anchor[0] if anchor else None
    total_weeks = anchor[1] if anchor else None
    checked_at = _sanitize_checked_at(
        doc.get("checked_at") if doc.get("checked_at") is not None else outer.get("checked_at")
    )

    return {
        "version": 1,
        "semester": resolved_semester,
        "campus": campus,
        "week1_monday": week1_monday,
        "total_weeks": total_weeks,
        "checked_at": checked_at,
        "courses": courses,
    }


def _allocate_ticket(db: DB, snapshot: dict, *, now_ts: int | None = None) -> dict:
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
    return _allocate_ticket(db, snapshot, now_ts=now_ts)


def create_anonymous_export_ticket(
    db: DB,
    raw_timetable: Any,
    *,
    now_ts: int | None = None,
) -> dict | None:
    """Create a one-time anonymous export ticket (300 s TTL) from a client timetable.

    Enforces strict payload/course/meeting limits and whitelists only timetable
    fields, stripping all PII. Returns None if the timetable has no valid courses.
    """
    snapshot = build_anonymous_snapshot(raw_timetable)
    if snapshot is None:
        return None
    return _allocate_ticket(db, snapshot, now_ts=now_ts)


def claim_export_ticket(db: DB, raw_code: str, *, now_ts: int | None = None) -> dict | None:
    """Atomically claim a one-time export ticket by raw code."""
    code = normalize_code(raw_code)
    if len(code) != CODE_LENGTH or any(ch not in _CODE_SET for ch in code):
        return None
    return db.today_ticket_claim(hash_code(code), now_ts=now_ts)
