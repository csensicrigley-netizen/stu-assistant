"""Demo payloads (STU_APP_DEMO=1): the whole App can be exercised end to end
without touching a school system.

Shapes follow stu-mcp v0.7.3 (docs/phase0/01-data-contract.md). Timetable,
rooms, teachers, tasks, exams, grades and notices are all synthetic sample
data aligned with web/src/lib/preview.ts.
"""
from __future__ import annotations

import datetime as dt
import hashlib

TZ = dt.timezone(dt.timedelta(hours=8))
TEACHING = list(range(3, 19))

# big section -> periods (official timetable, 13 periods)
BIG = {1: [1, 2], 2: [3, 4, 5], 3: [6, 7], 4: [8, 9], 5: [11, 12, 13]}

ROWS = [
    (1, 1, "微积分（二）", "林致远", "明理楼-A201", TEACHING),
    (1, 3, "大学英语写作", "陈思宁", "博雅楼-B108", TEACHING),
    (2, 2, "数据结构与算法", "周景行", "致知楼-C304", TEACHING),
    (2, 3, "概率论与数理统计", "许明川", "明理楼-A305", TEACHING),
    (3, 2, "人机交互设计", "唐若琳", "创新工坊-202", TEACHING),
    (3, 5, "现代设计史导论", "梁书瑶", "文澜楼-阶梯三", TEACHING),
    (4, 1, "数据结构与算法", "周景行", "致知楼-C304", TEACHING),
    (4, 2, "信息可视化基础", "程砚秋", "致知楼-C412", TEACHING),
    (4, 3, "前端工程实践", "沈清扬", "实验楼-数字媒体室508", TEACHING),
    (4, 4, "前端工程实践", "沈清扬", "实验楼-数字媒体室508", TEACHING),
    (5, 2, "数据库系统原理", "宋闻达", "致知楼-C208", TEACHING),
    (6, 2, "创新项目综合实践", "唐若琳", "创客空间-301", [6, 7, 8, 11, 12]),
    (6, 3, "创新项目综合实践", "唐若琳", "创客空间-301", [6, 7, 8, 11, 12]),
    (7, 2, "跨学科研讨课", "程砚秋", "文澜楼-研讨室105", [9, 10, 12, 13]),
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
        ("第三次实验：二叉树遍历与最短路径", "数据结构与算法", at(0, 23, 59)),
        ("原型评审报告：校园导览交互草图", "人机交互设计", at(3, 18)),
        ("案例分析：多维时间序列图表拆解", "信息可视化基础", at(8, 23, 59)),
        ("期中知识点整理提纲", "数据库系统原理", None),
        ("第一次作业：SQL 关联查询练习", "数据库系统原理", at(-1, 23, 59)),
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
        ("关于举办第四届全国大学生职业规划大赛校赛的通知", "学生通知", 0),
        ("2026-2027学年第一学期期中教学检查安排", "学生通知", 1),
        ("通识学术讲座：人工智能与数据可视化前沿", "活动预告", 1),
        ("我校学子在全国大学生创新创业竞赛中获佳绩", "学校要闻", 3),
    ]
    return [{
        "id": f"public:notice:{_id(title)}", "kind": "notice", "title": title,
        "category": category, "published_at": (today - dt.timedelta(days=days)).isoformat(),
        "url": "https://www.stu.edu.cn/",
    } for title, category, days in rows]


def demo_grades() -> list[dict]:
    rows = [
        ("2025-2026-2", "CS1102", "程序设计基础（二）", "91", 3.5, 3.9, "正常考试"),
        ("2025-2026-2", "MA1204", "线性代数", "88", 3.0, 3.6, "正常考试"),
        ("2025-2026-2", "PE1002", "体育（二）", "良好", 1.0, None, "正常考试"),
        ("2025-2026-1", "MA1001", "微积分（一）", "76", 4.0, 2.6, "正常考试"),
    ]
    return [{
        "id": f"jw:grade:{_id(sem, code, kind)}", "kind": "grade", "semester": sem,
        "course_code": code, "title": title, "score": score, "credits": credits,
        "gpa": gpa, "exam_type": kind,
    } for sem, code, title, score, credits, gpa, kind in rows]


def demo_exams(semester: str = "2026-2027-1") -> list[dict]:
    rows = [
        ("CS2101", "数据结构与算法", "2027-01-12 08:00-10:00", "致知楼-C304", "028"),
        ("MA2205", "概率论与数理统计", "2027-01-13 13:30-15:30", "明理楼-A305", "106"),
        ("DS2310", "人机交互设计", "", "", ""),
    ]
    return [{
        "id": f"jw:exam:{_id(semester, code, when)}", "kind": "exam", "semester": semester,
        "course_code": code, "title": title, "exam_time": when, "location": where,
        "seat_number": seat,
    } for code, title, when, where, seat in rows]
