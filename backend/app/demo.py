"""Demo payloads (STU_APP_DEMO=1): the whole App can be exercised end to end
without touching a school system.

Shapes follow stu-mcp v0.7.3 (docs/phase0/01-data-contract.md). The timetable
is the same real 2026-2027-1 snapshot the web preview uses; tasks, exams,
grades and notices are synthetic and dated relative to today.
"""
from __future__ import annotations

import datetime as dt
import hashlib

TZ = dt.timezone(dt.timedelta(hours=8))
TEACHING = list(range(3, 19))

# big section -> periods (official timetable, 13 periods)
BIG = {1: [1, 2], 2: [3, 4, 5], 3: [6, 7], 4: [8, 9], 5: [11, 12, 13]}

ROWS = [
    (2, 2, "生物信息学", "温以舟", "桑浦山-D座206", TEACHING),
    (2, 3, "基因组学", "沈知白", "桑浦山-D座206", TEACHING),
    (3, 2, "生物统计学", "苏念", "桑浦山-E305", TEACHING),
    (3, 5, "习近平新时代中国特色社会主义思想概论", "顾清和", "桑浦山-E301", TEACHING),
    (4, 1, "细胞生物学", "陆星野", "桑浦山-讲堂五", TEACHING),
    (4, 2, "基础生态学", "叶青梧", "桑浦山-E401", TEACHING),
    (4, 3, "细胞生物学实验", "何昭明", "桑浦山-细胞生物学实验室—理433、434", TEACHING),
    (4, 4, "细胞生物学实验", "何昭明", "桑浦山-细胞生物学实验室—理433、434", TEACHING),
    (5, 2, "表观遗传学", "陆星野", "桑浦山-D座206", TEACHING),
    (6, 1, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]),
    (6, 2, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]),
    (6, 3, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]),
    (6, 4, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 11]),
    (7, 1, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]),
    (7, 2, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]),
    (7, 3, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]),
    (7, 4, "生物信息学综合实验", "沈知白", "桑浦山-D座306", [6, 7, 8, 9]),
    (6, 2, "基础生态学实验", "叶青梧", "桑浦山-生态学实验室", [9, 10, 12, 13]),
    (7, 2, "基础生态学实验", "叶青梧", "桑浦山-生态学实验室", [9, 10, 12, 13]),
]


def _id(*parts: str) -> str:
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:24]


def _now() -> dt.datetime:
    return dt.datetime.now(TZ)


def demo_timetable(semester: str = "2026-2027-1") -> dict:
    courses: dict[str, dict] = {}
    for i, (weekday, big, title, teacher, room, weeks) in enumerate(ROWS):
        course = courses.setdefault(title, {
            "id": f"jw:timetable:{_id(semester, title)}",
            "title": title, "course_code": "", "class_name": "",
            "teachers": [teacher], "teacher": teacher, "meetings": [],
        })
        periods = BIG[big]
        weeks_raw = "3-18(周)" if weeks == TEACHING else ",".join(map(str, weeks)) + "(周)"
        course["meetings"].append({
            "id": f"jw:meeting:{_id(semester, title, str(i))}",
            "weekday": weekday, "weeks": weeks, "periods": periods,
            "teacher": teacher, "location": room, "building": "", "room": room,
            "raw_time": f"{weeks_raw}[{'-'.join(f'{p:02d}' for p in periods)}节]",
            "notes": "", "online_info": "",
        })
    return {
        "ok": True, "status": "ok", "source": "jw",
        "checked_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "semester": semester,
        "available_semesters": [{"semester": semester, "label": semester}],
        "courses": list(courses.values()),
        "unarranged": [], "unplaced_details": [], "pending": [],
        "coverage": {"kind": "theory", "weeks": "all", "complete": True,
                     "calendar_verified": False, "period_times_verified": False,
                     "experimental_timetable_included": False},
    }


def demo_tasks() -> list[dict]:
    today = _now().replace(second=0, microsecond=0)

    def at(days: int, hour: int, minute: int = 0) -> str:
        return (today + dt.timedelta(days=days)).replace(hour=hour, minute=minute).isoformat()

    rows = [
        ("第二次作业：序列比对与打分矩阵", "生物信息学", at(0, 23, 59)),
        ("实验报告：细胞器的分离与观察", "细胞生物学实验", at(3, 18)),
        ("文献阅读笔记（综述一篇）", "表观遗传学", at(8, 23, 59)),
        ("期中复习提纲", "基础生态学", None),
        ("第一次作业：GWAS 数据整理", "基因组学", at(-1, 23, 59)),
    ]
    return [{
        "id": f"mystu:task:{_id(title)}", "kind": "task", "title": title, "course": course,
        "url": None, "category": "assign", "due_at": due, "school_status": None,
        "status": "todo", "body": "",
        "due_at_status": "available" if due else "not_found_in_detail",
    } for title, course, due in rows]


def demo_notices() -> list[dict]:
    today = _now().date()
    rows = [
        ("关于举办第四届全国大学生职业规划大赛汕头大学校赛的通知", "学生通知", 0),
        ("2026-2027学年第一学期期中教学检查安排", "学生通知", 1),
        ("走向现代数学学术报告（No. 984）", "活动预告", 1),
        ("我校在第十届全国大学生生命科学竞赛中获佳绩", "学校要闻", 3),
    ]
    return [{
        "id": f"public:notice:{_id(title)}", "kind": "notice", "title": title,
        "category": category, "published_at": (today - dt.timedelta(days=days)).isoformat(),
        "url": "https://www.stu.edu.cn/",
    } for title, category, days in rows]


def demo_grades() -> list[dict]:
    rows = [
        ("2025-2026-2", "BIO2101", "生物化学", "89", 3.5, 3.7, "正常考试"),
        ("2025-2026-2", "BIO2203", "微生物学", "92", 3.0, 4.0, "正常考试"),
        ("2025-2026-2", "PE1002", "体育（二）", "良好", 1.0, None, "正常考试"),
        ("2025-2026-1", "MA1001", "高等数学（一）", "71", 4.0, 2.1, "正常考试"),
    ]
    return [{
        "id": f"jw:grade:{_id(sem, code, kind)}", "kind": "grade", "semester": sem,
        "course_code": code, "title": title, "score": score, "credits": credits,
        "gpa": gpa, "exam_type": kind,
    } for sem, code, title, score, credits, gpa, kind in rows]


def demo_exams(semester: str = "2026-2027-1") -> list[dict]:
    rows = [
        ("BIO3310", "生物信息学", "2027-01-12 08:00-10:00", "E座302", "021"),
        ("BIO2205", "生物统计学", "2027-01-13 13:30-15:30", "E305", "114"),
        ("BIO3320", "表观遗传学", "", "", ""),
    ]
    return [{
        "id": f"jw:exam:{_id(semester, code, when)}", "kind": "exam", "semester": semester,
        "course_code": code, "title": title, "exam_time": when, "location": where,
        "seat_number": seat,
    } for code, title, when, where, seat in rows]
