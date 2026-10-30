"""医学院教务适配层：加密、登录、课表、成绩、考试、学籍、只读约束。

    python tests/check_med.py

**完全离线**：不联网、不登录医学院、不需要账号。所有输入都是手工构造的 fixture，
结构照 `docs/医学院教务接口.md` 抄；所有 `parse_*` / `error_text` 都是纯函数。
结构照 `tests/check_import.py`：一行一个 expect，最后打印 ALL OK，失败退出码 1。
"""
from __future__ import annotations

import base64
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

# 和别的 check_* 保持一致：给配置一个临时家目录，免得碰到真实数据
os.environ.setdefault("STU_APP_HOME", tempfile.mkdtemp(prefix="stu-med-check-"))

from app import med_jw as mj  # noqa: E402
from app import timetable_import as ti  # noqa: E402

SEM = "2026-2027-1"

failures: list[str] = []
checks = 0


def expect(cond: bool, what: str) -> None:
    global checks
    checks += 1
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


def has_latin(text: str) -> bool:
    """面向用户的文案里不该出现英文技术词（两个以上连续字母就算）。"""
    return bool(re.search(r"[A-Za-z]{2,}", str(text or "")))


# --------------------------------------------------------------------------
# fixture 工具
# --------------------------------------------------------------------------

def lesson(**kw) -> dict:
    """一条课表记录（`kbList` 里的一项），字段名照文档 §3.1。"""
    base = {
        "kcmc": "细胞生物学实验",
        "kcywmc": "Cell Biology Lab",
        "teaxms": "何昭明",
        "jxcdmc": "桑浦山-理433",
        "jcdm": "3-4",
        "jcdm2": "3,4",
        "zc": "1-16周",
        "jxbmc": "细胞生物学实验01班",
        "kcbh": "BIO2101",
        "qssj": "10:00",
        "jssj": "11:40",
    }
    base.update(kw)
    return base


def week(**by_day) -> list:
    """{'1': [...], '3': [...]} → 长度 7 的数组（索引 0..6 = 周一..周日）。"""
    days: list[list] = [[], [], [], [], [], [], []]
    for day, rows in by_day.items():
        days[int(day) - 1].extend(rows)
    return days


def payload_of(kb, **extra) -> dict:
    """`Studentkb/index` 的响应外壳（文档 §3.1），`kbList` 默认是字符串。"""
    body = {
        "code": 200,
        "xnxqdm": SEM,
        "firstDayOfWeek": "2026-08-31",
        "zc": 8,
        "curDay": 4,
        "stillWeek": 8,
        "rq": "2026-10-29",
        "minzc": 1,
        "maxzc": 20,
        "kbList": json.dumps(kb, ensure_ascii=False) if isinstance(kb, list) else kb,
    }
    body.update(extra)
    return body


def one_lesson(**kw) -> dict:
    """把上面那门课放在周一第 3-4 节，变成一份只有一节课的课表文档。"""
    row = lesson(**kw)
    return mj.parse_timetable(payload_of(week(**{"1": [row]})), semester=SEM)


# --------------------------------------------------------------------------
# 1. 地址与常量
# --------------------------------------------------------------------------
expect(mj.BASE == "https://jwgl.med.stu.edu.cn", f"BASE 是医学院站点 ({mj.BASE})")
expect(mj.API == mj.BASE + "/dev-api/appapi", f"API 是 /dev-api/appapi ({mj.API})")
expect(mj.TOKEN_HEADER == "token", "鉴权头名是小写 token（不是 Authorization）")
expect(mj.SOURCE == "med", "来源标记是 med，不和主校区的 jw 混")
expect(mj.captcha_url("1793280939807") == mj.API + "/captcha/getCaptchaCode?yzmcode=1793280939807",
       f"验证码地址拼对: {mj.captcha_url('1793280939807')}")
expect(mj.url_of("/appapi/Studentkb/index") == mj.API + "/Studentkb/index",
       f"/appapi/x 拼成完整地址: {mj.url_of('/appapi/Studentkb/index')}")
expect(mj.url_of("https://example.test/x") == "https://example.test/x", "完整地址原样返回")

stamp = mj.new_yzm_code()
expect(isinstance(stamp, str) and stamp.isdigit(), f"验证码时间戳是数字字符串 ({stamp})")
expect(len(stamp) == 13, f"验证码时间戳是 13 位毫秒 ({len(stamp)})")
first = int(mj.new_yzm_code())
time.sleep(0.004)
second = int(mj.new_yzm_code())
expect(second > first, f"两次取时间戳不同（{second} > {first}），每次登录都要新取一张图")

# --------------------------------------------------------------------------
# 2. RSA-512 / PKCS#1 v1.5
# --------------------------------------------------------------------------
cipher_a = mj.rsa_password("med-pass-2026")
cipher_b = mj.rsa_password("med-pass-2026")
expect(len(cipher_a) == 88, f"密文 Base64 固定 88 字符 ({len(cipher_a)})")
expect(mj.RSA_B64_LENGTH == 88, "常量 RSA_B64_LENGTH 就是 88")
expect(len(base64.b64decode(cipher_a)) == 64, "Base64 解码后是 64 字节（512 位模长）")
expect(cipher_a != cipher_b, "同一个密码两次加密结果不同（PKCS#1 v1.5 随机填充）")
expect(int.from_bytes(base64.b64decode(cipher_a), "big") < mj._MODULUS, "密文数值在模数范围内")
expect(mj._MODULUS.bit_length() == 512, f"硬编码公钥模长 512 位 ({mj._MODULUS.bit_length()})")
expect(mj._EXPONENT == 65537, "公钥指数 65537")

# 硬编码的 DER 解出来的 (n, e) 必须和 cryptography 载入的一致
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import padding, rsa  # noqa: E402

_loaded = serialization.load_der_public_key(base64.b64decode(mj.PUBLIC_KEY_B64))
_numbers = _loaded.public_numbers()
expect(_numbers.n == mj._MODULUS and _numbers.e == mj._EXPONENT,
       "自己解的 DER 和 cryptography 载入的公钥完全一致")

# 纯 Python 那条路：用自建密钥走一遍 pad + pow，再用私钥解回来
# （cryptography 50 只允许生成 1024 位以上的密钥，所以这里用 1024 位验证算法本身；
#   算法与模长无关，512 位那条路在上面 88 字符 / 64 字节的断言里已经覆盖）
_own = rsa.generate_private_key(public_exponent=65537, key_size=1024)
_own_nums = _own.public_key().public_numbers()
secret = "医学@2026".encode("utf-8")
_hand = mj._rsa_encrypt(secret, _own_nums.n, _own_nums.e)
expect(len(_hand) == 128, f"手算 RSA 输出 128 字节（1024 位模长）({len(_hand)})")
expect(_own.decrypt(_hand, padding.PKCS1v15()) == secret,
       "手算 RSA（PKCS#1 v1.5 填充 + pow）能被私钥正确解回原文")

padded = mj._pkcs1_pad(b"abc", 64)
expect(len(padded) == 64, f"填充块长度 64 ({len(padded)})")
expect(padded[0] == 0 and padded[1] == 2, "填充块以 00 02 开头（PKCS#1 v1.5 块类型 2）")
expect(padded.endswith(b"\x00abc"), "填充块以 00 + 明文结尾")
_ps = padded[2:padded.index(b"\x00", 2)]
expect(len(_ps) >= 8 and all(b != 0 for b in _ps), f"随机填充至少 8 字节且全非零 ({len(_ps)})")

expect(len(mj.rsa_password("中文密码也行")) == 88, "中文密码同样产出 88 字符")
expect(len(mj.rsa_password("x" * mj.RSA_MAX_PLAIN)) == 88, "53 字节（上限）的密码可以加密")
try:
    mj.rsa_password("x" * (mj.RSA_MAX_PLAIN + 1))
    too_long_ok = False
except mj.MedError as exc:
    too_long_ok = True
    long_text = str(exc)
expect(too_long_ok, "超过 53 字节的密码给中文错误而不是崩掉")
expect(not has_latin(long_text), f"超长密码的错误是纯中文: {long_text}")
try:
    mj.rsa_password("")
    empty_ok = False
except mj.MedError as exc:
    empty_ok = True
    empty_text = str(exc)
expect(empty_ok and not has_latin(empty_text), f"空密码给中文错误: {empty_text}")

# --------------------------------------------------------------------------
# 3. 登录：成功 / 失败 / 未登录
# --------------------------------------------------------------------------
LOGIN_OK = {
    "code": 200,
    "user": {
        "token": "tok-abc-123", "usertype": "0", "userdm": "U001", "userxm": "张三",
        "userbh": "2023001", "userdh": "13800000000", "userdwmc": "医学院",
        "userAccount": "2023001",
    },
    "weakPass": 0, "initPw": 0, "isbind": "1", "twofactorAuth": 0,
}
ok_login = mj.parse_login(LOGIN_OK)
expect(ok_login["ok"] is True, "登录成功 → ok=True")
expect(ok_login["token"] == "tok-abc-123", f"token 从 user.token 取出 ({ok_login['token']})")
expect(ok_login["user"]["name"] == "张三" and ok_login["user"]["number"] == "2023001",
       "登录结果里带姓名和学号")
expect(ok_login["error"] == "", "成功时没有错误文案")

bad_login = mj.parse_login({"msg": "app_retrun_error_applogin", "code": 500})
expect(bad_login["ok"] is False and bad_login["token"] == "", "账号密码错 → ok=False，没有 token")
expect("不正确" in bad_login["error"], f"i18n key 翻成中文: {bad_login['error']}")
expect(not has_latin(bad_login["error"]), f"错误文案里没有英文技术词: {bad_login['error']}")

expired = mj.parse_login({"msg": "请先登录！", "code": 401})
expect(expired["ok"] is False and "重新登录" in expired["error"],
       f"401 → 提示重新登录: {expired['error']}")

no_token = mj.parse_login({"code": 200, "user": {"userxm": "张三"}})
expect(no_token["ok"] is False and not has_latin(no_token["error"]),
       f"200 但没带凭证 → 中文错误: {no_token['error']}")

weak = mj.parse_login({**LOGIN_OK, "weakPass": 1})
expect(weak["ok"] is False and "改密码" in weak["error"],
       f"弱口令（weakPass=1）不算登录成功: {weak['error']}")

for junk in (None, "不是字典", [], 0):
    got = mj.parse_login(junk)
    expect(got["ok"] is False and not has_latin(got["error"]),
           f"登录返回不是字典（{type(junk).__name__}）也不炸，给中文错误")

body = mj.login_body("2023001", "med-pass-2026", "a1b2", "1793280939807")
expect(set(body) == {"username", "password", "code", "appid", "yzmCode", "verifyCode"},
       f"登录体字段和前端一致: {sorted(body)}")
expect(body["code"] == "" and body["appid"] is None, "code 恒为空串、appid 为 null")
expect(body["yzmCode"] == 1793280939807 and isinstance(body["yzmCode"], int),
       f"yzmCode 是数字时间戳 ({body['yzmCode']!r})")
expect(len(body["password"]) == 88, "登录体里的密码是 88 字符密文")
expect("med-pass-2026" not in json.dumps(body, ensure_ascii=False),
       "登录体里没有明文密码")

# --------------------------------------------------------------------------
# 4-8. 课表：真实结构（kbList 是字符串，里面是 7 个 dict，按节次码分组）
# --------------------------------------------------------------------------


def real_payload(rows, semester_note=""):
    """按真实响应的结构造一份：7 天，每天 {节次码: [记录]}。"""
    days = [{} for _ in range(7)]
    for row in rows:
        day = int(row.get("xq", 1)) - 1
        bucket = row["_bucket"]
        days[day].setdefault(bucket, []).append({k: v for k, v in row.items() if k != "_bucket"})
    return {"code": 200, "kbList": json.dumps(days, ensure_ascii=False) + semester_note}


def rec(**kw):
    base = {"kcmc": "细胞生物学实验", "teaxms": "何昭明", "jxcdmc": "理433",
            "szxqmc": "桑浦山校区", "kcbh": "BIO2101", "jxbmc": "细胞生物学实验01班",
            "xq": 1, "zc": 1, "jcdm": "0304", "jcdm2": "03,04",
            "qssj": "10:00:00", "jssj": "11:40:00", "_bucket": "03"}
    base.update(kw)
    return base


one = mj.parse_timetable(real_payload([rec()]), semester=SEM)
expect(len(one["courses"]) == 1, f"一门课 → 1 门 ({len(one['courses'])})")
course = one["courses"][0]
meeting = course["meetings"][0]
expect(course["title"] == "细胞生物学实验", "课程名取 kcmc")
expect(course["code"] == "BIO2101", f"课程号取 kcbh ({course['code']})")
expect(course["teacher"] == "何昭明", "教师取 teaxms")
expect(meeting["weekday"] == 1, f"xq=1 → 周一 ({meeting['weekday']})")
expect(meeting["periods"] == [3, 4], f"jcdm2 '03,04' → [3,4] ({meeting['periods']})")
expect(meeting["weeks"] == [1], f"zc=1 → [1] ({meeting['weeks']})")
expect("理433" in meeting["location"], f"地点含教室 ({meeting['location']})")
expect(meeting["rawTime"].startswith("周一 第3-4节"), f"时间原文拼出来了 ({meeting['rawTime']})")
expect(course["id"] == ti._course_id("细胞生物学实验", "", "BIO2101"), "课程 id 复用既有规则")
# meeting id 现在带地点后缀：不同周次换教室必须能分成两段（审查报告第 16 项）
expect(meeting["id"].startswith(ti._meeting_id(course["id"], 1, [3, 4])),
       f"meeting id 以既有规则为前缀 ({meeting['id']})")

checked = ti.validate_doc(one)
expect(checked["ok"] is True and checked["errors"] == [],
       f"解析结果能过 validate_doc ({checked['errors'][:1]})")
DOC_KEYS = {"semester", "status", "checked_at", "courses", "unarranged", "pending", "coverage"}
expect(DOC_KEYS <= set(one), f"doc 键齐全: {sorted(DOC_KEYS - set(one))}")
expect(one["semester"] == SEM and one["status"] == "ok", "semester / status 对")

# 整学期展开：同一门课同一星期同一节次，每周一条 → 必须合并成 1 个时间段，周次收集
weeks = [rec(zc=w) for w in range(1, 17)]
many = mj.parse_timetable(real_payload(weeks), semester=SEM)
expect(len(many["courses"]) == 1, f"16 条周记录 → 1 门课 ({len(many['courses'])})")
m0 = many["courses"][0]["meetings"]
expect(len(m0) == 1, f"16 条周记录 → 1 个时间段 ({len(m0)})")
expect(m0[0]["weeks"] == list(range(1, 17)), f"周次合并成 1..16 ({len(m0[0]['weeks'])})")

# 同一门课换老师：不拆课，老师并成一条名单
two_t = [rec(zc=8, teaxms="欧少闽"), rec(zc=9, teaxms="罗舜庭")]
mt = mj.parse_timetable(real_payload(two_t), semester=SEM)
expect(len(mt["courses"]) == 1, f"换老师不拆课 ({len(mt['courses'])})")
expect("欧少闽" in mt["courses"][0]["meetings"][0]["teacher"]
       and "罗舜庭" in mt["courses"][0]["meetings"][0]["teacher"],
       f"老师并成名单 ({mt['courses'][0]['meetings'][0]['teacher']})")

# 不同星期的同一门课 → 同一门课，两个时间段
diff_day = [rec(xq=1, _bucket="03"), rec(xq=3, _bucket="06", jcdm2="06,07", jcdm="0607")]
dd = mj.parse_timetable(real_payload(diff_day), semester=SEM)
expect(len(dd["courses"]) == 1 and len(dd["courses"][0]["meetings"]) == 2,
       f"不同星期 → 1 门课 2 段 ({len(dd['courses'][0]['meetings'])})")

# kbList 已经是数组（不是字符串）也要能吃
arr = json.loads(json.dumps(real_payload([rec()])["kbList"]))
expect(len(mj.parse_timetable({"kbList": arr}, semester=SEM)["courses"]) == 1, "kbList 是数组也能解析")

# 节次写成 "0102"（两位数粘连）也要能拆
glue = mj.parse_timetable(real_payload([rec(jcdm2="", jcdm="0102")]), semester=SEM)
expect(glue["courses"][0]["meetings"][0]["periods"] == [1, 2],
       f"jcdm '0102' → [1,2] ({glue['courses'][0]['meetings'][0]['periods']})")

# 坏输入：一律不抛异常，给空课表
for bad in ({"kbList": "{不是 json"}, {"kbList": None}, {}, {"kbList": "[]"}):
    got = mj.parse_timetable(bad, semester=SEM)
    expect(isinstance(got, dict) and got["courses"] == [], f"坏 kbList 不炸: {str(bad)[:20]}")

# 没写星期的记录：用外层索引兜底，不丢
no_xq = rec()
no_xq.pop("xq")
fallback = mj.parse_timetable(real_payload([no_xq]), semester=SEM)
expect(len(fallback["courses"]) == 1, "记录里没有 xq 时用外层索引兜底")

# 空课表（后面 sync_result 要用）
no_time = mj.parse_timetable(real_payload([]), semester=SEM)
EMPTY_STR = ""
expect(no_time["courses"] == [], "空课表：没有课程")

# id 稳定：同样输入两次，id 一致（前端拿它当 key）
a1 = mj.parse_timetable(real_payload([rec()]), semester=SEM)["courses"][0]["id"]
a2 = mj.parse_timetable(real_payload([rec()]), semester=SEM)["courses"][0]["id"]
expect(a1 == a2, "课程 id 两次解析一致")

# --------------------------------------------------------------------------
# 9. 成绩
# --------------------------------------------------------------------------
GRADES = {
    "code": 200,
    "kccjList": [
        {"kcmc": "生物化学", "kcywmc": "Biochemistry", "zcj": 89, "cjfsdm": "01", "xf": 3.5,
         "xnxqmc": "2025-2026-2", "kcdlmc": "专业必修", "kcbh": "BIO2101", "cjdm": "CJ001"},
        {"kcmc": "体育（二）", "zcj": "良好", "cjfsdm": "02", "xf": 1.0,
         "xnxqmc": "2025-2026-2", "kcdlmc": "通识必修", "kcbh": "PE1002", "cjdm": "CJ002"},
        {"kcmc": "军事理论", "zcj": 55, "cjfsdm": "01", "xf": 2.0,
         "xnxqmc": "2025-2026-2", "kcdlmc": "通识必修", "kcbh": "MI1001", "cjdm": "CJ003"},
    ],
    "canViewDetail": True, "canViewRanking": False, "zcjblxsdws": 1,
}
grades = mj.parse_grades(GRADES)
expect(len(grades) == 3, f"3 条成绩 ({len(grades)})")
g1 = grades[0]
expect(g1["title"] == "生物化学", "成绩：课程名取 kcmc")
expect(g1["score"] == "89", f"成绩：zcj → score ({g1['score']!r})")
expect(g1["credits"] == 3.5, f"成绩：xf → credits ({g1['credits']!r})")
expect(g1["course_code"] == "BIO2101", "成绩：kcbh → course_code")
expect(g1["semester"] == "2025-2026-2", "成绩：xnxqmc → semester")
expect(g1["course_type"] == "专业必修", "成绩：kcdlmc → course_type")
expect(g1["detail_id"] == "CJ001", "成绩：cjdm 留给明细接口")
expect(g1["kind"] == "grade" and g1["external_id"] == g1["id"], "成绩条目 kind / external_id 齐")
expect(g1["failed"] is False, "89 分不算不及格")
expect(grades[2]["failed"] is True, "55 分标为不及格（百分制 < 60）")
expect(grades[1]["failed"] is False, "等级制（非百分制）不去猜及不及格")
expect(grades[1]["score_mode"] != "百分制", "等级制的 score_mode 不是百分制")
expect(len({g["id"] for g in grades}) == 3, "3 条成绩 id 互不相同")
expect([g["id"] for g in mj.parse_grades(GRADES)] == [g["id"] for g in grades],
       "成绩 id 稳定（重复同步不会变成新条目）")

expect(mj.parse_grades({"code": 200, "kccjList": []}) == [], "空成绩列表 → 空列表")
expect(mj.parse_grades({}) == [] and mj.parse_grades(None) == [], "缺 kccjList / 非字典 → 空列表不炸")
expect(mj.parse_grades({"kccjList": [None, 5, {"kcmc": ""}]}) == [], "脏行全部跳过")

# --------------------------------------------------------------------------
# 10. 考试
# --------------------------------------------------------------------------
EXAMS = {
    "code": 200,
    "xnxqdm": SEM,
    "ksapList": [
        {"kcmc": "生物信息学", "ksrq": "2027-01-12", "kssj": "08:00-10:00",
         "kscdmc": "E座302", "xqmc": "桑浦山", "zwh": "021", "zc": 19, "xq": 2,
         "jcdm2": "第1-2节", "khxsmc": "闭卷", "ksxs": "2", "ksaplxmc": "期末考试",
         "kcbh": "BIO3310", "ksrcdm": "K001"},
        {"kcmc": "生物统计学", "kssj": "13:30-15:30", "kcbh": "BIO2205"},
    ],
}
exams = mj.parse_exams(EXAMS)
expect(len(exams) == 2, f"2 场考试 ({len(exams)})")
e1 = exams[0]
expect(e1["title"] == "生物信息学", "考试：课程名取 kcmc")
expect(e1["exam_time"] == "2027-01-12 08:00-10:00", f"考试：日期+时间拼起来 ({e1['exam_time']})")
expect(e1["location"] == "E座302", "考试：考场取 kscdmc")
expect(e1["seat_number"] == "021", "考试：座位号取 zwh")
expect(e1["week"] == 19 and e1["weekday"] == 2, "考试：周次/星期是整数")
expect(e1["open_book"] is False, "ksxs='2' → 闭卷")
expect(e1["periods_text"] == "第1-2节", "考试：节次是给人看的文字")
expect(e1["kind"] == "exam" and e1["external_id"] == e1["id"], "考试条目 kind / external_id 齐")
expect(e1["semester"] == SEM, "考试：学期从顶层带下来")
expect(exams[1]["exam_time"] == "13:30-15:30", f"缺日期时只显示时间，不补假日期 ({exams[1]['exam_time']})")
expect([e["id"] for e in mj.parse_exams(EXAMS)] == [e["id"] for e in exams], "考试 id 稳定")
expect(mj.parse_exams({"ksapList": []}) == [] and mj.parse_exams(None) == [], "空考试列表 / 非字典不炸")

# --------------------------------------------------------------------------
# 11. 学籍 / 个人信息
# --------------------------------------------------------------------------
XJKP = {
    "code": 200,
    "isXs": True,
    "xjxx": {
        "xsbh": "2023001", "xsxm": "张三", "rxnf": "2023", "dqszj": "2023级", "xz": "4",
        "yxmc": "医学院", "zymc": "临床医学", "zyfxmc": "儿科学", "bjmc": "临床医学2023级1班",
        "xqmc": "桑浦山", "xsztmc": "在籍", "xjztmc": "正常", "xbmc": "男", "mzmc": "汉族",
        "sfzh": "440500200501011234", "dh": "13800000000", "email": "a@example.test",
        "dorm": "A座101",
    },
    "syxx": {"sydm": "X"},
}
profile = mj.parse_profile(XJKP)
expect(profile["ok"] is True and profile["source"] == "xjkp", "学籍卡 → ok，来源 xjkp")
expect(profile["name"] == "张三" and profile["number"] == "2023001", "学籍：姓名/学号")
expect(profile["college"] == "医学院", "学籍：院系取 yxmc")
expect(profile["major"] == "临床医学", "学籍：专业取 zymc")
expect(profile["major_direction"] == "儿科学", "学籍：专业方向取 zyfxmc")
expect(profile["grade"] == "2023级" and profile["enroll_year"] == "2023", "学籍：年级/入学年份")
expect(profile["class_name"] == "临床医学2023级1班", "学籍：班级取 bjmc")
expect(profile["status"] == "正常" and profile["student_status"] == "在籍", "学籍：学籍状态/学生状态")

info_profile = mj.parse_profile({"code": 200, "user": {
    "userxm": "张三", "userbh": "2023001", "userdwmc": "医学院", "userdh": "13800000000",
}})
expect(info_profile["ok"] is True and info_profile["source"] == "info", "账号信息 → 来源 info")
expect(info_profile["name"] == "张三" and info_profile["college"] == "医学院", "账号信息也能取姓名/单位")

blank_profile = mj.parse_profile(None)
expect(blank_profile["ok"] is False and not has_latin(blank_profile["error"]),
       f"空学籍返回 → 中文错误: {blank_profile['error']}")
expect(set(blank_profile) >= set(mj._PROFILE_KEYS) | {"ok", "source", "error"},
       "失败时字段形状也是齐的（前端不用判空）")
_payload_text = json.dumps(profile, ensure_ascii=False)
expect("440500200501011234" not in _payload_text, "身份证号没有搬进档案（App 用不上）")
expect("sfzh" not in _payload_text, "连身份证字段名都不出现")

# --------------------------------------------------------------------------
# 12. 错误翻译
# --------------------------------------------------------------------------
I18N_CASES = [
    ("app_retrun_error_applogin", "不正确"),
    ("app_retrun_error_applogina", "初始密码"),
    ("app_retrun_error_apploginb", "已过期"),
    ("app_retrun_error_apploginc", "身份证号"),
    ("app_retrun_error_apploginmndl", "不正确"),
]
for key, want in I18N_CASES:
    text = mj.error_text(500, key)
    expect(want in text and not has_latin(text), f"{key} → {text}")

expect(mj.error_text(200, "") == "", "成功没有错误文案")
expect("重新登录" in mj.error_text(401, ""), "401 → 重新登录")
expect("重新登录" in mj.error_text(501, ""), "501（前端按 401 同级处理）→ 重新登录")
expect("重新登" in mj.error_text(500, "请先登录！"), "中文未登录文案也翻成重新登录")
expect("重新登录" in mj.error_text(500, "您的登录状态已过期"), "登录过期文案也认")
expect("换一张" in mj.error_text(500, "验证码不正确"), "验证码错 → 提示换一张")
expect("换一张" in mj.error_text(500, "验证码已过期"), "验证码过期 → 提示换一张")
_unknown = mj.error_text(500, "")
expect("错误码 500" in _unknown and not has_latin(_unknown),
       f"未知错误带错误码且纯中文: {_unknown}")
expect("连不上" in mj.error_text(None, ""), "没有错误码 → 当成网络问题")
expect(not has_latin(mj.error_text(503, "service unavailable")),
       "服务端回了英文，也不把英文漏给用户")
expect(mj.error_text("401", "") == mj.error_text(401, ""), "字符串错误码也能认")

# --------------------------------------------------------------------------
# 13. 只读：白名单 + 写接口一律拦下
# --------------------------------------------------------------------------
READ_OK = ["/appapi/getIstoken", "/appapi/applogin", "/appapi/Studentkb/index",
           "/appapi/Studentcj/data", "/appapi/xsksxx/datas", "/appapi/Studentxjkp/index",
           "/appapi/captcha/getCaptchaCode"]
for path in READ_OK:
    expect(mj.is_read_path(path), f"只读白名单放行 {path}")
expect(mj.is_read_path("/appapi/Studentkb/index?x=1"), "带查询串也算同一个只读接口")

WRITE_PATHS = ["/appapi/Studentxk/addxk", "/appapi/Studentxk/cancel",
               "/appapi/UpPasswd", "/appapi/zhUpPasswd", "/appapi/Studentcjfhsq/save",
               "/appapi/applogout", "/appapi/getsmsCode", "/appapi/StudentExam/xsExam",
               "/appapi/Studentxjkp/xjkpsave", "/appapi/studentxjyd/save",
               "/appapi/Studentpjwj/save", "/appapi/Studentqjsq/save"]
for path in WRITE_PATHS:
    expect(not mj.is_read_path(path), f"写接口不在白名单 {path}")

_blocked = 0
_last = ""
for path in WRITE_PATHS:
    try:
        mj.post_api(path, body={})
    except mj.MedError as exc:
        _blocked += 1
        _last = str(exc)
expect(_blocked == len(WRITE_PATHS), f"{len(WRITE_PATHS)} 个写接口全部在发请求前被拦下（{_blocked}）")
expect(not has_latin(_last), f"拦截文案是纯中文: {_last}")
expect(getattr(mj.post_api, "__module__", "") == "app.med_jw", "post_api 只在这一个模块里")

expect(not any(marker in path for path in mj.READ_PATHS
               for marker in ("addxk", "cancel", "UpPasswd", "getsmsCode", "StudentExam",
                              "delete", "save", "submit", "bind")),
       "白名单里没有任何写接口的字样")
_suspicious = [name for name in dir(mj)
               if any(k in name.lower() for k in ("save", "add", "cancel", "delete",
                                                  "update", "passwd", "submit"))]
expect(_suspicious == [], f"模块里没有写操作函数 {_suspicious}")

SOURCE_TEXT = (Path(ROOT) / "app" / "med_jw.py").read_text(encoding="utf-8")
expect("sqlite3" not in SOURCE_TEXT, "模块不碰数据库（不落密码、不落 token）")
expect("json.dump(" not in SOURCE_TEXT and ".write(" not in SOURCE_TEXT, "模块不写文件")
expect(SOURCE_TEXT.count("urllib.request.urlopen") == 1, "只有一个出口，所有请求都过白名单")

# --------------------------------------------------------------------------
# 14. 请求体（纯字典，不发请求）
# --------------------------------------------------------------------------
expect(mj.timetable_body() == {"zc": "all", "jc": "", "teadm": ""},
       f"课表请求体默认 {mj.timetable_body()}")
expect(mj.timetable_body(8)["zc"] == 8, "按周查时 zc 是数字")
expect(mj.grades_body(SEM) == {"xnxqdm": SEM}, "成绩请求体")
expect(mj.exams_body() == {"xnxqdm": "", "ksaplxdm": ""}, "考试请求体默认全部类型")
expect(mj.profile_body() == {"yhtype": "", "xsdm": ""}, "学籍请求体：学生看自己两个参数都空")

# --------------------------------------------------------------------------
# 14b. 和现有前端（web/src/lib/med-api.ts）对齐的两个纯函数
# --------------------------------------------------------------------------
_data_url = mj.image_data_url(b"\xff\xd8\xff")
expect(_data_url == "data:image/jpeg;base64,/9j/",
       f"验证码能包成 data URL（前端 <img src> 直接吃）: {_data_url}")
expect(base64.b64decode(_data_url.split(",", 1)[1]) == b"\xff\xd8\xff", "data URL 里的字节原样")
expect(mj.image_data_url(b"", "image/png").startswith("data:image/png;base64,"), "图片类型可换")

# 把取图那一步换成桩，绝不发请求
_orig_fetch_captcha = mj.fetch_captcha
try:
    mj.fetch_captcha = lambda code, timeout=0: b"\xff\xd8JPEG"  # noqa: ARG005 - 桩，不发请求
    _cap = mj.captcha_image("1793280939807")
finally:
    mj.fetch_captcha = _orig_fetch_captcha
expect(_cap["yzm_code"] == "1793280939807", f"captcha_image 带回时间戳 ({_cap['yzm_code']})")
expect(_cap["image"].startswith("data:image/jpeg;base64,"), "captcha_image 给的是 data URL")
expect(set(_cap) == {"yzm_code", "image"}, f"captcha_image 字段和 MedCaptcha 一致: {sorted(_cap)}")

_sync = mj.sync_result(one)
expect(_sync == {"ok": True, "semester": SEM, "courses": 1, "meetings": 1,
                 "status": "ok", "message": ""},
       f"sync_result 对成功课表给六个字段: {_sync}")
_sync_part = mj.sync_result(no_time)
expect(_sync_part["status"] == "partial",
       f"空课表 → status=partial ({_sync_part})")
expect(_sync_part["message"] and not has_latin(_sync_part["message"]),
       f"未排课的提示是中文: {_sync_part['message']}")
_sync_bad = mj.sync_result(EMPTY_STR)
expect(_sync_bad["ok"] is False and _sync_bad["courses"] == 0, "失败课表 → ok=False、0 门课")
expect(_sync_bad["message"] and not has_latin(_sync_bad["message"]),
       f"失败原因能直接显示: {_sync_bad['message']}")
expect(mj.sync_result(None)["ok"] is False, "sync_result 传非字典也不炸")
expect(mj.sync_result(one)["meetings"] == sum(len(c["meetings"]) for c in one["courses"]),
       "sync_result 的时间段数是数出来的，不是猜的")

# --------------------------------------------------------------------------
# 15. 中文文案总检查
# --------------------------------------------------------------------------
_all_messages: list[str] = []
def messages_of(doc):
    """把一份 doc 里所有面向用户的文案收集起来（供最后的中文检查用）。"""
    out: list[str] = []
    if not isinstance(doc, dict):
        return out
    for key in ("errors", "warnings", "pending"):
        value = doc.get(key)
        if isinstance(value, list):
            out.extend(str(x) for x in value if x)
    return out


# 只覆盖这次真实结构里产出的几份 doc（旧结构的那几个夹具已经不存在了）
for doc in (one, many, mt, dd, fallback, glue, no_time):
    _all_messages.extend(messages_of(doc))
_all_messages.extend([bad_login["error"], expired["error"], no_token["error"], weak["error"],
                      blank_profile["error"], _unknown, mj.error_text(None, ""),
                      long_text, empty_text, _sync_part["message"], _sync_bad["message"]])
expect(all(not has_latin(m) for m in _all_messages),
       f"所有面向用户的中文文案里没有英文技术词（{len(_all_messages)} 条）")

print(f"\n{checks} 条断言")
print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
sys.exit(1 if failures else 0)
