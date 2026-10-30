"""Pure parsing of the theoretical timetable — a patched copy of stu-mcp 0.7.3.

Classes may span multiple 大节 rows and appear more than once in the grid.
Usable courses survive malformed inventory rows or blocks, but every skipped
record is reported in coverage/pending. Recheck these adaptations when upgrading
stu-mcp rather than replacing this module without its regression tests.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin

from bs4 import BeautifulSoup, Tag

from stu_mcp.parsing import clean, is_login, item_id
from stu_mcp.runtime import AppError
from stu_mcp.features.jw.schema import (
    JW_TERM_PATTERN,
    JW_TIMETABLE_COLUMNS,
    JW_TIMETABLE_FIELDS,
    JW_TIMETABLE_URL,
)


def changed(message: str) -> AppError:
    return AppError("schema_changed", "教务课表" + message, "jw")


def page(html: str) -> BeautifulSoup:
    if is_login(html):
        raise AppError("login_expired", "教务登录已过期，请在设置页重新登录。", "jw")
    return BeautifulSoup(html, "html.parser")


def one(nodes: list, message: str):
    if len(nodes) != 1:
        raise changed(message)
    return nodes[0]


def value(node: Tag) -> str:
    result = node.get("value", "")
    if not isinstance(result, str) or len(result) > 1024:
        raise changed("查询字段无法识别。")
    return result


@dataclass(frozen=True)
class TimetableForm:
    semester: str
    semesters: list[dict]
    data: dict[str, str]


def timetable_form(html: str) -> TimetableForm:
    soup = page(html)
    form = one([f for f in soup.select("form") if f.select('select[name="xnxq01id"]')],
               "未返回唯一的学期查询表单。")
    if (str(form.get("method", "")).lower() != "post"
            or urljoin(JW_TIMETABLE_URL, str(form.get("action", ""))) != JW_TIMETABLE_URL):
        raise changed("查询路线改变，已停止发送表单。")
    data, selects, checkboxes = {}, {}, {}
    for control in form.select("input[name],select[name],textarea[name]"):
        name = control.get("name")
        if control.name == "input" and control.get("type") in {"button", "submit", "reset"}:
            continue
        if (name not in JW_TIMETABLE_FIELDS or name in data or name in checkboxes
                or control.has_attr("disabled")):
            raise changed("查询字段重复或发生变化。")
        if control.name == "select":
            if control.has_attr("multiple"):
                raise changed("查询选项无法识别。")
            options = control.select("option")
            if not 1 <= len(options) <= 100 or len({value(o) for o in options}) != len(options):
                raise changed("查询选项重复或缺失。")
            selected = [o for o in options if o.has_attr("selected")]
            if len(selected) > 1:
                raise changed("选定学期或周次有歧义。")
            data[name] = value(selected[0] if selected else options[0])
            selects[name] = options
        elif control.name == "input" and control.get("type") == "hidden":
            data[name] = value(control)
        elif control.name == "input" and control.get("type") == "checkbox":
            checkboxes[name] = value(control)
            if control.has_attr("checked"):
                data[name] = value(control)
        else:
            raise changed("查询控件发生变化。")
    if (set(selects) != {"zc", "xnxq01id", "kbjcmsid"}
            or not {"cj0701id", "demo"}.issubset(data)
            or "wkbkc" not in checkboxes or not checkboxes["wkbkc"]
            or "" not in {value(o) for o in selects["zc"]} or not data["kbjcmsid"]):
        raise changed("缺少全部周次或未排课查询控件。")
    semesters = [{"semester": value(o), "label": clean(o.get_text(" ", strip=True))}
                 for o in selects["xnxq01id"] if value(o)]
    if (not semesters or any(not re.fullmatch(JW_TERM_PATTERN, o["semester"]) for o in semesters)
            or data["xnxq01id"] not in {o["semester"] for o in semesters}):
        raise changed("学期选项无法确认。")
    # These are the actual page's read filters, not generated or guessed form values.
    data.update(zc="", wkbkc=checkboxes["wkbkc"])
    return TimetableForm(data["xnxq01id"], semesters, data)


def _rows(table: Tag) -> list[Tag]:
    return [row for row in table.select("tr") if row.find_parent("table") is table]


def _cells(row: Tag) -> list[Tag]:
    return row.find_all(["th", "td"], recursive=False)


def _text(node: Tag) -> str:
    result = clean(node.get_text(" ", strip=True))
    if len(result) > 2000:
        raise changed("字段超过可识别范围。")
    return result


def _inventory(soup: BeautifulSoup, table_id: str) -> tuple[list[dict], int]:
    table = one(soup.select("#" + table_id), "课程明细表缺失或重复。")
    rows = _rows(table)
    candidates = []
    for index, row in enumerate(rows):
        labels = [re.sub(r"\s+", "", _text(c)) for c in _cells(row)]
        matches = {k: [i for i, label in enumerate(labels) if label in aliases]
                   for k, aliases in JW_TIMETABLE_COLUMNS.items()}
        if all(matches.values()):
            if any(len(indices) != 1 for indices in matches.values()):
                raise changed("课程表头存在重复含义。")
            candidates.append((index, {k: indices[0] for k, indices in matches.items()}, len(labels)))
    head, columns, width = one(candidates, "课程表头缺失或有歧义。")
    result = []
    skipped = 0
    for position, row in enumerate(rows):
        if position == head:
            continue
        cells = _cells(row)
        if (len(cells) == 1 and _text(cells[0]) in {"无课表课程", "有课表课程"}
                and cells[0].name == "th" and not cells[0].find("table")
                and re.fullmatch(r"[1-9][0-9]*", str(cells[0].get("colspan", "")))):
            continue
        if len(cells) != width or any(c.get("rowspan", "1") != "1" or
                                     c.get("colspan", "1") != "1" or c.find("table") for c in cells):
            # 保留可解析的课程，但必须向调用方报告漏掉的明细。
            skipped += 1
            continue
        record = {key: _text(cells[index]) for key, index in columns.items()}
        if not all(record[key] for key in ("class_name", "course_code", "title")):
            skipped += 1
            continue
        result.append(record)
    if len(result) > 1000:
        raise changed("课程明细超过读取上限。")
    return result, skipped


def meeting_time(raw: str) -> tuple[list[int], list[list[int]]]:
    """Expand weeks; this page enumerates period numbers with hyphens (03-04-05)."""
    text = re.sub(r"\s+", "", raw).translate(str.maketrans({"（": "(", "）": ")", "［": "[",
                                                           "］": "]", "，": ",", "、": ","}))
    match = re.fullmatch(r"([0-9,\-]+)\((单|双)?周\)(?:\((单|双)\))?\[([0-9,\-]+)节\]", text)
    if not match or match[2] and match[3] and match[2] != match[3]:
        raise changed("周次／节次表达无法无歧义解析。")
    weeks = set()
    for part in match[1].split(","):
        interval = re.fullmatch(r"([0-9]{1,2})(?:-([0-9]{1,2}))?", part)
        if not interval:
            raise changed("周次表达无法识别。")
        first, last = int(interval[1]), int(interval[2] or interval[1])
        if not 1 <= first <= last <= 60:
            raise changed("周次超出可识别范围。")
        weeks.update(range(first, last + 1))
    parity = match[2] or match[3]
    if parity:
        weeks = {w for w in weeks if w % 2 == (1 if parity == "单" else 0)}
    parts = re.split("[-,]", match[4])
    if any(not re.fullmatch(r"[0-9]{1,2}", p) for p in parts):
        raise changed("节次表达无法识别。")
    periods = [int(p) for p in parts]
    if not weeks or periods != sorted(set(periods)) or not all(1 <= p <= 30 for p in periods):
        raise changed("周次／节次重复或超出范围。")
    runs: list[list[int]] = []
    for period in periods:
        if not runs or period != runs[-1][-1] + 1:
            runs.append([])
        runs[-1].append(period)
    return sorted(weeks), runs


def _label(text: str, label: str) -> str:
    return re.sub(r"^" + re.escape(label) + r"\s*[:：]\s*", "", text)


def _blocks(node: Tag) -> list[dict]:
    for child in node.children:
        if isinstance(child, Tag):
            if child.name not in {"font", "br"} and not (
                    child.name == "div" and "item-box" in child.get("class", [])):
                raise changed("授课安排包含未识别内容。")
            if child.name == "div" and (_text(child) or child.select("table,font")):
                raise changed("授课提示框含未识别业务内容。")
        elif clean(child) and not re.fullmatch(r"-+", clean(child)):
            raise changed("授课安排包含未识别文本。")
    records = []
    for font in node.find_all("font", recursive=False):
        title, name = font.get("title"), font.get("name")
        if font.find("table") or title and title not in {
                "教师", "周次(节次)", "教学楼", "教室", "通知单编号", "班级", "备注"}:
            raise changed("授课安排出现未识别业务字段。")
        if not title and not name:
            records.append({"title": _text(font)})
        elif title:
            if not records or title in records[-1]:
                raise changed("授课安排字段顺序或含义有歧义。")
            records[-1][title] = _label(_text(font), title)
        elif name not in {"wkxx", "xsks"}:
            raise changed("授课安排出现未知字段。")
        elif name == "wkxx":
            if not records or "online_info" in records[-1]:
                raise changed("网络授课信息缺少所属课程。")
            records[-1]["online_info"] = _text(font)
    if _text(node) and not records:
        raise changed("授课安排缺少可识别字段。")
    return records


def parse_timetable(html: str, semester: str) -> dict:
    form = timetable_form(html)
    if form.semester != semester:
        raise changed("返回的学期与查询不符。")
    # timetable_form normalizes outgoing zc; inspect the returned selection as evidence as well.
    soup = page(html)
    weeks_select = one(soup.select('form select[name="zc"]'), "周次查询字段有歧义。")
    chosen = weeks_select.select("option[selected]") or weeks_select.select("option")[:1]
    if not chosen or value(chosen[0]) != "":
        raise changed("未返回全部周次。")
    include_unarranged = one(soup.select('form input[name="wkbkc"]'), "未排课查询字段有歧义。")
    if not include_unarranged.has_attr("checked"):
        raise changed("未返回已启用的未排课查询，不能确认完整范围。")
    arranged, skipped_arranged = _inventory(soup, "dataTables2")
    unarranged, skipped_unarranged = _inventory(soup, "dataTables")
    skipped_inventory = skipped_arranged + skipped_unarranged
    courses = {}
    index = {}
    for row in arranged:
        cid = item_id("jw", "timetable", semester, row["course_code"], row["class_name"])
        record = courses.setdefault(cid, {"id": cid, "title": row["title"], "course_code": row["course_code"],
                                          "class_name": row["class_name"], "teachers": [], "meetings": []})
        if record["title"] != row["title"]:
            raise changed("同一课程身份对应不同名称。")
        if row["teacher"] not in record["teachers"]:
            record["teachers"].append(row["teacher"])
        key = (row["class_name"], row["title"], row["teacher"])
        if key in index:
            raise changed("课程明细身份重复或有歧义。")
        index[key] = cid
    grid = one(soup.select("#timetable"), "星期节次网格缺失或重复。")
    rows = _rows(grid)
    if not rows:
        raise changed("星期网格为空。")
    headings = [_text(c) for c in _cells(rows[0])]
    weekdays = {"星期" + word: number for number, word in enumerate("一二三四五六日", 1)}
    if len(headings) != 8 or set(headings[1:]) != set(weekdays):
        raise changed("星期表头缺失或有歧义。")
    seen, matched_details, grid_periods, blocks = set(), set(), set(), 0
    skipped_time = skipped_blocks = 0
    # Every 小节 that appears anywhere in the grid: a class may legitimately span
    # two adjacent 大节 rows (the page writes [06-07-08-09节] inside 第三大节),
    # so blocks are validated against the whole page, not one row.
    all_periods: set[int] = set()
    for row in rows[1:]:
        probe = _cells(row)
        if len(probe) != 8:
            continue
        hit = re.search(r"[（(]([0-9,，]+)小节[）)]", _text(probe[0]))
        if hit:
            all_periods.update(int(p) for p in re.split("[,，]", hit[1]))
    for row in rows[1:]:
        cells = _cells(row)
        if cells and re.fullmatch(r"备注[:：]?", _text(cells[0])) and not row.select(".kbcontent"):
            continue
        if len(cells) != 8 or any(c.get("rowspan", "1") != "1" or
                                  c.get("colspan", "1") != "1" for c in cells):
            raise changed("星期网格行结构改变。")
        period_match = re.search(r"[（(]([0-9,，]+)小节[）)]", _text(cells[0]))
        if not period_match:
            raise changed("大节与实际节次的对应无法确认。")
        period_values = [int(p) for p in re.split("[,，]", period_match[1])]
        row_periods = set(period_values)
        if (not row_periods or period_values != sorted(row_periods)
                or any(not 1 <= p <= 30 for p in row_periods) or grid_periods & row_periods):
            raise changed("节次网格重复或超出范围。")
        grid_periods.update(row_periods)
        for heading, cell in zip(headings[1:], cells[1:], strict=True):
            nodes = cell.select(".kbcontent")
            if not nodes:
                if _text(cell):
                    skipped_blocks += 1
                continue
            for node in nodes:
                for block in _blocks(node):
                    required = {"title", "教师", "周次(节次)", "班级", "通知单编号", "教室"}
                    if not required.issubset(block) or not block["通知单编号"]:
                        skipped_blocks += 1
                        continue
                    key = (block["班级"], block["title"], block["教师"])
                    if key not in index:
                        # 明细里没有这条（比如明细行被跳过了）：跳过这个块，别把整份课表废掉
                        skipped_blocks += 1
                        continue
                    cid = index[key]
                    try:
                        weeks, runs = meeting_time(block["周次(节次)"])
                    except AppError:
                        # 周次写法没认出来的那一条跳过，但**要记账**：
                        # 静默跳过会让"漏了一门课"看起来像"完整课表"，
                        # 然后 sync 拿它整份替换缓存（审查报告第 08 项）。
                        skipped_time += 1
                        continue
                    if not set(sum(runs, [])).issubset(all_periods):
                        raise changed("授课节次与网格对应不一致。")
                    matched_details.add(key)
                    building, room = block.get("教学楼", ""), block["教室"]
                    location = room if not building or room.startswith(building) else clean(building + " " + room)
                    for periods in runs:
                        # The page has a class-level notice ID, not a meeting primary key.
                        # Week sets distinguish split arrangements in the same slot. Teacher/
                        # room edits retain identity; a change to recurrence defines a new one.
                        mid = item_id("jw", "meeting", cid, block["通知单编号"], weekdays[heading], periods, weeks)
                        if mid in seen:
                            # A class that spans two 大节 rows is printed in both
                            # rows with the same identity; the second copy adds
                            # nothing. Upstream aborted the whole read here.
                            continue
                        seen.add(mid)
                        courses[cid]["meetings"].append({"id": mid, "weekday": weekdays[heading], "weeks": weeks,
                            "periods": periods, "teacher": block["教师"], "location": location,
                            "building": building, "room": room, "raw_time": block["周次(节次)"],
                            "notes": block.get("备注", ""), "online_info": block.get("online_info", "")})
                    blocks += 1
    if not grid_periods and not courses:
        raise changed("缺少节次网格，不能认定为空课表。")
    # 只有「一门有安排的课都没有」才算真失败；个别课程没排上不要紧
    courses = {k: v for k, v in courses.items() if v["meetings"]}
    if not courses and not unarranged:
        raise changed("课表里没有任何可用的课程安排。")
    if len(courses) > 500 or len(seen) > 2000:
        raise changed("课程明细与授课安排不完整或超过上限。")
    pending = []
    if unarranged:
        pending.append("学校列有未排课课程，导出前须确认这些课程的安排。")
    unplaced = [{"course_id": index[key], "class_name": key[0], "title": key[1], "teacher": key[2]}
                for key in sorted(index.keys() - matched_details)]
    if unplaced:
        pending.append("部分已排课教师明细没有对应的网格安排，须在学校核对，不能认定这些安排为空。")
    if skipped_time:
        pending.append(f"有 {skipped_time} 条安排的周次写法没认出来，已跳过；这份课表不完整，别当成全部课。")
    if skipped_inventory:
        pending.append(f"有 {skipped_inventory} 条课程明细无法识别，须在学校核对。")
    if skipped_blocks:
        pending.append(f"有 {skipped_blocks} 条网格安排缺少字段或无法匹配明细，须在学校核对。")
    unscheduled = {}
    for row in unarranged:
        cid = item_id("jw", "timetable", semester, row["course_code"], row["class_name"])
        if cid in courses:
            raise changed("同一课程同时出现在已排课和未排课明细。")
        c = unscheduled.setdefault(cid, {"id": cid, **{k: row[k] for k in ("title", "course_code", "class_name")},
                                        "teachers": []})
        if c["title"] != row["title"] or row["teacher"] in c["teachers"]:
            raise changed("未排课课程身份重复或有歧义。")
        c["teachers"].append(row["teacher"])
    for course in courses.values():
        course["teachers"].sort()
        course["teacher"] = "、".join(filter(None, course["teachers"]))
        course["meetings"].sort(key=lambda m: (m["weekday"], m["periods"], m["weeks"], m["id"]))
    for course in unscheduled.values():
        course["teachers"].sort()
    return {"semester": semester, "available_semesters": form.semesters,
            "courses": sorted(courses.values(), key=lambda c: c["id"]),
            "unarranged": sorted(unscheduled.values(), key=lambda c: c["id"]),
            "unplaced_details": unplaced, "pending": pending,
            "coverage": {"kind": "theory", "weeks": "all",
                         "complete": not unplaced and not (skipped_time or skipped_inventory or skipped_blocks),
                         "course_count": len(courses), "meeting_count": len(seen), "grid_arrangements": blocks,
                         "unarranged_count": len(unscheduled), "unplaced_detail_count": len(unplaced),
                         "skipped_time_count": skipped_time,
                         "skipped_inventory_count": skipped_inventory,
                         "skipped_block_count": skipped_blocks,
                         "calendar_verified": False,
                         "period_times_verified": False, "experimental_timetable_included": False}}
