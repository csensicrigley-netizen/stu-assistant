"""手动录入课表：解析、校验、落库、租户隔离。

    python tests/check_import.py

不联网、不登录学校。用 TestClient 跑真实的 FastAPI 应用（生产模式）。
结构照抄 tests/check_sync.py：一行一个 expect，最后打印 ALL OK。
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

TMP = tempfile.mkdtemp(prefix="stu-import-check-")
os.environ["STU_APP_HOME"] = TMP
os.environ["STU_APP_JWT_SECRET"] = "x" * 48
os.environ.pop("STU_APP_DEMO", None)
os.environ.pop("STU_MCP_HOME", None)
os.environ.pop("STU_MCP_KEY_FILE", None)

from fastapi.testclient import TestClient  # noqa: E402

from app import timetable_import as ti  # noqa: E402
from app.config import settings  # noqa: E402
from app.db import DB  # noqa: E402
from app.main import app  # noqa: E402

SEM = "2026-2027-1"

failures: list[str] = []


def expect(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


client = TestClient(app)


def register(email: str) -> dict:
    r = client.post("/v1/auth/register", json={"email": email, "passphrase": "passphrase-123"})
    assert r.status_code == 200, r.text
    return {"token": r.json()["token"], "uid": r.json()["user"]["id"]}


db = DB(settings.db_path)
user_a = register("med-a@example.com")
user_b = register("med-b@example.com")

# ---- 1. 未登录：现有端点一律 401 -------------------------------------------
expect(client.get("/v1/timetable").status_code == 401, "GET /v1/timetable without a token → 401")
expect(client.get("/v1/timetable", headers={"Authorization": "Bearer nope"}).status_code == 401,
       "a forged token → 401")

# ---- 2. 粘贴格式 A：一行一课 ----------------------------------------------
PASTE_A = """细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 桑浦山-细胞生物学实验室-理433、434
高等数学 | 周一 1-2节,周三 3-4节 | 1-16周 | 张三 | 桑浦山-E305"""

a = ti.parse_paste(PASTE_A, semester=SEM)
expect(a["ok"] and a["errors"] == [], f"paste A parses with no errors ({a['errors'][:1]})")
expect(a["stats"]["courses"] == 2, f"paste A → 2 courses (got {a['stats']['courses']})")
expect(a["stats"]["meetings"] == 3, f"paste A → 3 meetings (got {a['stats']['meetings']})")

bio = next((c for c in a["courses"] if c["title"] == "细胞生物学实验"), None)
math = next((c for c in a["courses"] if c["title"] == "高等数学"), None)
expect(bio is not None and len(bio["meetings"]) == 1, "细胞生物学实验 has one meeting")
expect(math is not None and len(math["meetings"]) == 2, "高等数学 has two meetings (周一 + 周三)")

if bio and math:
    m = bio["meetings"][0]
    expect(m["weekday"] == 4, f"周四 → weekday 4 (got {m['weekday']})")
    expect(m["periods"] == [6, 7, 8, 9], f"6-9节 → periods 6..9 (got {m['periods']})")
    expect(m["weeks"] == list(range(1, 17)), "1-16周 → weeks 1..16")
    expect(m["teacher"] == "何昭明", f"teacher parsed (got {m['teacher']!r})")
    expect(m["location"] == "桑浦山-细胞生物学实验室-理433、434", f"location kept verbatim (got {m['location']!r})")

    slots = sorted((x["weekday"], x["periods"]) for x in math["meetings"])
    expect(slots == [(1, [1, 2]), (3, [3, 4])], f"周一 1-2 + 周三 3-4 (got {slots})")

# ---- 3. 粘贴格式 B：从 Excel 复制出来的制表符表格 --------------------------
PASTE_B = "\n".join([
    "课程名称\t星期\t节次\t周次\t教师\t地点",
    "细胞生物学实验\t四\t6-9\t1-16\t何昭明\t理433",
    "高等数学\t一\t1-2\t1-16\t张三\tE305",
])
b = ti.parse_paste(PASTE_B, semester=SEM)
expect(b["ok"] and b["errors"] == [], f"tab-separated table parses ({b['errors'][:1]})")
expect(b["stats"]["courses"] == 2 and b["stats"]["meetings"] == 2,
       f"format B → 2 courses / 2 meetings (got {b['stats']})")
b1 = next((c for c in b["courses"] if c["title"] == "细胞生物学实验"), None)
expect(b1 is not None and b1["meetings"][0]["weekday"] == 4 and b1["meetings"][0]["periods"] == [6, 7, 8, 9],
       "format B: 星期「四」+ 节次「6-9」拼成 weekday 4 / periods 6-9")
expect(b1 is not None and b1["meetings"][0]["location"] == "理433", "format B: 地点列读到 理433")

# 英文表头也认
PASTE_B_EN = "\n".join([
    "Course\tWeekday\tPeriod\tWeek\tTeacher\tRoom",
    "高等数学\t一\t1-2\t1-16\t张三\tE305",
])
b_en = ti.parse_paste(PASTE_B_EN, semester=SEM)
expect(b_en["ok"] and b_en["stats"]["courses"] == 1, f"English header row is skipped, not eaten ({b_en['errors'][:1]})")

# ---- 4. 简写变体 -----------------------------------------------------------
VARIANTS = [
    ("星期四 第6-9节", 4, [6, 7, 8, 9]),
    ("周四 6-9节", 4, [6, 7, 8, 9]),
    ("四 6-9", 4, [6, 7, 8, 9]),
    ("Thu 6-9", 4, [6, 7, 8, 9]),
    ("星期一 第1节-第2节", 1, [1, 2]),
    ("二 6,7,8,9", 2, [6, 7, 8, 9]),
    ("日 11-13", 7, [11, 12, 13]),
]
for text, want_wd, want_periods in VARIANTS:
    got = ti.parse_paste(f"测试课 | {text} | 1-16周 | 老师 | E305", semester=SEM)
    ok = (
        got["ok"]
        and got["courses"]
        and got["courses"][0]["meetings"][0]["weekday"] == want_wd
        and got["courses"][0]["meetings"][0]["periods"] == want_periods
    )
    expect(bool(ok), f"time variant {text!r} → weekday {want_wd} / periods {want_periods}")

WEEK_VARIANTS = [
    ("1-16周", list(range(1, 17))),
    ("1-16", list(range(1, 17))),
    ("第1-16周", list(range(1, 17))),
    ("1,3,5周", [1, 3, 5]),
    ("1-8,10-16周", [*range(1, 9), *range(10, 17)]),
    ("1-16单周", [1, 3, 5, 7, 9, 11, 13, 15]),
    ("1-16双周", [2, 4, 6, 8, 10, 12, 14, 16]),
]
for text, want in WEEK_VARIANTS:
    got = ti.parse_paste(f"测试课 | 周一 1-2节 | {text} | 老师 | E305", semester=SEM)
    ok = got["ok"] and got["courses"] and got["courses"][0]["meetings"][0]["weeks"] == want
    expect(bool(ok), f"week variant {text!r} → {len(want)} weeks")

# ---- 5. 错误输入：带行号的中文错误 ----------------------------------------
BAD = "\n".join([
    "细胞生物学实验 | 周四 6-9节 | 1-16周 | 何昭明 | 理433",
    "高等数学 | 周一 1-2节 | 1-16周 | 张三 | E305",
    "化学 | 周八 3-4节 | 1-16周 | 王五 | 理201",
])
bad = ti.parse_paste(BAD, semester=SEM)
expect(not bad["ok"], "a bad weekday makes the whole parse not ok")
hit = [e for e in bad["errors"] if "第 3 行" in e]
expect(bool(hit), f"the error names the real line (第 3 行): {bad['errors'][:1]}")
expect(any("看不懂" in e and "星期" in e for e in bad["errors"]),
       f"the error says what is wrong with 星期: {bad['errors'][:1]}")
expect(bad["stats"]["courses"] == 2, f"the two good lines still parse (got {bad['stats']['courses']})")

# ---- 6. 节次超范围 ---------------------------------------------------------
per = ti.parse_paste("高等数学 | 周一 14-15节 | 1-16周 | 张三 | E305", semester=SEM)
expect(not per["ok"], "periods 14-15 is rejected")
expect(any("14-15" in e and "1-13" in e for e in per["errors"]), f"period error names the range: {per['errors'][:1]}")
expect(not any(c["meetings"] for c in per["courses"]), "no meeting is kept from a rejected row")

# ---- 7. 周次超范围 ---------------------------------------------------------
wk = ti.parse_paste("高等数学 | 周一 1-2节 | 31-40周 | 张三 | E305", semester=SEM)
expect(not wk["ok"], "weeks 31-40 is rejected")
expect(any("31-40" in e and "1-30" in e for e in wk["errors"]), f"week error names the range: {wk['errors'][:1]}")

# ---- 8. 幂等：同一份输入两次解析，course id 完全相同 -----------------------
a2 = ti.parse_paste(PASTE_A, semester=SEM)
ids1 = sorted(c["id"] for c in a["courses"])
ids2 = sorted(c["id"] for c in a2["courses"])
expect(ids1 == ids2, f"parsing the same paste twice gives the same course ids ({ids1} vs {ids2})")
expect(all(i.startswith("course_") and len(i) == len("course_") + 16 for i in ids1),
       f"course id is course_<16 hex> ({ids1[:1]})")
mids1 = sorted(m["id"] for c in a["courses"] for m in c["meetings"])
mids2 = sorted(m["id"] for c in a2["courses"] for m in c["meetings"])
expect(mids1 == mids2, "meeting ids are stable too")

# 顺序变了 id 也不变
REORDERED = "\n".join(reversed(PASTE_A.splitlines()))
a3 = ti.parse_paste(REORDERED, semester=SEM)
expect(sorted(c["id"] for c in a3["courses"]) == ids1, "reordering the lines does not change ids")

# ---- 9. hue 稳定 -----------------------------------------------------------
hue1 = {c["id"]: c["hue"] for c in a["courses"]}
hue2 = {c["id"]: c["hue"] for c in a2["courses"]}
expect(hue1 == hue2, f"the same course keeps its hue ({hue1})")
expect(all(isinstance(h, int) and 0 <= h <= 359 for h in hue1.values()),
       f"hue is an integer in 0..359 ({sorted(hue1.values())})")
expect(ti._hue("course_deadbeefdeadbeef") == ti._hue("course_deadbeefdeadbeef"), "hue is a pure function of the id")

# ---- 10. 文档形状：能被 normalizeTimetable() 直接吃 -----------------------
doc = ti.to_timetable_doc(a["courses"], semester=SEM)
expect(doc["semester"] == SEM and doc["status"] == "ok", "to_timetable_doc keeps semester and status ok")
expect(doc["coverage"]["complete"] is True, "coverage.complete is true")
expect(set(doc) >= {"semester", "status", "checked_at", "courses", "unarranged", "pending", "coverage"},
       f"doc has the keys main.py reads: {sorted(doc)}")
meeting_keys = {"id", "course_id", "title", "class_name", "weekday", "weeks", "periods",
                "teacher", "location", "raw_time", "notes", "online_info"}
expect(meeting_keys <= set(doc["courses"][0]["meetings"][0]),
       f"meeting carries every field timetable.ts reads: {sorted(doc['courses'][0]['meetings'][0])}")

checked = ti.validate_doc(doc)
expect(checked["ok"] and checked["errors"] == [], f"validate_doc accepts our own doc ({checked['errors'][:1]})")
expect(checked["stats"]["courses"] == 2 and checked["stats"]["meetings"] == 3, "validate_doc keeps 2 courses / 3 meetings")
expect(sorted(c["id"] for c in checked["doc"]["courses"]) == ids1, "validate_doc does not re-hash the ids")
expect(checked["doc"]["courses"][0]["hue"] == hue1[checked["doc"]["courses"][0]["id"]], "validate_doc keeps the hue")

# 手写的坏文档也要被拦住
bad_doc = ti.validate_doc({"semester": SEM, "courses": [
    {"title": "化学", "meetings": [{"weekday": 9, "periods": [1, 2], "weeks": [1]}]},
    {"title": "", "meetings": []},
    {"title": "物理", "meetings": [{"weekday": 1, "periods": [99], "weeks": [1]}]},
]})
expect(not bad_doc["ok"], "validate_doc rejects weekday 9 / empty title / period 99")
expect(len(bad_doc["errors"]) >= 3, f"every bad item gets its own error ({len(bad_doc['errors'])})")

# ---- 11. tt_manual_save + tt_manual_get 往返 ------------------------------
saved = ti.save_manual(db, user_a["uid"], SEM, checked["doc"], school="med", note="医学院手录")
expect(saved["id"].startswith("ttman_") and len(saved["id"]) == len("ttman_") + 16,
       f"row id is ttman_<16 hex> ({saved['id']})")
expect(saved["school"] == "med" and saved["source"] == "manual", "school / source recorded")
expect(saved["note"] == "医学院手录", "note recorded")

row = db.tt_manual_get(user_a["uid"], SEM)
expect(row is not None, "tt_manual_get finds the row")
expect(row is not None and row["semester"] == SEM and row["school"] == "med", "semester / school round-trip")
expect(row is not None and row["doc"]["courses"][0]["title"] == checked["doc"]["courses"][0]["title"],
       "payload round-trips through JSON")
_bio_row = next((c for c in (row or {}).get("doc", {}).get("courses", []) if c["title"] == "细胞生物学实验"), None)
expect(_bio_row is not None and _bio_row["meetings"][0]["periods"] == [6, 7, 8, 9],
       f"nested meeting periods survive the round-trip ({_bio_row['meetings'][0]['periods'] if _bio_row else None})")
expect(row is not None and isinstance(row["created_at"], float) and isinstance(row["updated_at"], float),
       "created_at / updated_at are floats")
expect(db.tt_manual_get(user_a["uid"], "2025-2026-2") is None, "an unknown semester returns None")

# 镜像：GET /v1/timetable 读的就是 items 里 kind=timetable 的 payload
items = db.items_of(user_a["uid"], "timetable")
expect(len(items) == 1, f"exactly one timetable row was mirrored into items (got {len(items)})")
expect(items and items[0]["external_id"] == f"doc:{SEM}", "mirrored row uses sync._flatten_timetable's external_id")
payload = items[0]["payload"] if items else {}
expect(payload.get("courses") and payload["courses"][0]["title"] == checked["doc"]["courses"][0]["title"],
       "the mirrored payload carries the courses main.py returns")
expect(payload.get("status") == "ok" and payload.get("semester") == SEM, "mirrored payload keeps status / semester")
expect(set(payload) >= {"semester", "status", "checked_at", "courses", "unarranged", "pending", "coverage"},
       "mirrored payload has every key GET /v1/timetable reads")

# ---- 12. 同一用户同一学期再保存是覆盖 -------------------------------------
first_id = saved["id"]
again = ti.save_manual(db, user_a["uid"], SEM, checked["doc"], school="med", note="第二次")
expect(again["id"] == first_id, f"second save reuses the same row ({again['id']} vs {first_id})")
expect(db.tt_manual_get(user_a["uid"], SEM)["note"] == "第二次", "the newer note wins")
expect(again["created_at"] == saved["created_at"], "created_at stays at the first save")
expect(again["updated_at"] >= saved["updated_at"], "updated_at moves forward")
expect(len(db.tt_manual_list(user_a["uid"])) == 1, "still one row for this user + semester")
expect(len(db.items_of(user_a["uid"], "timetable")) == 1, "still one mirrored item")

# 第二个学期是另一行
other_sem = "2026-2027-2"
db.tt_manual_save(user_a["uid"], other_sem, checked["doc"], school="med", source="csv")
expect(len(db.tt_manual_list(user_a["uid"])) == 2, "a different semester is a separate row")

# 删除只删这一个学期
expect(db.tt_manual_delete(user_a["uid"], other_sem) is True, "tt_manual_delete removes the row")
expect(db.tt_manual_get(user_a["uid"], other_sem) is None, "the deleted semester is gone")
expect(db.tt_manual_get(user_a["uid"], SEM) is not None, "the other semester is untouched")
expect(db.tt_manual_delete(user_a["uid"], other_sem) is False, "deleting twice reports False")

# ---- 13. 租户隔离：B 读不到 A 的 ------------------------------------------
expect(db.tt_manual_get(user_b["uid"], SEM) is None, "user B cannot read user A's manual timetable")
expect(db.tt_manual_list(user_b["uid"]) == [], "user B's list is empty")
expect(not db.items_of(user_b["uid"], "timetable"), "user B has no mirrored timetable item either")

db.tt_manual_save(user_b["uid"], SEM, ti.to_timetable_doc(
    ti.parse_paste("大学英语 | 周二 3-4节 | 1-16周 | 李四 | E201", semester=SEM)["courses"], semester=SEM,
), school="med")
row_b = db.tt_manual_get(user_b["uid"], SEM)
row_a = db.tt_manual_get(user_a["uid"], SEM)
expect(row_b is not None and row_b["id"] != row_a["id"], "two users get two different rows")
expect(row_b["doc"]["courses"][0]["title"] == "大学英语", "user B sees only its own course")
expect(row_a["doc"]["courses"][0]["title"] != "大学英语", "user A's copy is unchanged")

# 删掉 B 不影响 A
db.tt_manual_delete(user_b["uid"], SEM)
expect(db.tt_manual_get(user_a["uid"], SEM) is not None, "deleting B's row leaves A's alone")

# ---- 14. 模板 -----------------------------------------------------------------
tpl = ti.template_csv()
expect(tpl.startswith("\ufeff"), "template starts with a BOM so Excel reads UTF-8")
expect(tpl.splitlines()[0].lstrip("\ufeff").startswith("课程名称"), "template header uses 课程名称")
tpl_parsed = ti.parse_csv(tpl, semester=SEM)
expect(tpl_parsed["ok"] and tpl_parsed["errors"] == [], f"the template itself parses ({tpl_parsed['errors'][:1]})")
expect(tpl_parsed["stats"]["courses"] == 3, f"template has 3 example courses (got {tpl_parsed['stats']['courses']})")

CSV = "\n".join([
    "课程名称,星期,节次,周次,教师,地点",
    "细胞生物学实验,四,6-9,1-16,何昭明,桑浦山-细胞生物学实验室-理433、434",
    "高等数学,一,1-2,1-16,张三,桑浦山-E305",
    "高等数学,三,3-4,1-16,张三,桑浦山-E305",
])
csv_doc = ti.parse_csv(CSV, semester=SEM)
expect(csv_doc["ok"] and csv_doc["stats"]["courses"] == 2, f"CSV parses ({csv_doc['errors'][:1]})")
expect(sorted(c["id"] for c in csv_doc["courses"]) == ids1,
       "CSV and paste of the same courses give the same ids (idempotent across formats)")
expect(csv_doc["stats"]["meetings"] == 3, "CSV merges the two 高等数学 rows into one course with two meetings")

# ---- 15. 只写课程名 → 进「没排时间」而不是报错 ----------------------------
partial = ti.parse_paste("医学伦理学 | 王老师", semester=SEM)
expect(partial["ok"], f"a course with no time is not an error ({partial['errors'][:1]})")
expect(len(partial["unarranged"]) == 1, "it lands in unarranged")
expect(any("没排时间" in w for w in partial["warnings"]), "and it is warned about")
expect(ti.to_timetable_doc(partial["courses"], semester=SEM)["status"] == "partial",
       "a doc with unarranged courses is status=partial")

# ---- 16. 空输入 -----------------------------------------------------------
empty = ti.parse_body({"text": "   ", "format": "paste", "semester": SEM})
expect(not empty["ok"] and empty["errors"], f"empty text explains itself: {empty['errors'][:1]}")
blank = ti.parse_paste("", semester=SEM)
expect(not blank["ok"] and "没有解析出任何课程" in blank["errors"][0], "blank paste says nothing was parsed")

# ---- 16b. 教师姓「周」、房间号纯数字，不能被当成周次 ----------------------
tricky = ti.parse_paste("化学 | 周一 1-2节 | 1-16周 | 周老师 | 305", semester=SEM)
expect(tricky["ok"], f"a teacher named 周老师 still parses ({tricky['errors'][:1]})")
_tm = tricky["courses"][0]["meetings"][0]
expect(_tm["teacher"] == "周老师", f"周老师 stays the teacher, not a week range (got {_tm['teacher']!r})")
expect(_tm["location"] == "305", f"room 305 stays the location (got {_tm['location']!r})")
expect(len(_tm["weeks"]) == 16, "the real 1-16周 is what sets the weeks")

roomy = ti.parse_paste("化学 | 周一 1-2节 | 王五 | 305", semester=SEM)
expect(roomy["ok"] and roomy["courses"][0]["meetings"][0]["teacher"] == "王五",
       "with no week column, 305 goes to location, not weeks")

# ---- 17. 路由薄壳（main.py 挂 5 个路由时直接调这三个） --------------------
save_res = ti.save_body(db, user_a["uid"], {"doc": checked["doc"], "school": "med", "note": "路由壳"})
expect(save_res["ok"] and save_res["semester"] == SEM, f"save_body ok ({save_res.get('errors')})")
load_res = ti.load_body(db, user_a["uid"], SEM)
expect(load_res["doc"] is not None and load_res["school"] == "med", "load_body returns the doc + school")
expect(ti.load_body(db, user_a["uid"], "2099-2100-1")["doc"] is None, "load_body returns doc=None when absent")
expect(ti.delete_body(db, user_a["uid"], SEM)["ok"] is True, "delete_body removes it")
expect(ti.delete_body(db, user_a["uid"], SEM)["ok"] is False, "delete_body on nothing reports ok=false")

rejected = ti.save_body(db, user_a["uid"], {"doc": {"semester": SEM, "courses": []}, "school": "med"})
expect(not rejected["ok"] and rejected["errors"], f"save_body refuses an empty doc ({rejected['errors'][:1]})")

# 学校 id 不认识时退回 main，不报错
fallback = ti.save_body(db, user_a["uid"], {"doc": checked["doc"], "school": "nope"})
expect(fallback["ok"] and fallback["school"] == "main", "an unknown school id falls back to main")

# ---- 19. 镜像到位后，现有的 GET /v1/timetable 原样返回 --------------------
auth_a = {"Authorization": f"Bearer {user_a['token']}"}
served = client.get("/v1/timetable", headers=auth_a)
expect(served.status_code == 200, "GET /v1/timetable answers 200 for the manual doc")
tt = served.json()
expect(tt.get("empty") is False, "the endpoint does not report empty")
expect(tt.get("semester") == SEM, f"it serves our semester ({tt.get('semester')})")
titles = sorted(c["title"] for c in tt.get("courses", []))
expect(titles == ["细胞生物学实验", "高等数学"], f"it serves both courses: {titles}")
meeting = next(m for c in tt["courses"] for m in c["meetings"])
expect({"weekday", "weeks", "periods", "location", "teacher"} <= set(meeting),
       f"meetings come back in the shape timetable.ts reads: {sorted(meeting)}")
expect(tt.get("status") in {"ok", "partial"}, "status is one of the two values normalizeTimetable accepts")
expect("coverage" in tt and tt["coverage"].get("complete") is True, "coverage.complete survives")

# 换成用户 B 读，看不到 A 的课
auth_b = {"Authorization": f"Bearer {user_b['token']}"}
tt_b = client.get("/v1/timetable", headers=auth_b).json()
expect(tt_b.get("empty") is True or not tt_b.get("courses"), "user B's /v1/timetable stays empty")

db.tt_manual_delete(user_a["uid"], SEM)

# ---- 20. HTTP 路由薄壳：main.py 有没有真的把这 5 个路由挂上 ----------------
# 上面 17 节测的是 timetable_import 的函数；这一节测的是「路由存在且薄」。
# 少了它，route 被别人从 main.py 里删掉也不会报错（前端会直接 404）。
P = "/v1/timetable/import/parse"
S = "/v1/timetable/import/save"
M = "/v1/timetable/manual"
T = "/v1/timetable/import/template"

expect(client.post(P, json={"text": PASTE_A, "format": "paste", "semester": SEM}).status_code == 401,
       "POST import/parse without a token → 401")
expect(client.get(T).status_code == 401, "GET import/template without a token → 401")

r = client.post(P, headers=auth_a, json={"text": PASTE_A, "format": "paste", "semester": SEM})
expect(r.status_code == 200, f"POST import/parse answers 200 ({r.status_code})")
pb = r.json()
expect(pb.get("ok") is True and len(pb.get("courses") or []) >= 1,
       f"parse route returns courses ({len(pb.get('courses') or [])})")
expect({"courses", "unarranged", "errors", "warnings", "stats", "semester"} <= set(pb),
       f"parse route returns the ImportPreview shape: {sorted(pb)}")

r = client.post(P, headers=auth_a, json={"text": "", "format": "paste", "semester": SEM})
expect(r.status_code == 200 and r.json().get("ok") is False,
       "an empty paste is a 200 with ok=false, not a 4xx")

saved_doc = ti.to_timetable_doc(pb["courses"], semester=SEM)
r = client.post(S, headers=auth_a, json={"doc": saved_doc, "school": "med", "note": "路由壳"})
expect(r.status_code == 200 and r.json().get("ok") is True,
       f"POST import/save answers 200 ok ({r.status_code} {r.text[:80]})")

r = client.get(M, headers=auth_a, params={"semester": SEM})
expect(r.status_code == 200, f"GET timetable/manual answers 200 ({r.status_code})")
mb = r.json()
expect(mb.get("doc") is not None and mb.get("school") == "med",
       f"manual route returns the saved doc + school ({mb.get('school')!r})")
expect(mb.get("semester") == SEM, "manual route echoes the semester")

r = client.get(M, headers=auth_a, params={"semester": "2099-2100-1"})
expect(r.status_code == 200 and r.json().get("doc") is None,
       "an unknown semester answers 200 with doc=null")

r = client.get(T, headers=auth_a)
expect(r.status_code == 200, f"GET import/template answers 200 ({r.status_code})")
expect("csv" in r.headers.get("content-type", ""), f"template is served as text/csv ({r.headers.get('content-type')})")
expect(r.text.startswith("\ufeff"), "the downloaded template keeps its BOM")
expect("课程名称" in r.text, "the downloaded template has the header row")

# 不传 semester → 400，别让它静默删掉「所有学期」
expect(client.delete(M, headers=auth_a).status_code == 400, "DELETE manual without a semester → 400")
r = client.delete(M, headers=auth_a, params={"semester": SEM})
expect(r.status_code == 200 and r.json().get("ok") is True, "DELETE manual removes the row")
expect(client.get(M, headers=auth_a, params={"semester": SEM}).json().get("doc") is None,
       "after DELETE the doc is gone")

# ---- 18. 常量 -------------------------------------------------------------
expect([s["id"] for s in ti.SCHOOLS] == ["main", "med", "other"], "SCHOOLS keeps main / med / other in order")
expect(ti.MAX_PERIOD == 13 and ti.MAX_WEEK == 30, "limits are 13 periods / 30 weeks")

print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
sys.exit(1 if failures else 0)
