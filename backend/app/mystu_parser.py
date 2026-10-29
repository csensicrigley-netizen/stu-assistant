"""MySTU 课程清单解析的修补版（对应 stu_mcp 0.7.3 的 parser.parse_inventory）。

上游按「课程 id」判重，任何 id 出现两次就整份清单作废（`schema_changed:
MySTU 课程标识重复`）。学校的 MySTU 现在会把**同一门课的不同学期**放进同一份
清单里（id 相同、学年学期不同），于是登录直接失败 —— 作业就永远读不到。

这里只改判重口径：按 (id, 学年, 学期) 三元组判重，其余逻辑逐行沿用上游的辅助
函数，不重新发明解析。升级 stu-mcp 后请重新对照上游本文件。
"""
from __future__ import annotations

from stu_mcp.features.mystu.parser import (  # noqa: F401 - 复用上游辅助函数
    Course,
    Inventory,
    complete_list,
    course_url,
    current_courses,
    optional_text,
    payload_records,
    schema_error,
)
from stu_mcp.features.mystu.schema import COURSE_FIELDS


def parse_inventory(payload: dict) -> Inventory:
    courses = payload_records(payload)
    known = set()
    for course in courses:
        if isinstance(course.get(COURSE_FIELDS["id"]), bool):
            raise schema_error("MySTU 课程标识变为布尔值，无法确认当前课程范围。")
        if any(isinstance(course.get(COURSE_FIELDS[field]), bool) for field in ("year", "term")):
            raise schema_error("MySTU 课程年份或学期变为布尔值，无法确认当前课程范围。")
        identity = course.get(COURSE_FIELDS["id"]) or course.get(COURSE_FIELDS["url"])
        if not isinstance(identity, (str, int)) or isinstance(identity, bool) or not str(identity).strip():
            raise schema_error("MySTU 课程缺少可识别的记录。")
        # 判重口径：同一门课在不同学年/学期是两条合法记录
        cid = (
            str(identity),
            course.get(COURSE_FIELDS["year"]),
            course.get(COURSE_FIELDS["term"]),
        )
        if cid in known:
            raise schema_error("MySTU 课程标识重复，无法确认当前课程范围。")
        known.add(cid)

    selected = current_courses(courses)
    dated = [course for course in courses if type(course.get(COURSE_FIELDS["year"])) is int
             and type(course.get(COURSE_FIELDS["term"])) is int]
    complete = complete_list(payload, len(courses)) and len(dated) == len(courses)
    semester = (f"{selected[0][COURSE_FIELDS['year']]}-{selected[0][COURSE_FIELDS['term']]}"
                if selected and dated else None)

    result = []
    seen_records = set()
    for course in selected:
        name = next((course.get(key) for key in COURSE_FIELDS["title"] if course.get(key)), None)
        title = optional_text(name, "课程名称")
        if not title:
            raise schema_error("MySTU 课程缺少名称，保留原缓存。")
        record_id = str(course.get(COURSE_FIELDS["id"]) or course[COURSE_FIELDS["url"]])
        record_key = (record_id, course.get(COURSE_FIELDS["year"]), course.get(COURSE_FIELDS["term"]))
        if record_key in seen_records:
            continue  # 同一门课在清单里出现两次：只留一条
        seen_records.add(record_key)
        result.append(Course(
            id=record_id, title=title,
            url=course_url(course.get(COURSE_FIELDS["url"])),
            year=course.get(COURSE_FIELDS["year"]), term=course.get(COURSE_FIELDS["term"]),
            teacher=optional_text(course.get(COURSE_FIELDS["teacher"]), "教师")))
    return Inventory(result, complete, semester)
