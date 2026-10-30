"""手动录入课表：解析、校验、落库。

医学院的教务系统和主校区不一样，没法自动同步，只能让学生自己把课表录进来。
这里做三件事，都不碰网络：

  1. 把学生粘进来的东西（竖线行、Excel 表格、CSV）解析成 `Course` / `Meeting`，
     形状与 `web/src/lib/timetable.ts` 完全一致；
  2. 校验：节次 1–13、周次 1–30、星期 1–7，超范围给带行号的中文错误；
  3. 落库：`manual_timetables` 存一份原文，同时按 `sync._flatten_timetable()`
     的形状往 `items` 里镜像一行 —— 这样 `GET /v1/timetable` 原样返回，
     前端 `normalizeTimetable()` 一行都不用改。

约定（和 `sections.ts` 对齐）：
  weekday  1=周一 … 7=周日
  periods  1–13（学校印的是 1–9、0、A、B、C，stu-mcp 用整数）
  weeks    1–30
"""
from __future__ import annotations

import csv
import datetime as dt
import hashlib
import io
import re
from typing import Any

# --------------------------------------------------------------------------
# 常量
# --------------------------------------------------------------------------

SCHOOLS = (
    {"id": "main", "name": "主校区", "note": "教务系统可自动同步"},
    {"id": "med", "name": "医学院", "note": "教务系统不同，需手动录入"},
    {"id": "other", "name": "其他", "note": "自行录入"},
)

SCHOOL_IDS = {s["id"] for s in SCHOOLS}

SOURCES = ("manual", "paste", "csv")

MAX_PERIOD = 13
MAX_WEEK = 30
DEFAULT_WEEKS = (1, 16)

TEMPLATE_MEDIA_TYPE = "text/csv; charset=utf-8"

_CN_WEEKDAY = {
    "一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "日": 7, "天": 7,
    "1": 1, "2": 2, "3": 3, "4": 4, "5": 5, "6": 6, "7": 7,
}

_EN_WEEKDAY = {
    "mon": 1, "monday": 1,
    "tue": 2, "tues": 2, "tuesday": 2,
    "wed": 3, "weds": 3, "wednesday": 3,
    "thu": 4, "thur": 4, "thurs": 4, "thursday": 4,
    "fri": 5, "friday": 5,
    "sat": 6, "saturday": 6,
    "sun": 7, "sunday": 7,
}

_WD_RE = re.compile(r"(?:周|星期|礼拜)\s*([一二三四五六日天1-7])")
_WD_BAD_RE = re.compile(r"(?:周|星期|礼拜)\s*([八九十]|\d+)")
_EN_DAY_RE = re.compile(
    r"(?<![A-Za-z])(mon|monday|tues|tuesday|tue|weds|wednesday|wed|"
    r"thurs|thursday|thur|thu|fri|friday|sat|saturday|sun|sunday)(?![A-Za-z])",
    re.I,
)
_BARE_WD_RE = re.compile(r"^\s*([一二三四五六日天])(?=\s|\d|第|$)")

_SEP_MAP = (
    ("至", "-"), ("到", "-"), ("~", "-"), ("～", "-"), ("–", "-"), ("—", "-"), ("－", "-"),
    ("、", ","), ("，", ","), ("；", ","), (";", ","), ("＋", ","), ("+", ","),
)

# 表头中英文变体。先按整格精确匹配，再按长别名优先的子串匹配。
_HEADER_ALIASES: dict[str, tuple[str, ...]] = {
    "title": ("课程名称", "课程名", "课程", "科目", "课名", "教学班", "课程名字",
              "course name", "course", "subject", "title", "class"),
    "weekday": ("星期几", "上课星期", "星期", "周几", "周", "日",
                "weekday", "day of week", "day"),
    "periods": ("上课节次", "节次", "上课时间", "时间", "课时", "课节", "节",
                "periods", "period", "class time", "time", "session"),
    "weeks": ("上课周次", "起止周", "周次", "周数",
              "weeks", "week", "week range"),
    "teacher": ("任课教师", "授课教师", "主讲教师", "教师", "老师", "主讲",
                "teacher", "instructor", "lecturer"),
    "location": ("上课地点", "上课教室", "地点", "教室", "场地", "课室", "上课场地",
                 "location", "room", "classroom", "place", "venue"),
}

_HEADER_EXACT: dict[str, str] = {}
for _key, _names in _HEADER_ALIASES.items():
    for _name in _names:
        _HEADER_EXACT.setdefault(_name, _key)

# 长别名在前，避免「周次」被「周」抢走
_HEADER_ORDERED = sorted(_HEADER_EXACT.items(), key=lambda kv: len(kv[0]), reverse=True)


# --------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------

def _norm(text: Any) -> str:
    return re.sub(r"\s+", " ", str(text if text is not None else "").strip())


def _digest(raw: str, size: int = 16) -> str:
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:size]


def _course_id(title: str, teacher: str, location: str) -> str:
    """课程名 + 教师 + 地点 的稳定指纹：同一份输入重复导入得到同一个 id。"""
    return "course_" + _digest("\x1f".join([_norm(title), _norm(teacher), _norm(location)]))


def _hue(course_id: str) -> int:
    """0–359 的稳定色相，同一个 id 永远同一个颜色。"""
    return int(hashlib.sha1(course_id.encode("utf-8")).hexdigest()[:8], 16) % 360


def _meeting_id(course_id: str, weekday: int, periods: list[int]) -> str:
    if not periods:
        return f"{course_id}-w{weekday}"
    return f"{course_id}-w{weekday}p{periods[0]}-{periods[-1]}"


def _clean_number_text(text: str, drop: tuple[str, ...]) -> str:
    t = text
    for ch in "第（）()【】[]{}":
        t = t.replace(ch, "")
    for word in drop:
        t = t.replace(word, "")
    for a, b in _SEP_MAP:
        t = t.replace(a, b)
    t = re.sub(r"\s+", "", t)
    return t.strip(",")


# --------------------------------------------------------------------------
# 星期
# --------------------------------------------------------------------------

def _take_weekday(text: str) -> tuple[int | None, str, str | None]:
    """→ (星期几, 去掉星期标记的剩余文本, 看不懂的标记)。"""
    m = _WD_RE.search(text)
    if m:
        wd = _CN_WEEKDAY.get(m.group(1))
        if wd:
            return wd, f"{text[:m.start()]} {text[m.end():]}", None

    m = _EN_DAY_RE.search(text)
    if m:
        wd = _EN_WEEKDAY.get(m.group(0).lower().rstrip("."))
        if wd:
            return wd, f"{text[:m.start()]} {text[m.end():]}", None

    m = _WD_BAD_RE.search(text)
    if m:
        return None, text, m.group(0)

    m = _BARE_WD_RE.match(text)
    if m:
        return _CN_WEEKDAY.get(m.group(1)), text[m.end():], None

    return None, text, None


def _parse_weekday(text: str) -> int | None:
    wd, _, _ = _take_weekday(_norm(text))
    return wd


# --------------------------------------------------------------------------
# 节次 / 周次
# --------------------------------------------------------------------------

def _parse_periods(text: str) -> tuple[list[int], str | None]:
    body = _clean_number_text(text, ("节次", "节"))
    if not body:
        return [], None
    values: list[int] = []
    for part in body.split(","):
        part = part.strip()
        if not part:
            continue
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", part)
        if not m:
            return [], f"节次「{part}」看不懂，写成 6-9 或 6,7,8,9"
        a = int(m.group(1))
        b = int(m.group(2)) if m.group(2) else a
        lo, hi = min(a, b), max(a, b)
        if lo < 1 or hi > MAX_PERIOD:
            return [], f"节次 {part} 超出范围（1-{MAX_PERIOD}）"
        values.extend(range(lo, hi + 1))
    return sorted(set(values)), None


def _parse_weeks(text: str) -> tuple[list[int], str | None]:
    t = text
    odd = "单" in t
    even = "双" in t
    t = t.replace("单", "").replace("双", "")
    body = _clean_number_text(t, ("周次", "周数", "周"))
    if not body:
        return [], None
    values: list[int] = []
    for part in body.split(","):
        part = part.strip()
        if not part:
            continue
        m = re.fullmatch(r"(\d+)(?:-(\d+))?", part)
        if not m:
            return [], f"周次「{part}」看不懂，写成 1-16 或 1,3,5"
        a = int(m.group(1))
        b = int(m.group(2)) if m.group(2) else a
        lo, hi = min(a, b), max(a, b)
        if lo < 1 or hi > MAX_WEEK:
            return [], f"周次 {part} 超出范围（1-{MAX_WEEK}）"
        values.extend(range(lo, hi + 1))
    if odd:
        values = [v for v in values if v % 2 == 1]
    if even:
        values = [v for v in values if v % 2 == 0]
    if not values:
        return [], "周次是空的，写成 1-16 或 1,3,5"
    return sorted(set(values)), None


# --------------------------------------------------------------------------
# 一格内容的判定
# --------------------------------------------------------------------------

def _looks_like_time(text: str) -> bool:
    t = _norm(text)
    if not t:
        return False
    if "周次" in t or "周数" in t:
        return False
    if _WD_RE.search(t):
        return True
    if _EN_DAY_RE.search(t):
        return True
    if "节" in t:
        return True
    # 「四 6-9」：星期只写一个字，后面跟节次
    if _BARE_WD_RE.match(t) and re.search(r"\d", t):
        return True
    # 「周八 5-6节」这种错的也要认成时间格，才能报出「周八」看不懂
    if re.search(r"(?:周|星期|礼拜)\s*\S", t) and re.search(r"\d", t):
        return True
    return False


def _looks_like_weeks(text: str) -> bool:
    """这一格像不像周次。

    两个坑要避开：
      * 「周老师」这种教师名带个「周」字，不是周次 —— 所以必须有数字；
      * 「305」这种纯数字房间号，看着像周次但不是 —— 所以要么带「周/单/双」，
        要么里面的数字都落在 1–30。
    """
    t = _norm(text)
    if not t:
        return False
    if not any(ch.isdigit() for ch in t):
        return False
    if "周" in t or "单" in t or "双" in t:
        return True
    if not re.fullmatch(r"[\d\s,，、\-–~至到()（）第]+", t):
        return False
    numbers = [int(n) for n in re.findall(r"\d+", t)]
    return bool(numbers) and all(1 <= n <= MAX_WEEK for n in numbers)


def _looks_like_location(text: str) -> bool:
    t = _norm(text)
    if not t:
        return False
    if re.search(r"[楼室馆场区座层号栋厅苑]", t):
        return True
    if re.search(r"\d", t):
        return True
    return "-" in t


# --------------------------------------------------------------------------
# 时间格 → [(weekday, periods)]
# --------------------------------------------------------------------------

def _parse_time_cell(lineno: int, text: str, errs: list[str]) -> list[tuple[int, list[int]]]:
    acc: dict[int, list[int]] = {}
    current: int | None = None

    for piece in re.split(r"[;；+＋,，、]", text):
        piece = piece.strip()
        if not piece:
            continue
        wd, rest, bad = _take_weekday(piece)
        if bad:
            errs.append(f"第 {lineno} 行：「{bad}」看不懂，星期只能是 一/二/三/四/五/六/日 或 1-7")
            return []
        if wd is not None:
            current = wd
        if current is None:
            errs.append(f"第 {lineno} 行：没写星期，星期只能是 一/二/三/四/五/六/日 或 1-7")
            return []
        periods, perr = _parse_periods(rest)
        if perr:
            errs.append(f"第 {lineno} 行：{perr}")
            return []
        acc.setdefault(current, []).extend(periods)

    filled = {wd: ps for wd, ps in acc.items() if ps}
    if not filled:
        errs.append(f"第 {lineno} 行：没写节次，节次是 1-{MAX_PERIOD}（比如 6-9）")
        return []
    # 「一,三 1-2」：写了两个星期、节次只写一次，两边都给同一段节次
    if len(filled) != len(acc):
        union = sorted({p for ps in filled.values() for p in ps})
        for wd in list(acc):
            if not acc[wd]:
                acc[wd] = list(union)
    return [(wd, sorted(set(ps))) for wd, ps in sorted(acc.items()) if ps]


def _pairs_from_columns(
    lineno: int, weekday_cell: str, period_cell: str, errs: list[str]
) -> list[tuple[int, list[int]]]:
    """表格里「星期」和「节次」是两格，拼起来再解析。"""
    wd_tokens = [t for t in re.split(r"[,，、/;；\s]+", _norm(weekday_cell)) if t]
    if not wd_tokens:
        return []

    weekdays: list[int] = []
    for token in wd_tokens:
        wd, _, bad = _take_weekday(token)
        if wd is None:
            errs.append(
                f"第 {lineno} 行：「{bad or token}」看不懂，星期只能是 一/二/三/四/五/六/日 或 1-7"
            )
            return []
        weekdays.append(wd)

    groups = [g.strip() for g in re.split(r"[,，、]", _norm(period_cell)) if g.strip()]
    if len(weekdays) > 1 and len(groups) == len(weekdays):
        raw = groups
    else:
        raw = [_norm(period_cell)] * len(weekdays)

    out: list[tuple[int, list[int]]] = []
    for wd, raw_periods in zip(weekdays, raw):
        periods, perr = _parse_periods(raw_periods)
        if perr:
            errs.append(f"第 {lineno} 行：{perr}")
            return []
        if not periods:
            errs.append(f"第 {lineno} 行：没写节次，节次是 1-{MAX_PERIOD}（比如 6-9）")
            return []
        out.append((wd, periods))
    return out


# --------------------------------------------------------------------------
# 一行 → 课程
# --------------------------------------------------------------------------

def _split_teacher_place(rest: list[str]) -> tuple[str, str]:
    teacher = ""
    places: list[str] = []
    for field in rest:
        field = _norm(field)
        if not field:
            continue
        if _looks_like_location(field):
            places.append(field)
        elif not teacher:
            teacher = field
        else:
            places.append(field)
    return teacher, " / ".join(places)


def _header_map(cells: list[str]) -> dict[str, int] | None:
    found: dict[str, int] = {}
    for index, cell in enumerate(cells):
        name = _norm(cell)
        if not name:
            continue
        # 英文表头大小写不敏感（Course / course / COURSE 都认）
        probe = name.lower()
        key = _HEADER_EXACT.get(name) or _HEADER_EXACT.get(probe)
        if key is None:
            for alias, mapped in _HEADER_ORDERED:
                if alias in name or alias in probe:
                    key = mapped
                    break
        if key and key not in found:
            found[key] = index
    if "title" in found and len(found) >= 2:
        return found
    return None


def _read_row(
    lineno: int, cells: list[str], columns: dict[str, int] | None
) -> tuple[str, str, str, str, list[tuple[int, list[int]]], str, list[str]]:
    """→ (课程名, 教师, 地点, 周次原文, 时间段, 时间原文, 错误)。"""
    errs: list[str] = []

    if columns:
        def cell(key: str) -> str:
            index = columns.get(key)
            if index is None or index >= len(cells):
                return ""
            return cells[index]

        title = cell("title")
        weekday_cell = cell("weekday")
        period_cell = cell("periods")
        weeks_text = cell("weeks")
        teacher = cell("teacher")
        location = cell("location")
        raw_time = _norm(f"{weekday_cell} {period_cell}")
        pairs = _pairs_from_columns(lineno, weekday_cell, period_cell, errs) if weekday_cell or period_cell else []
        return title, teacher, location, weeks_text, pairs, raw_time, errs

    if len(cells) >= 2 and _looks_like_time(cells[1]):
        # 格式 A：课程名 | 时间 | 周次 | 教师 | 地点
        title = cells[0]
        rest = list(cells[1:])
        time_text = rest.pop(0)
        weeks_text = ""
        if rest and _looks_like_weeks(rest[0]):
            weeks_text = rest.pop(0)
        teacher, location = _split_teacher_place(rest)
        pairs = _parse_time_cell(lineno, time_text, errs)
        return title, teacher, location, weeks_text, pairs, _norm(time_text), errs

    if len(cells) >= 3 and _parse_weekday(cells[1]) is not None:
        # 没有表头的表格：课程名 星期 节次 周次 教师 地点
        title = cells[0]
        weekday_cell = cells[1]
        period_cell = cells[2]
        weeks_text = cells[3] if len(cells) > 3 else ""
        teacher = cells[4] if len(cells) > 4 else ""
        location = " ".join(cells[5:]) if len(cells) > 5 else ""
        pairs = _pairs_from_columns(lineno, weekday_cell, period_cell, errs)
        raw_time = _norm(f"{weekday_cell} {period_cell}")
        return title, teacher, location, weeks_text, pairs, raw_time, errs

    # 只写了课程名（和可能的教师、地点）：先当「没排时间」收着
    title = cells[0]
    teacher, location = _split_teacher_place(cells[1:])
    return title, teacher, location, "", [], "", errs


def _parse_rows(
    rows: list[tuple[int, list[str]]], *, semester: str
) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    columns: dict[str, int] | None = None
    courses: dict[str, dict] = {}
    unarranged: dict[str, dict] = {}

    for lineno, raw_cells in rows:
        cells = [_norm(c) for c in raw_cells]
        if not any(cells):
            continue
        if cells[0].startswith("#"):
            continue

        header = _header_map(cells)
        if header:
            columns = header
            continue

        title, teacher, location, weeks_text, pairs, raw_time, errs = _read_row(lineno, cells, columns)

        if not title:
            errs.append(f"第 {lineno} 行：没写课程名")
        if errs:
            errors.extend(errs)
            continue

        weeks: list[int] | None = None
        if weeks_text:
            weeks, werr = _parse_weeks(weeks_text)
            if werr:
                errors.append(f"第 {lineno} 行：{werr}")
                continue
        if not weeks:
            weeks = list(range(DEFAULT_WEEKS[0], DEFAULT_WEEKS[1] + 1))
            warnings.append(f"第 {lineno} 行：没写周次，按 {DEFAULT_WEEKS[0]}-{DEFAULT_WEEKS[1]} 周记")

        cid = _course_id(title, teacher, location)

        if not pairs:
            # 只写了课程名：仍然算一门课，只是没有时间段。
            # 放进 courses（meetings 为空）+ unarranged 两处，
            # 这样 to_timetable_doc() 能原样把它搬进 unarranged。
            courses.setdefault(cid, {
                "id": cid,
                "title": title,
                "course_code": "",
                "class_name": "",
                "teachers": [teacher] if teacher else [],
                "teacher": teacher,
                "meetings": [],
                "hue": _hue(cid),
            })
            unarranged[cid] = {
                "id": cid,
                "title": title,
                "class_name": "",
                "teachers": [teacher] if teacher else [],
            }
            warnings.append(f"第 {lineno} 行：没有时间段，先放进「没排时间」里")
            continue

        course = courses.setdefault(cid, {
            "id": cid,
            "title": title,
            "course_code": "",
            "class_name": "",
            "teachers": [teacher] if teacher else [],
            "teacher": teacher,
            "meetings": [],
            "hue": _hue(cid),
        })

        for weekday, periods in pairs:
            same = [
                m for m in course["meetings"]
                if m["weekday"] == weekday and m["periods"] == periods
            ]
            if same:
                same[0]["weeks"] = sorted(set(same[0]["weeks"]) | set(weeks))
                continue
            course["meetings"].append({
                "id": _meeting_id(cid, weekday, periods),
                "course_id": cid,
                "title": title,
                "class_name": "",
                "weekday": weekday,
                "weeks": list(weeks),
                "periods": list(periods),
                "teacher": teacher,
                "location": location,
                "raw_time": raw_time,
                "notes": "",
                "online_info": "",
            })

    ordered = sorted(courses.values(), key=_course_sort_key)
    if not ordered and not unarranged and not errors:
        errors.append(
            "没有解析出任何课程。一行的写法是「课程名 | 周一 1-2节 | 1-16周 | 教师 | 地点」，"
            "竖线或制表符分隔都行。"
        )

    all_weeks = [w for c in ordered for m in c["meetings"] for w in m["weeks"]]
    return {
        "ok": not errors and bool(ordered or unarranged),
        "semester": semester,
        "courses": ordered,
        "unarranged": sorted(unarranged.values(), key=lambda u: u["title"]),
        "errors": errors,
        "warnings": warnings,
        "stats": {
            "courses": len(ordered),
            "meetings": sum(len(c["meetings"]) for c in ordered),
            "weeks": [min(all_weeks), max(all_weeks)] if all_weeks else [0, 0],
        },
    }


def _course_sort_key(course: dict) -> tuple:
    slots = sorted((m["weekday"], m["periods"][0]) for m in course["meetings"] if m["periods"])
    return (slots[0] if slots else (9, 99), course["title"])


# --------------------------------------------------------------------------
# 对外：解析
# --------------------------------------------------------------------------

def parse_paste(text: str, *, semester: str) -> dict:
    """粘进来的文本：一行一课（竖线/制表符）或从 Excel 复制的表格。"""
    rows: list[tuple[int, list[str]]] = []
    for lineno, line in enumerate(str(text or "").lstrip("\ufeff").splitlines(), start=1):
        if not line.strip():
            rows.append((lineno, []))
            continue
        if "\t" in line:
            cells = line.split("\t")
        elif "|" in line or "｜" in line:
            cells = re.split(r"[|｜]", line)
        else:
            cells = [line]
        rows.append((lineno, cells))
    return _parse_rows(rows, semester=semester)


def parse_csv(text: str, *, semester: str) -> dict:
    """CSV（或从 Excel 存出来的逗号表格）。第一行有表头就用表头。"""
    body = str(text or "").lstrip("\ufeff")
    first = next((ln for ln in body.splitlines() if ln.strip()), "")
    delimiter = "\t" if ("\t" in first and "," not in first) else ","
    rows: list[tuple[int, list[str]]] = []
    try:
        reader = csv.reader(io.StringIO(body), delimiter=delimiter)
        for lineno, cells in enumerate(reader, start=1):
            rows.append((lineno, list(cells)))
    except csv.Error as exc:  # noqa: BLE001 - 坏 CSV 也要给人话
        return {
            "ok": False,
            "semester": semester,
            "courses": [],
            "unarranged": [],
            "errors": [f"CSV 读不出来（{type(exc).__name__}），另存成「CSV UTF-8」再试一次"],
            "warnings": [],
            "stats": {"courses": 0, "meetings": 0, "weeks": [0, 0]},
        }
    return _parse_rows(rows, semester=semester)


# --------------------------------------------------------------------------
# 对外：文档
# --------------------------------------------------------------------------

def _meeting_ok(meeting: dict) -> bool:
    try:
        weekday = int(meeting.get("weekday"))
    except (TypeError, ValueError):
        return False
    periods = meeting.get("periods")
    return 1 <= weekday <= 7 and isinstance(periods, list) and bool(periods)


def to_timetable_doc(courses: list[dict], *, semester: str) -> dict:
    """课程列表 → 和 `sync._flatten_timetable()` 同形状的整份课表文档。"""
    placed: list[dict] = []
    unplaced: list[dict] = []
    for course in courses or []:
        row = course if isinstance(course, dict) else {}
        meetings = [m for m in (row.get("meetings") or []) if isinstance(m, dict) and _meeting_ok(m)]
        if not meetings:
            title = _norm(row.get("title"))
            if not title:
                continue
            cid = str(row.get("id") or _course_id(title, "", ""))
            teachers = row.get("teachers")
            if not isinstance(teachers, list):
                teachers = [row.get("teacher")] if row.get("teacher") else []
            unplaced.append({
                "id": cid,
                "title": title,
                "class_name": _norm(row.get("class_name")),
                "teachers": [_norm(t) for t in teachers if _norm(t)],
            })
            continue
        placed.append({**row, "meetings": meetings})

    return {
        "semester": semester,
        "status": "partial" if unplaced else "ok",
        "checked_at": None,
        "courses": placed,
        "unarranged": unplaced,
        "unplaced_details": [],
        "pending": [],
        "coverage": {
            "kind": "theory",
            "weeks": "all",
            "complete": True,
            "source": "manual",
        },
    }


def _canonical_meeting(raw: dict, course: dict, index: int, errors: list[str], label: str) -> dict | None:
    meeting = raw if isinstance(raw, dict) else {}
    try:
        weekday = int(meeting.get("weekday"))
    except (TypeError, ValueError):
        weekday = 0
    if not 1 <= weekday <= 7:
        errors.append(f"{label}第 {index} 个时间段：星期只能是 1-7（一=1 … 日=7）")
        return None

    periods_raw = meeting.get("periods")
    periods: list[int] = []
    for value in periods_raw if isinstance(periods_raw, list) else []:
        try:
            number = int(value)
        except (TypeError, ValueError):
            continue
        if 1 <= number <= MAX_PERIOD:
            periods.append(number)
    periods = sorted(set(periods))
    if not periods:
        errors.append(f"{label}第 {index} 个时间段：节次要在 1-{MAX_PERIOD} 之间")
        return None

    weeks_raw = meeting.get("weeks")
    weeks: list[int] = []
    for value in weeks_raw if isinstance(weeks_raw, list) else []:
        try:
            number = int(value)
        except (TypeError, ValueError):
            continue
        if 1 <= number <= MAX_WEEK:
            weeks.append(number)
    weeks = sorted(set(weeks))

    cid = str(course.get("id") or "")
    return {
        "id": str(meeting.get("id") or _meeting_id(cid, weekday, periods)),
        "course_id": cid,
        "title": _norm(course.get("title")),
        "class_name": _norm(course.get("class_name")),
        "weekday": weekday,
        "weeks": weeks,
        "periods": periods,
        "teacher": _norm(meeting.get("teacher") or course.get("teacher")),
        "location": _norm(meeting.get("location")),
        "raw_time": _norm(meeting.get("raw_time")),
        "notes": _norm(meeting.get("notes")),
        "online_info": _norm(meeting.get("online_info")),
    }


def validate_doc(doc: dict) -> dict:
    """把前端交回来的课表校一遍、捋成规范形状。

    返回 {"ok", "errors", "warnings", "doc", "stats"}；`ok` 为真才允许落库。
    """
    errors: list[str] = []
    warnings: list[str] = []

    if not isinstance(doc, dict):
        return {
            "ok": False,
            "errors": ["课表格式不对，应该是一份 JSON 文档"],
            "warnings": [],
            "doc": {},
            "stats": {"courses": 0, "meetings": 0, "weeks": [0, 0]},
        }

    semester = _norm(doc.get("semester"))
    if not semester:
        errors.append("缺学期（semester），比如 2026-2027-1")

    raw_courses = doc.get("courses")
    if not isinstance(raw_courses, list) or not raw_courses:
        errors.append("这份课表里没有课程")

    courses: list[dict] = []
    for index, raw in enumerate(raw_courses if isinstance(raw_courses, list) else [], start=1):
        row = raw if isinstance(raw, dict) else {}
        title = _norm(row.get("title"))
        if not title:
            errors.append(f"第 {index} 门课：没写课程名")
            continue

        teacher = _norm(row.get("teacher"))
        teachers = row.get("teachers")
        if not isinstance(teachers, list):
            teachers = [teacher] if teacher else []
        teachers = [_norm(t) for t in teachers if _norm(t)]

        first_place = ""
        raw_meetings = row.get("meetings")
        if isinstance(raw_meetings, list):
            for meeting in raw_meetings:
                if isinstance(meeting, dict) and _norm(meeting.get("location")):
                    first_place = _norm(meeting.get("location"))
                    break

        cid = _norm(row.get("id")) or _course_id(title, teacher, first_place)
        course = {
            "id": cid,
            "title": title,
            "course_code": _norm(row.get("course_code")),
            "class_name": _norm(row.get("class_name")),
            "teachers": teachers,
            "teacher": teacher,
            "meetings": [],
            "hue": _hue(cid),
        }

        label = f"第 {index} 门课「{title}」"
        if isinstance(raw_meetings, list):
            for position, meeting in enumerate(raw_meetings, start=1):
                canonical = _canonical_meeting(meeting, course, position, errors, label)
                if canonical is None:
                    continue
                if not canonical["weeks"]:
                    canonical["weeks"] = list(range(DEFAULT_WEEKS[0], DEFAULT_WEEKS[1] + 1))
                    warnings.append(f"{label}第 {position} 个时间段：没写周次，按 {DEFAULT_WEEKS[0]}-{DEFAULT_WEEKS[1]} 周记")
                course["meetings"].append(canonical)

        courses.append(course)

    result = to_timetable_doc(courses, semester=semester)

    known = {u["title"] for u in result["unarranged"]}
    for raw in doc.get("unarranged") if isinstance(doc.get("unarranged"), list) else []:
        row = raw if isinstance(raw, dict) else {}
        title = _norm(row.get("title"))
        if not title or title in known:
            continue
        known.add(title)
        teachers = row.get("teachers")
        if not isinstance(teachers, list):
            teachers = [row.get("teacher")] if row.get("teacher") else []
        result["unarranged"].append({
            "id": _norm(row.get("id")) or _course_id(title, "", ""),
            "title": title,
            "class_name": _norm(row.get("class_name")),
            "teachers": [_norm(t) for t in teachers if _norm(t)],
        })
    result["status"] = "partial" if result["unarranged"] else "ok"

    return {
        "ok": not errors,
        "errors": errors,
        "warnings": warnings,
        "doc": result,
        "stats": _stats(result),
    }


def _stats(doc: dict) -> dict:
    courses = doc.get("courses") if isinstance(doc.get("courses"), list) else []
    meetings = [m for c in courses for m in (c.get("meetings") or [])]
    weeks = [w for m in meetings for w in (m.get("weeks") or [])]
    return {
        "courses": len(courses),
        "meetings": len(meetings),
        "weeks": [min(weeks), max(weeks)] if weeks else [0, 0],
    }


def template_csv() -> str:
    """CSV 模板：Excel 双击能认（带 BOM、CRLF）。"""
    lines = [
        "课程名称,星期,节次,周次,教师,地点",
        "细胞生物学实验,四,6-9,1-16,何昭明,桑浦山-细胞生物学实验室-理433",
        "高等数学,一,1-2,1-16,张三,桑浦山-E305",
        "大学英语,二,3-4,1-16,李四,桑浦山-教学楼-E201",
    ]
    return "\ufeff" + "\r\n".join(lines) + "\r\n"


# --------------------------------------------------------------------------
# 落库：manual_timetables + items 镜像
# --------------------------------------------------------------------------

def flatten_for_items(doc: dict) -> list[dict]:
    """按 `sync._flatten_timetable()` 的形状包一层，`GET /v1/timetable` 直接能返回。"""
    semester = _norm(doc.get("semester"))
    return [{
        "external_id": f"doc:{semester}",
        "title": f"{semester} 课表",
        "semester": semester,
        "status": doc.get("status", "ok"),
        "checked_at": doc.get("checked_at"),
        "courses": doc.get("courses", []),
        "unarranged": doc.get("unarranged", []),
        "unplaced_details": doc.get("unplaced_details", []),
        "pending": doc.get("pending", []),
        "coverage": doc.get("coverage", {}),
    }]


def save_manual(
    db,
    uid: str,
    semester: str,
    doc: dict,
    *,
    school: str = "main",
    note: str = "",
    source: str = "manual",
) -> dict:
    """存进 manual_timetables，同时往 items 里镜像一行。

    镜像这一步是关键：`GET /v1/timetable` 读的就是 items 里 kind=timetable 的
    payload，所以镜像完前端一行都不用改就能显示这份手动课表。
    """
    stamp = dt.datetime.now(dt.timezone.utc).isoformat()
    body = {**doc, "semester": semester, "checked_at": stamp}
    saved = db.tt_manual_save(uid, semester, body, school=school, source=source, note=note)
    db.replace_kind(uid, "manual", "timetable", flatten_for_items(body))
    return saved


# --------------------------------------------------------------------------
# 给路由用的薄壳（main.py 挂 5 个路由时直接调这些）
# --------------------------------------------------------------------------

def _empty_preview(semester: str, message: str) -> dict:
    return {
        "ok": False,
        "semester": semester,
        "courses": [],
        "unarranged": [],
        "errors": [message],
        "warnings": [],
        "stats": {"courses": 0, "meetings": 0, "weeks": [0, 0]},
    }


def parse_body(body: dict) -> dict:
    """POST /v1/timetable/import/parse 的请求体 → ImportPreview。"""
    body = body if isinstance(body, dict) else {}
    text = str(body.get("text") or "")
    fmt = _norm(body.get("format")).lower() or "paste"
    semester = _norm(body.get("semester"))
    if not text.strip():
        return _empty_preview(semester, "没有内容，先把课表粘进来或者选一个文件")
    if fmt == "csv":
        return parse_csv(text, semester=semester)
    return parse_paste(text, semester=semester)


def save_body(db, uid: str, body: dict) -> dict:
    """POST /v1/timetable/import/save 的请求体 → {ok, semester, ...}。"""
    body = body if isinstance(body, dict) else {}
    doc = body.get("doc") if isinstance(body.get("doc"), dict) else {}
    checked = validate_doc(doc)
    semester = _norm(doc.get("semester")) if isinstance(doc, dict) else ""
    if not checked["ok"]:
        return {
            "ok": False,
            "semester": semester,
            "errors": checked["errors"],
            "warnings": checked["warnings"],
            "stats": checked["stats"],
        }
    school = _norm(body.get("school")).lower()
    if school not in SCHOOL_IDS:
        school = "main"
    note = _norm(body.get("note"))[:200]
    saved = save_manual(db, uid, semester, checked["doc"], school=school, note=note)
    return {
        "ok": True,
        "semester": semester,
        "id": saved.get("id", ""),
        "school": school,
        "updated_at": saved.get("updated_at"),
        "stats": checked["stats"],
        "warnings": checked["warnings"],
    }


def load_body(db, uid: str, semester: str) -> dict:
    """GET /v1/timetable/manual?semester=… → {doc, school, source, ...}。"""
    row = db.tt_manual_get(uid, semester) if semester else None
    if row is None:
        return {"doc": None, "semester": semester, "school": "", "source": "", "note": "", "updated_at": None}
    return {
        "doc": row.get("doc"),
        "semester": row["semester"],
        "school": row["school"],
        "source": row["source"],
        "note": row["note"],
        "updated_at": row["updated_at"],
    }


def delete_body(db, uid: str, semester: str) -> dict:
    """DELETE /v1/timetable/manual?semester=… → {ok}。"""
    removed = db.tt_manual_delete(uid, semester) if semester else False
    return {"ok": bool(removed), "semester": semester}
