from html import escape

import pytest

from app import jw_timetable as jw
from stu_mcp.runtime import AppError

SEMESTER = "2026-2027-1"


def block(title="Good", time="1-2(周)[1-2节]", omit=None):
    fields = {"教师": "Teacher", "周次(节次)": time, "班级": title,
              "通知单编号": title, "教室": "Room"}
    return f"<font>{title}</font>" + "".join(
        f'<font title="{key}">{escape(value)}</font>' for key, value in fields.items() if key != omit)


def inventory(titles):
    return "".join("<tr>" + "".join(f"<td>{escape(v)}</td>" for v in (t, t, t, "Teacher")) + "</tr>" for t in titles)


def page(blocks, titles=("Good", "Bad"), extra_inventory="", unarranged=(), second_row=""):
    headings = "".join(f"<th>{escape(aliases[0])}</th>" for aliases in jw.JW_TIMETABLE_COLUMNS.values())
    return f'''<form method="post" action="{jw.JW_TIMETABLE_URL}">
<select name="xnxq01id"><option value="{SEMESTER}" selected>{SEMESTER}</option></select>
<select name="zc"><option value="" selected>全部</option></select>
<select name="kbjcmsid"><option value="default">默认</option></select>
<input type="hidden" name="cj0701id" value=""><input type="hidden" name="demo" value="">
<input type="checkbox" name="wkbkc" value="1" checked></form>
<table id="dataTables2"><tr>{headings}</tr>{inventory(titles)}{extra_inventory}</table>
<table id="dataTables"><tr>{headings}</tr>{inventory(unarranged)}</table>
<table id="timetable"><tr><th>节次</th>{''.join('<th>星期'+d+'</th>' for d in '一二三四五六日')}</tr>
<tr><td>第一大节（1,2小节）</td><td><div class="kbcontent">{blocks}</div></td>{'<td></td>'*6}</tr>{second_row}</table>'''


@pytest.mark.parametrize("bad_first", [True, False])
def test_bad_time_is_reported_regardless_of_order(bad_first):
    good, bad = block(), block("Bad", "unrecognized")
    doc = jw.parse_timetable(page(bad + good if bad_first else good + bad), SEMESTER)
    assert [c["title"] for c in doc["courses"]] == ["Good"]
    assert doc["coverage"]["complete"] is False
    assert doc["coverage"]["skipped_time_count"] == 1
    assert [r["title"] for r in doc["unplaced_details"]] == ["Bad"]
    assert doc["pending"]


@pytest.mark.parametrize("skipped", [block("Bad", omit="教室"), block("Unknown")])
def test_incomplete_and_unmatched_blocks_are_not_silent(skipped):
    doc = jw.parse_timetable(page(block() + skipped, titles=("Good",)), SEMESTER)
    assert doc["coverage"]["complete"] is False
    assert doc["coverage"]["skipped_block_count"] == 1
    assert doc["pending"]


@pytest.mark.parametrize("row", ['<tr><td colspan="2">bad</td></tr>', '<tr><td>Bad</td><td></td><td>Bad</td><td>Teacher</td></tr>'])
def test_skipped_inventory_marks_coverage_incomplete(row):
    doc = jw.parse_timetable(page(block(), titles=("Good",), extra_inventory=row), SEMESTER)
    assert doc["coverage"]["skipped_inventory_count"] == 1
    assert doc["coverage"]["complete"] is False
    assert doc["pending"]


def test_all_bad_blocks_fail_instead_of_replacing_cache_with_empty_timetable():
    with pytest.raises(AppError) as error:
        jw.parse_timetable(page(block("Bad", "unrecognized"), titles=("Bad",)), SEMESTER)
    assert error.value.code == "schema_changed"


def test_only_unarranged_courses_do_not_use_uninitialized_counters():
    doc = jw.parse_timetable(page("", titles=(), unarranged=("Later",)), SEMESTER)
    assert doc["courses"] == []
    assert doc["unarranged"][0]["title"] == "Later"
    assert doc["coverage"]["skipped_time_count"] == 0
    assert doc["pending"]


def test_spanning_periods_and_duplicate_grid_copy_still_work():
    course = block(time="1-2(周)[1-2-3-4节]")
    second = f'<tr><td>第二大节（3,4小节）</td><td><div class="kbcontent">{course}</div></td>{"<td></td>"*6}</tr>'
    doc = jw.parse_timetable(page(course, titles=("Good",), second_row=second), SEMESTER)
    assert doc["coverage"]["complete"] is True
    assert len(doc["courses"][0]["meetings"]) == 1
    assert doc["courses"][0]["meetings"][0]["periods"] == [1, 2, 3, 4]
    assert doc["pending"] == []


def test_compat_installs_the_app_parser(monkeypatch):
    from app import stucompat
    from stu_mcp.features.jw import adapter, service
    monkeypatch.setattr(stucompat, "_installed", False)
    monkeypatch.setattr(adapter, "parse_timetable", None)
    # Some upstream versions bind the parser in service as well as adapter.
    monkeypatch.setattr(service, "parse_timetable", None, raising=False)
    stucompat.install()
    assert adapter.parse_timetable is jw.parse_timetable
    assert service.parse_timetable is jw.parse_timetable
