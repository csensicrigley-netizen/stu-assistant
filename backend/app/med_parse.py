"""医学院课表解析：把「教学服务平台」的 kbList 折成 App 用的 TimetableDoc。

真实结构（实测样本，字段名取自真实响应，示例值已替换成占位）：

    kbList  是一个 **JSON 字符串**，parse 之后是长度 7 的数组，
            索引 0..6 = 周一…周日。
    每一天是一个 dict，key 是节次码 "01".."13"，value 是**这门课本周的记录列表**。
    一条记录（结构）：

        {"kcmc":"<课程名>", "teaxms":"<教师>", "jxcdmc":"<教室>",
         "jzwmc":"<建筑>", "szxqmc":"<校区>", "kcbh":"<课程号>",
         "jxbmc":"<教学班>", "xq":1, "zc":17,
         "jcdm":"0102", "jcdm2":"01,02", "qssj":"08:00:00", "jssj":"09:40:00",
         "qsrq":"2026-12-28", ...}

    注意三件事，之前按文档推断写错过：
      1. `zc` 是**第几周这个整数**（17 = 第 17 周），不是 "1-16周" 这种区间；
      2. `xq` 在**记录里**也有（外层索引只是分组），以记录里的为准；
      3. 同一门课同一星期同一节次，会在**每个周各出现一条**（整学期展开成几百条），
         所以必须按 (星期, 节次) 合并，把周次收集成一个列表。
"""

from __future__ import annotations

import hashlib
import json
from typing import Any

from . import timetable_import as imp

MAX_WEEK = 30


def _text(value: Any) -> str:
    return str(value or "").strip()


def _int(value: Any, fallback: int | None = None) -> int | None:
    text = _text(value)
    if text.lstrip("-").isdigit():
        return int(text)
    return fallback


def _periods(row: dict, bucket: str) -> list[int]:
    """节次：优先 jcdm2（"01,02"），其次 jcdm（"0102"），最后用外层 key。"""
    raw = _text(row.get("jcdm2"))
    nums: list[int] = []
    if raw:
        for part in raw.replace("，", ",").split(","):
            n = _int(part)
            if n:
                nums.append(n)
    if not nums:
        joined = _text(row.get("jcdm"))
        if joined.isdigit() and len(joined) % 2 == 0 and len(joined) >= 2:
            nums = [_int(joined[i : i + 2]) or 0 for i in range(0, len(joined), 2)]
            nums = [n for n in nums if n]
    if not nums:
        n = _int(bucket)
        nums = [n] if n else []
    return sorted({n for n in nums if 1 <= n <= imp.MAX_PERIOD})


def _weeks(row: dict) -> list[int]:
    """周次：zc 是整数周；个别记录会给 "1-16周" 这种串，两种都吃。"""
    n = _int(row.get("zc"))
    if n and 1 <= n <= MAX_WEEK:
        return [n]
    text = _text(row.get("zc")) or _text(row.get("zcmc"))
    if not text:
        return []
    try:
        parsed, _ = imp._parse_weeks(text)
    except Exception:
        return []
    return [w for w in parsed if 1 <= w <= MAX_WEEK]


def _raw_time(row: dict, weekday: int, periods: list[int]) -> str:
    start = _text(row.get("qssj"))
    end = _text(row.get("jssj"))
    head = f"周{'一二三四五六日'[weekday - 1]} 第{periods[0]}-{periods[-1]}节" if periods else ""
    span = f"{start[:5]}-{end[:5]}" if start and end else ""
    return " ".join(x for x in (head, span) if x)


def _rows_of(day: Any) -> list[tuple[str, dict]]:
    """一天里的全部记录：[(节次 key, 记录)]。结构不对就当天跳过。"""
    out: list[tuple[str, dict]] = []
    if not isinstance(day, dict):
        return out
    for bucket, rows in day.items():
        if not isinstance(rows, list):
            continue
        for row in rows:
            if isinstance(row, dict):
                out.append((str(bucket), row))
    return out


def parse_timetable(payload: dict, *, semester: str) -> dict:
    """接口响应 → TimetableDoc（形状由 timetable_import.to_timetable_doc 决定）。"""
    body = payload if isinstance(payload, dict) else {}
    raw = body.get("kbList")
    days: Any = raw
    if isinstance(raw, str):
        try:
            days = json.loads(raw)
        except Exception:
            days = []
    if not isinstance(days, list):
        days = []

    courses: dict[str, dict] = {}
    for index, day in enumerate(days):
        for bucket, row in _rows_of(day):
            title = _text(row.get("kcmc"))
            if not title:
                continue
            weekday = _int(row.get("xq"), index + 1) or (index + 1)
            if not 1 <= weekday <= 7:
                weekday = index + 1 if 1 <= index + 1 <= 7 else 1
            periods = _periods(row, bucket)
            weeks = _weeks(row)
            if not periods or not weeks:
                continue

            teacher = _text(row.get("teaxms"))
            room = _text(row.get("jxcdmc")) or _text(row.get("jzwmc"))
            campus = _text(row.get("szxqmc"))
            location = f"{campus} {room}".strip() if campus and room else (room or campus)

            # 按「课程号 + 课程名」归并：同一门课换老师、换教学班都不拆，
            # 老师开成一条名单放在 meeting 上。
            cid = imp._course_id(title, "", _text(row.get("kcbh")))
            course = courses.get(cid)
            if course is None:
                course = {
                    "id": cid,
                    "title": title,
                    "code": _text(row.get("kcbh")),
                    "className": _text(row.get("jxbmc")),
                    "teacher": teacher,
                    "hue": imp._hue(cid),
                    "meetings": [],
                    "_by_meeting": {},
                }
                courses[cid] = course

            # 合并键必须带上**地点**。
            # 原来只用 (课程, 星期, 节次)：周 1 在 A 教室、周 2 换到 B 教室时，
            # 两条会被并成一条，周次 [1,2] 但地点只留了 A——学生按课表走到 A 教室，
            # 结果那天在 B（审查报告第 16 项）。地点不同就是两个时间段。
            # 用完整字段归并，持久 ID 则使用跨进程稳定的摘要。
            key = (weekday, tuple(periods), location)
            mid = imp._meeting_id(cid, weekday, periods) + "-" + hashlib.sha256(location.encode("utf-8")).hexdigest()
            meeting = course["_by_meeting"].get(key)
            if meeting is None:
                meeting = {
                    "id": mid,
                    "courseId": cid,
                    "title": title,
                    "className": course["className"],
                    "weekday": weekday,
                    "weeks": [],
                    "periods": periods,
                    "teacher": teacher,
                    "location": location,
                    "rawTime": _raw_time(row, weekday, periods),
                    "notes": "",
                    "onlineInfo": "",
                    "_teachers": set(),
                }
                course["_by_meeting"][key] = meeting
                course["meetings"].append(meeting)
            meeting["weeks"] = sorted(set(meeting["weeks"]) | set(weeks))
            if teacher:
                meeting["_teachers"].add(teacher)

    prepared: list[dict] = []
    for course in courses.values():
        course.pop("_by_meeting", None)
        for m in course["meetings"]:
            names = m.pop("_teachers", set())
            if names:
                m["teacher"] = "、".join(sorted(names))
        course["meetings"] = sorted(
            course["meetings"], key=lambda m: (m["weekday"], m["periods"][0] if m["periods"] else 0)
        )
        prepared.append(course)

    return imp.to_timetable_doc(prepared, semester=semester)
