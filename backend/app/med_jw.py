"""汕头大学医学院教务系统（强智「教学服务平台」）适配层。

医学院和主校区不是同一套系统：主校区是正方（`/jsxsd/` + CAS Cookie），
医学院是强智新版（`/dev-api/appapi/` + 请求头 `token`）。协议、登录、字段全不一样，
所以单独一个模块，产出形状**完全对齐** `timetable_import.to_timetable_doc()`
—— 前端 `normalizeTimetable()` 一行都不用改。

接口依据：`docs/医学院教务接口.md`（2026-10-29 实抓 + 前端拆包），
本文件里的路径、字段名、错误文案全部来自那份文档，没有猜测、没有重新抓包。

这个模块只做三件事：

  1. 登录链路：验证码时间戳 → 验证码地址 → 密码加密 → 登录体 → 解析登录结果；
  2. 解析：把接口返回的 dict 拆成 App 用的形状（课表 / 成绩 / 考试 / 学籍）；
  3. 错误翻译：把人话讲清楚，不让英文栈信息漏到界面上。

三条自我约束（改代码时别破坏）：

  * **只读**：`READ_PATHS` 白名单之外一律拦下并被测试覆盖，模块里没有任何
    选课 / 退课 / 评价 / 改密 / 发短信的调用（见文档附录 C）；
  * **不落密码**：模块内没有文件、数据库、日志写入；密码只以密文出现在登录体里，
    `token` 由调用方保管，本模块从不持久化任何东西；
  * **不联网地测**：所有 `parse_*` 与 `error_text` 都是纯函数，`tests/check_med.py`
    用构造数据覆盖，不需要账号、不需要网络。

周次与节次解析直接复用 `timetable_import` 里现成的实现（`_parse_weeks` /
`_parse_periods`），不另写一套口径，否则两套课表会出现"同一门课周次不一样"。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

from . import timetable_import as ti

# --------------------------------------------------------------------------
# 地址与常量
# --------------------------------------------------------------------------

BASE = "https://jwgl.med.stu.edu.cn"
PREFIX = "/dev-api"
API = BASE + PREFIX + "/appapi"

#: 鉴权头名（小写，不是 Authorization、不是 Bearer）
TOKEN_HEADER = "token"

#: 这个模块产出的一切都挂在 `med` 这个来源下，别和主校区的 `jw` 混在一起
SOURCE = "med"

TIMEOUT = 20.0
CAPTCHA_TIMEOUT = 15.0

#: 512 位模长 → PKCS#1 v1.5 明文上限 53 字节，密文恒 64 字节 → Base64 恒 88 字符
RSA_KEY_SIZE = 64
RSA_MAX_PLAIN = RSA_KEY_SIZE - 11
RSA_B64_LENGTH = 88

#: 前端硬编码、全产品同一把的 RSA 公钥（SubjectPublicKeyInfo / DER）
PUBLIC_KEY_B64 = (
    "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAKoR8mX0rGKLqzcWmOzbfj64K8ZIgOdH"
    "nzkXSOVOZbFu/TJhZ7rFAN+eaGkl3C4buccQd/EjEsj9ir7ijT7h96MCAwEAAQ=="
)

#: 课表一格里"第 1-13 节"的上限，与 timetable_import.MAX_PERIOD 同源
MAX_PERIOD = ti.MAX_PERIOD

_CN_DAYS = "一二三四五六日"


class MedError(Exception):
    """带中文说明的错误。

    `str(err)` 可以直接展示给用户 —— 这里**只会**是中文，不会是英文栈信息。
    开发者需要的定位信息放在 `path` 之类的属性上，不混进给用户看的文案。
    """

    def __init__(self, message: str, *, path: str = "", code: str = "") -> None:
        super().__init__(message)
        self.path = path
        # 业务侧的错误码（med_expired / bad_credentials / med_unreachable …）。
        # main.py 靠它决定"要不要把这份 token 清掉、让用户重新连一次"，
        # 原来没有这个属性，那边永远走不到清 token 的分支（审查报告第 15 项）。
        self.code = code


# --------------------------------------------------------------------------
# 小工具
# --------------------------------------------------------------------------

def _text(value: Any) -> str:
    """把任意字段捋成一行干净文本（复用既有口径，别另造一套）。"""
    return ti._norm(value)


def _digest(*parts: str) -> str:
    """稳定指纹：同一份输入永远同一个 id（24 位十六进制，和 demo 的 id 同长）。"""
    raw = "|".join(_text(p) for p in parts)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def _num(value: Any) -> float | None:
    """数字字段容错：数字原样留，字符串能转就转，转不了给 None。"""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return value
    body = _text(value)
    if not body:
        return None
    try:
        return float(body)
    except ValueError:
        return None


def _has_cjk(text: str) -> bool:
    return any("\u4e00" <= ch <= "\u9fff" for ch in text)


# --------------------------------------------------------------------------
# RSA-512 / PKCS#1 v1.5
# --------------------------------------------------------------------------

def _der_read(data: bytes, pos: int) -> tuple[int, bytes, int]:
    """极窄的 DER 读取器：只认本文件这一个公钥的结构。"""
    tag = data[pos]
    pos += 1
    length = data[pos]
    pos += 1
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[pos:pos + count], "big")
        pos += count
    return tag, data[pos:pos + length], pos + length


def _der_public_numbers(der: bytes) -> tuple[int, int]:
    """SubjectPublicKeyInfo → (模数 n, 指数 e)。

    结构固定：SEQUENCE { SEQUENCE(算法) , BIT STRING { SEQUENCE { INTEGER n, INTEGER e } } }。
    自己解一遍是为了让"没有 cryptography 也能加密"这条路可测、可核对。
    """
    tag, body, _ = _der_read(der, 0)
    if tag != 0x30:
        raise MedError("公钥格式不对，无法加密密码")
    _, _, pos = _der_read(body, 0)          # 算法标识，跳过
    tag, bits, _ = _der_read(body, pos)     # BIT STRING
    if tag != 0x03 or not bits or bits[0] != 0:
        raise MedError("公钥格式不对，无法加密密码")
    tag, inner, _ = _der_read(bits, 1)
    if tag != 0x30:
        raise MedError("公钥格式不对，无法加密密码")
    tag_n, raw_n, pos = _der_read(inner, 0)
    tag_e, raw_e, _ = _der_read(inner, pos)
    if tag_n != 0x02 or tag_e != 0x02:
        raise MedError("公钥格式不对，无法加密密码")
    return int.from_bytes(raw_n, "big"), int.from_bytes(raw_e, "big")


_MODULUS, _EXPONENT = _der_public_numbers(base64.b64decode(PUBLIC_KEY_B64))

_PUBLIC_KEY: Any = None


def _load_public_key() -> Any:
    """cryptography 的公钥对象，失败时返回 None（走纯 Python 那条路）。"""
    global _PUBLIC_KEY
    if _PUBLIC_KEY is None:
        try:
            from cryptography.hazmat.primitives import serialization
        except ImportError:
            return None
        _PUBLIC_KEY = serialization.load_der_public_key(base64.b64decode(PUBLIC_KEY_B64))
    return _PUBLIC_KEY


def _nonzero_bytes(count: int) -> bytes:
    """PKCS#1 v1.5 的填充字节必须全部非零。"""
    out = bytearray()
    while len(out) < count:
        for byte in os.urandom(count - len(out) + 8):
            if byte:
                out.append(byte)
                if len(out) == count:
                    break
    return bytes(out)


def _pkcs1_pad(message: bytes, size: int = RSA_KEY_SIZE) -> bytes:
    """0x00 0x02 || 非零随机填充(>=8) || 0x00 || 明文。"""
    if len(message) > size - 11:
        raise MedError("密码太长了，超过了这个系统能加密的长度")
    pad_len = size - 3 - len(message)
    return b"\x00\x02" + _nonzero_bytes(pad_len) + b"\x00" + message


def _rsa_encrypt(
    message: bytes, modulus: int, exponent: int, *, pad=_pkcs1_pad
) -> bytes:
    """纯 Python 的 RSA 加密：一块 PKCS#1 v1.5 填充 + 一次模幂。

    填充块长度跟着模长走（512 位 → 64 字节），所以这套代码对任何模长都成立，
    测试里就用 1024 位密钥把整条路验证一遍。
    """
    size = (modulus.bit_length() + 7) // 8
    block = pad(message, size)
    m = int.from_bytes(block, "big")
    if m >= modulus:
        raise MedError("密码太长，加密块装不下")
    return pow(m, exponent, modulus).to_bytes(size, "big")


def rsa_password(password: str) -> str:
    """明文密码 → RSA-512 / PKCS#1 v1.5 → Base64（固定 88 字符）。

    有 `cryptography` 就用它（requirements.txt 第 10 行已经钉了版本）；
    没装则退回纯 Python：同一套 PKCS#1 v1.5 填充 + `pow()` 模幂，产物同样合法。
    随机填充意味着**同一个密码两次加密结果不同**，这是对的，不要"修"。
    """
    raw = str(password or "").encode("utf-8")
    if not raw:
        raise MedError("密码是空的")
    if len(raw) > RSA_MAX_PLAIN:
        raise MedError("密码太长了，超过了这个系统能加密的长度")
    key = _load_public_key()
    if key is None:
        cipher = _rsa_encrypt(raw, _MODULUS, _EXPONENT)
    else:
        from cryptography.hazmat.primitives.asymmetric import padding

        cipher = key.encrypt(raw, padding.PKCS1v15())
    return base64.b64encode(cipher).decode("ascii")


# --------------------------------------------------------------------------
# 登录链路
# --------------------------------------------------------------------------

def new_yzm_code() -> str:
    """取验证码用的毫秒时间戳。

    文档 §2.3：验证码是 stateless 的，答案按 `yzmcode` 存在服务端，
    所以**取图的时间戳必须原样带进登录体**，而且每次登录都要重新取一张。
    """
    return str(int(time.time() * 1000))


def captcha_url(yzm_code: str) -> str:
    """验证码图片地址（GET，不需要 token，120×40 的 JPEG）。"""
    code = urllib.parse.quote(str(yzm_code), safe="")
    return f"{API}/captcha/getCaptchaCode?yzmcode={code}"


def login_body(username: str, password: str, verify_code: str, yzm_code: str) -> dict:
    """登录请求体。字段表见文档 §2.4，顺序和前端一致。

    `yzmCode` 在前端是数字（`(new Date).getTime()`），所以能转数字就转数字，
    服务端是按它取验证码答案的 key，类型不对会被当成另一张图。
    """
    stamp: Any = str(yzm_code or "").strip()
    if stamp.isdigit():
        stamp = int(stamp)
    return {
        "username": _text(username),
        "password": rsa_password(password),
        "code": "",
        "appid": None,
        "yzmCode": stamp,
        "verifyCode": _text(verify_code),
    }


def _user_of(raw: dict) -> dict:
    """登录响应 / getInfo 里的 `user` 对象 → App 用的几个稳定字段。"""
    row = raw if isinstance(raw, dict) else {}
    return {
        "name": _text(row.get("userxm")),
        "number": _text(row.get("userbh")),
        "code": _text(row.get("userdm")),
        "account": _text(row.get("userAccount")),
        "type": _text(row.get("usertype")),
        "phone": _text(row.get("userdh")),
        "college": _text(row.get("userdwmc")),
        "college_en": _text(row.get("userdwywmc")),
    }


def parse_login(payload: dict) -> dict:
    """登录响应 → {"ok", "token", "user", "error", "weak_pass"}。

    三种结果都要能认：
      * 成功：`code=200` 且 `user.token` 有值；
      * 失败：`{"msg":"app_retrun_error_applogin","code":500}` → 中文人话；
      * 未登录：`{"code":401,"msg":"请先登录！"}` → 提示重新登录。
    密码是弱口令/初始密码时服务端仍然不给可用会话（文档 §2.4），也按失败处理。
    """
    out = {"ok": False, "token": "", "user": _user_of({}), "error": "", "weak_pass": False}
    if not isinstance(payload, dict):
        out["error"] = "教务返回的内容看不懂，稍后再试一次"
        return out

    code = payload.get("code")
    raw_user = payload.get("user") if isinstance(payload.get("user"), dict) else {}
    out["user"] = _user_of(raw_user)
    token = _text(raw_user.get("token"))

    weak = _num(payload.get("weakPass")) == 1 or _num(payload.get("initPw")) == 1
    out["weak_pass"] = bool(weak)
    if weak:
        out["error"] = "教务要求先改密码才能继续，请到教务系统改完密码再回来登录"
        return out

    if token:
        out["ok"] = True
        out["token"] = token
        return out

    out["error"] = error_text(code, payload.get("msg"))
    if not out["error"]:
        out["error"] = "登录没有拿到凭证，稍后再试一次"
    return out


# --------------------------------------------------------------------------
# 错误翻译
# --------------------------------------------------------------------------

#: 文档 §2.4 给的 i18n key → 中文（服务端有时回 key 有时回中文，两张都要认）
_I18N = {
    "app_retrun_error_applogin": "您的帐号或身份证号或密码不正确",
    "app_retrun_error_applogina": "您的帐号初始密码已过期，请联系教务管理员设置有效期或重新初始化密码",
    "app_retrun_error_apploginb": "您的帐号已过期，请联系教务管理员",
    "app_retrun_error_apploginc": "您输入的身份证号或学生姓名不正确",
    "app_retrun_error_apploginmndl": "您的帐号或身份证号不正确",
    "app_retrun_error_apploginmndlfh": "您的帐号或身份证号不正确",
}


def _code_number(code: Any) -> int | None:
    if isinstance(code, bool) or code is None:
        return None
    if isinstance(code, int):
        return code
    if isinstance(code, float):
        return int(code) if code.is_integer() else None
    body = _text(code)
    return int(body) if body.lstrip("-").isdigit() else None


def error_text(code: Any, msg: Any) -> str:
    """教务的错误码 + 错误文案 → 一句能照着做的中文。

    规矩：只出中文，不把 `app_retrun_error_*` 这种 i18n key、也不把英文栈信息
    漏给用户；未知错误退回"错误码 N + 稍后再试"。
    """
    text = _text(msg)
    num = _code_number(code)

    mapped = _I18N.get(text)
    if mapped:
        return mapped

    if num == 200:
        return ""

    if num in (401, 501) or "请先登录" in text or ("登录" in text and "过期" in text):
        return "教务登录已过期，请重新登录"

    if "验证码" in text:
        if "过期" in text:
            return "验证码已过期，点一下图片换一张，然后马上登录"
        return "验证码不正确，点一下图片换一张再试一次"

    if text and _has_cjk(text):
        return text

    if num is None:
        return "连不上医学院教务系统，检查一下网络再试一次"

    return f"医学院教务系统这次没办成（错误码 {num}），稍后再试一次"


# --------------------------------------------------------------------------
# 课表：节次 / 周次
# --------------------------------------------------------------------------

def _periods_from(text: Any) -> tuple[list[int], str]:
    """节次字段 → 整数列表。

    三种编码都要认（文档 §3.1 + §10 第 5 条）：
      `"3,4,5"` → [3,4,5]；`"1-2"` → [1,2]；强智的 `"0102"` → [1,2]。
    """
    body = _text(text)
    if not body:
        return [], ""
    if re.fullmatch(r"(?:\d{2}){2,}", body):
        body = ",".join(str(int(body[i:i + 2])) for i in range(0, len(body), 2))
    periods, err = ti._parse_periods(body)
    return periods, err or ""


def _weeks_from(item: dict) -> tuple[list[int], str]:
    """周次字段 → 整数列表，直接复用 `timetable_import._parse_weeks`。

    `zc` 在不同部署里可能是"1-16周"这种描述串，也可能是"第几周"的数字，
    两种都喂给同一个解析器。`zcmc` 是周次名称，有就以它为准（信息更全）。
    """
    for key in ("zcmc", "zc", "week", "zcs"):
        raw = item.get(key)
        if raw is None or raw == "":
            continue
        weeks, err = ti._parse_weeks(str(raw))
        if weeks:
            return weeks, ""
        if err:
            return [], err
    return [], ""


def _weekday_of(item: dict) -> int | None:
    """扁平数组里的星期（7 个分组那种数组用索引，不看这个字段）。"""
    for key in ("xq", "weekday"):
        raw = item.get(key)
        if raw is None or raw == "":
            continue
        try:
            day = int(str(raw).strip())
        except (TypeError, ValueError):
            continue
        if 1 <= day <= 7:
            return day
    return None


def _location_of(item: dict) -> str:
    """教室名称。没有 `jxcdmc` 时按 建筑 / 楼层 / 校区 拼一个（文档 §3.1）。"""
    place = _text(item.get("jxcdmc"))
    if place:
        return place
    parts: list[str] = []
    building = _text(item.get("jzwmc"))
    floor = _text(item.get("lch"))
    campus = _text(item.get("szxqmc"))
    if building:
        parts.append(building)
    if floor:
        parts.append(f"{floor}层")
    if campus and campus not in parts:
        parts.append(campus)
    return " / ".join(parts)


def _raw_time_of(item: dict) -> str:
    """给人看的时间原文，例如「第3-4节 10:00-11:40」。"""
    period = _text(item.get("jcdm")) or _text(item.get("jcdm2"))
    start = _text(item.get("qssj"))
    end = _text(item.get("jssj"))
    when = f"{start}-{end}" if start and end else start or end
    label = f"第{period}节" if period else ""
    return " ".join(p for p in (label, when) if p)


def _online_of(item: dict) -> str:
    """直播/线上课链接（`meetingData.shareUrl`，文档 §3.1）。"""
    data = item.get("meetingData")
    if isinstance(data, dict):
        return _text(data.get("shareUrl"))
    return ""


def _notes_of(item: dict) -> str:
    """停课标记 `tkbz == "2"`（前端会加个星号，我们直接说明白）。"""
    return "停课" if _text(item.get("tkbz")) == "2" else ""


# --------------------------------------------------------------------------
# 课表：kbList 的三种形状
# --------------------------------------------------------------------------

EMPTY_TEXT = "这个学期还没有课表"


def _decode_kb(raw: Any) -> tuple[Any, str, bool]:
    """`kbList` 解包 → (数据, 说明, 是否只是"空")。

    文档 §3.1：它是**字符串**，要 `JSON.parse` 一次。多解一层是防御：
    有的部署会把已经序列化过的内容再包一层，那种情况单次解析会得到字符串，
    这里循环几层直到拿到数组。
    """
    if raw is None or raw == "":
        return None, EMPTY_TEXT, True
    if isinstance(raw, list):
        return raw, "", False
    if isinstance(raw, dict):
        inner = raw.get("kbList")
        if inner is None:
            return None, "课表数据的格式看不懂，稍后再同步一次", False
        return _decode_kb(inner)
    if isinstance(raw, str):
        body = raw.strip()
        if not body:
            return None, EMPTY_TEXT, True
        for _ in range(3):
            try:
                data = json.loads(body)
            except (json.JSONDecodeError, ValueError):
                return None, "课表内容读不出来，可能是教务改版了，稍后再同步一次或者手动录入", False
            if not isinstance(data, str):
                return data, "", False
            body = data.strip()
        return None, "课表内容读不出来，可能是教务改版了，稍后再同步一次或者手动录入", False
    return None, "课表数据的格式看不懂，稍后再同步一次", False


def _walk_entries(data: Any) -> tuple[list[tuple[int | None, Any]], int]:
    """→ ([(星期提示, 课表项)], 被跳过的条数)。

    认三种：
      * 长度 7 的数组 → 索引 0..6 就是周一..周日（文档 §3.1，最权威）；
      * 扁平数组（`Studentkb/data` 那种）→ 每项自己带 `xq`；
      * 其他嵌套 → 尽力而为，认不出来的记为"跳过"。
    """
    out: list[tuple[int | None, Any]] = []
    skipped = 0
    if isinstance(data, list):
        grouped = len(data) == 7 and all(isinstance(day, list) for day in data)
        for pos, group in enumerate(data):
            if isinstance(group, list):
                hint = pos + 1 if grouped else None
                for item in group:
                    out.append((hint, item))
            elif isinstance(group, dict):
                out.append((None, group))
            else:
                skipped += 1
    elif isinstance(data, dict):
        out.append((None, data))
    else:
        skipped += 1
    return out, skipped


# --------------------------------------------------------------------------
# 课表：主解析
# --------------------------------------------------------------------------

def _course_shell(cid: str, title: str, code: str, class_name: str, teacher: str) -> dict:
    return {
        "id": cid,
        "title": title,
        "course_code": code,
        "class_name": class_name,
        "teachers": [teacher] if teacher else [],
        "teacher": teacher,
        "meetings": [],
        "hue": ti._hue(cid),
    }


def parse_timetable(payload: dict, *, semester: str) -> dict:
    """接口响应 → TimetableDoc。真实结构见 med_parse.py 的模块注释。"""
    from .med_parse import parse_timetable as _real

    return _real(payload, semester=semester)

def _meta_of(payload: dict, entries: int) -> dict:
    """课表的边角信息：学期代码、当前第几周、第一周周一。

    App 的"第 N 周"和"今日课表"要用 `zc`（服务端已经算好），
    但 TimetableDoc 的形状里没有它的位置，所以单独挂在 `meta` 下；
    `main.py` 只挑固定几个键，`_flatten_timetable()` 也只挑固定几个键，
    多一个 `meta` 不会影响任何既有链路。
    """
    meta: dict[str, Any] = {"entries": entries}
    code = _text(payload.get("xnxqdm"))
    if code:
        meta["semester_code"] = code
    week = _code_number(payload.get("zc"))
    if week is not None:
        meta["current_week"] = week
    first_day = _text(payload.get("firstDayOfWeek"))
    if first_day:
        meta["first_day_of_week"] = first_day
    max_week = _code_number(payload.get("maxzc"))
    if max_week is not None:
        meta["max_week"] = max_week
    today = _text(payload.get("rq"))
    if today:
        meta["today"] = today
    return meta


# --------------------------------------------------------------------------
# 成绩
# --------------------------------------------------------------------------

def parse_grades(payload: dict) -> list[dict]:
    """`Studentcj/data` → 成绩条目列表（字段对齐 `demo.demo_grades()`）。

    `kccjList` 里一项 = 一门课的成绩；空列表、缺字段、非 dict 都只是少几行，
    不会抛异常。`cjdm` 是成绩主键，留给调用方去查成绩构成明细。
    """
    rows = payload.get("kccjList") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        return []

    semester_code = _text(payload.get("xnxqdm"))
    semester_name = _text(payload.get("xnxqmc"))
    decimals = _code_number(payload.get("zcjblxsdws"))

    out: list[dict] = []
    for item in rows:
        if not isinstance(item, dict):
            continue
        title = _text(item.get("kcmc")) or _text(item.get("kcywmc"))
        if not title:
            continue
        code = _text(item.get("kcbh"))
        semester = _text(item.get("xnxqmc")) or semester_name or semester_code
        detail_id = _text(item.get("cjdm"))
        score = _text(item.get("zcj"))
        mode = _text(item.get("cjfsdm"))
        item_id = "med:grade:" + _digest(semester, code, title, detail_id)
        out.append({
            "id": item_id,
            "external_id": item_id,
            "kind": "grade",
            "title": title,
            "semester": semester,
            "semester_code": _text(item.get("xnxqdm")) or semester_code,
            "course_code": code,
            "score": score,
            "credits": _num(item.get("xf")),
            "gpa": None,
            "exam_type": "",
            "score_mode": "百分制" if mode == "01" else _text(item.get("cjfsdm")),
            "score_decimals": decimals,
            "course_type": _text(item.get("kcdlmc")),
            "detail_id": detail_id,
            "failed": _is_failed(score, mode, decimals),
        })
    return out


def _is_failed(score: str, mode: str, decimals: int | None) -> bool:
    """百分制低于 60 记不及格；其他计分方式不猜（文档只说 `cjfsdm=="01"` 是百分制）。"""
    if mode != "01":
        return False
    try:
        value = float(score)
    except (TypeError, ValueError):
        return False
    return value < 60


# --------------------------------------------------------------------------
# 考试
# --------------------------------------------------------------------------

def parse_exams(payload: dict) -> list[dict]:
    """`xsksxx/datas` → 考试安排列表（字段对齐 `demo.demo_exams()`）。

    `exam_time` 按文档 §5.2 拼成「日期 时间」，前端直接显示。
    `jcdm2` 在考试里是给人看的节次文字（"第3节"），不当数组用，放 `periods_text`。
    """
    rows = payload.get("ksapList") if isinstance(payload, dict) else None
    if not isinstance(rows, list):
        return []

    semester_code = _text(payload.get("xnxqdm"))
    semester_name = _text(payload.get("xnxqmc"))

    out: list[dict] = []
    for item in rows:
        if not isinstance(item, dict):
            continue
        title = _text(item.get("kcmc")) or _text(item.get("kcywmc"))
        if not title:
            continue
        code = _text(item.get("kcbh"))
        date = _text(item.get("ksrq"))
        clock = _text(item.get("kssj"))
        semester = _text(item.get("xnxqdm")) or semester_code or semester_name
        item_id = "med:exam:" + _digest(semester, code, title, date, clock)
        out.append({
            "id": item_id,
            "external_id": item_id,
            "kind": "exam",
            "title": title,
            "semester": semester,
            "semester_name": semester_name,
            "course_code": code,
            "exam_time": " ".join(p for p in (date, clock) if p),
            "exam_date": date,
            "exam_clock": clock,
            "location": _text(item.get("kscdmc")),
            "seat_number": _text(item.get("zwh")),
            "campus": _text(item.get("xqmc")),
            "week": _code_number(item.get("zc")),
            "weekday": _code_number(item.get("xq")),
            "periods_text": _text(item.get("jcdm2")),
            "form": _text(item.get("khxsmc")),
            "open_book": _text(item.get("ksxs")) == "1",
            "batch": _text(item.get("ksaplxmc")),
            "exam_id": _text(item.get("ksrcdm")) or _text(item.get("sjdm")),
        })
    return out


# --------------------------------------------------------------------------
# 学籍 / 个人信息
# --------------------------------------------------------------------------

_PROFILE_KEYS = (
    "name", "number", "account", "college", "major", "major_direction",
    "grade", "class_name", "campus", "enroll_year", "study_years", "gender",
    "nation", "status", "student_status", "phone", "email", "dorm", "school_key",
)


def _profile_skeleton() -> dict:
    body: dict[str, Any] = {"ok": False, "source": "", "error": ""}
    for key in _PROFILE_KEYS:
        body[key] = ""
    return body


def parse_profile(payload: dict) -> dict:
    """学籍卡（`Studentxjkp/index`）或账号信息（`getInfo`）→ 一份能用的学生档案。

    两个接口都能喂：有 `xjxx` 就按学籍卡读（院系/专业/年级在这里），
    只有 `user` 就按账号信息读（只有姓名学号单位）。

    身份证号（`sfzh`）**故意不搬**：App 用不到，少存一份少一份麻烦。
    """
    out = _profile_skeleton()
    if not isinstance(payload, dict):
        out["error"] = "教务返回的内容看不懂，稍后再试一次"
        return out

    code = payload.get("code")
    num = _code_number(code)
    if num not in (None, 200):
        out["error"] = error_text(code, payload.get("msg"))
        return out

    info = payload.get("xjxx") if isinstance(payload.get("xjxx"), dict) else {}
    user = payload.get("user") if isinstance(payload.get("user"), dict) else {}

    if info:
        out.update({
            "ok": True,
            "source": "xjkp",
            "name": _text(info.get("xsxm")),
            "number": _text(info.get("xsbh")),
            "college": _text(info.get("yxmc")),
            "major": _text(info.get("zymc")),
            "major_direction": _text(info.get("zyfxmc")) or _text(info.get("fxzymc")),
            "grade": _text(info.get("dqszj")) or _text(info.get("rxnf")),
            "class_name": _text(info.get("bjmc")),
            "campus": _text(info.get("xqmc")),
            "enroll_year": _text(info.get("rxnf")),
            "study_years": _text(info.get("xz")),
            "gender": _text(info.get("xbmc")),
            "nation": _text(info.get("mzmc")),
            "status": _text(info.get("xjztmc")),
            "student_status": _text(info.get("xsztmc")),
            "phone": _text(info.get("dh")),
            "email": _text(info.get("email")),
            "dorm": _text(info.get("dorm")),
        })
        return out

    if user:
        out.update({
            "ok": True,
            "source": "info",
            "name": _text(user.get("userxm")),
            "number": _text(user.get("userbh")),
            "account": _text(user.get("userAccount")),
            "college": _text(user.get("userdwmc")),
            "phone": _text(user.get("userdh")),
        })
        return out

    out["error"] = "教务没有返回学籍信息，稍后再试一次"
    return out


# --------------------------------------------------------------------------
# 请求体（纯字典，方便离线测）
# --------------------------------------------------------------------------

def timetable_body(zc: Any = "all", jc: str = "", teadm: str = "") -> dict:
    """`Studentkb/index` 的请求体。学生端 `teadm` 恒为空串。"""
    week: Any = _text(zc) or "all"
    if str(week).isdigit():
        week = int(str(week))
    return {"zc": week, "jc": _text(jc), "teadm": _text(teadm)}


def grades_body(semester_code: str = "") -> dict:
    """`Studentcj/data` 的请求体；空字符串表示用服务端默认学期。"""
    return {"xnxqdm": _text(semester_code)}


def exams_body(semester_code: str = "", kind: str = "") -> dict:
    """`xsksxx/datas` 的请求体；`kind` 是安排类型（"" = 全部）。"""
    return {"xnxqdm": _text(semester_code), "ksaplxdm": _text(kind)}


def profile_body() -> dict:
    """`Studentxjkp/index` 的请求体：学生看自己时两个参数都是空串。"""
    return {"yhtype": "", "xsdm": ""}


# --------------------------------------------------------------------------
# 只读白名单（写操作一律拦在发请求之前）
# --------------------------------------------------------------------------

#: 允许发出去的路径。**只放读接口**，别往里加任何写接口 —— 文档附录 C 点名了
#: `Studentxk/addxk`、`Studentxk/cancel`、`UpPasswd`、`getsmsCode`、`StudentExam/*`
#: 这些，模块里连调用痕迹都不该有。
READ_PATHS = (
    "/appapi/getIstoken",
    "/appapi/getInfo",
    "/appapi/applogin",
    "/appapi/captcha/getCaptchaCode",
    "/appapi/getimage",
    "/appapi/getmainimage",
    "/appapi/Studentkb/index",
    "/appapi/Studentkb/data",
    "/appapi/Studentkb/jxrl",
    "/appapi/Studentcj/index",
    "/appapi/Studentcj/data",
    "/appapi/Studentcj/newDetail",
    "/appapi/xsksxx/index",
    "/appapi/xsksxx/datas",
    "/appapi/Studentxjkp/index",
    "/appapi/appxl/index",
    "/appapi/appxl/data",
    "/appapi/ssologin",
    "/appapi/getCasData",
)


def is_read_path(path: str) -> bool:
    """这个路径在只读白名单里吗（查询串不影响判断）。"""
    body = _text(path).split("?", 1)[0]
    return any(body == allowed for allowed in READ_PATHS)


def url_of(path: str) -> str:
    """`/appapi/...` → 完整地址；已经是完整地址就原样返回。"""
    body = _text(path)
    if body.startswith("http://") or body.startswith("https://"):
        return body
    if body.startswith("/appapi/"):
        return API + body[len("/appapi"):]
    if body.startswith(PREFIX + "/"):
        return BASE + body
    return API + "/" + body.lstrip("/")


# --------------------------------------------------------------------------
# 薄 HTTP 客户端（只在调用方显式要数据时才会发请求）
# --------------------------------------------------------------------------

def _raw_request(
    method: str, path: str, *, token: str = "", body: dict | None = None,
    timeout: float = TIMEOUT,
) -> tuple[int, bytes]:
    """真正发请求的地方。**先过只读白名单，再建连接。**

    网络层错误一律翻成中文 `MedError`，不让 `URLError` / 堆栈漏出去。
    """
    if not is_read_path(path):
        err = MedError("这个接口不在只读名单里，已经拦下，没有任何请求发出去", path=path)
        raise err

    data = None
    headers = {"Accept": "application/json", "User-Agent": "stu-app-med/1"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json;charset=utf-8"
    if token:
        headers[TOKEN_HEADER] = _text(token)

    request = urllib.request.Request(
        url_of(path), data=data, headers=headers, method=method.upper()
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:  # noqa: S310 - 固定本校主机
            return int(resp.status), resp.read()
    except urllib.error.HTTPError as exc:
        return int(exc.code), exc.read() or b""
    except urllib.error.URLError as exc:
        reason = _text(getattr(exc, "reason", ""))
        if "timed out" in reason.lower() or "timeout" in reason.lower():
            raise MedError("医学院教务系统响应太慢，稍后再试一次") from None
        raise MedError("连不上医学院教务系统，检查一下网络再试一次") from None
    except (TimeoutError, OSError):
        raise MedError("连不上医学院教务系统，检查一下网络再试一次") from None


def get_api(path: str, *, token: str = "", timeout: float = TIMEOUT) -> dict:
    """GET 一个只读接口 → 响应 dict；失败时返回 `{"code": -1, "msg": 中文}`。"""
    status, raw = _raw_request("GET", path, token=token, timeout=timeout)
    return _json_of(status, raw)


def post_api(
    path: str, *, token: str = "", body: dict | None = None, timeout: float = TIMEOUT
) -> dict:
    """POST 一个只读接口 → 响应 dict；失败时返回 `{"code": -1, "msg": 中文}`。"""
    status, raw = _raw_request("POST", path, token=token, body=body, timeout=timeout)
    return _json_of(status, raw)


def get_bytes(path: str, *, token: str = "", timeout: float = CAPTCHA_TIMEOUT) -> bytes:
    """取二进制（验证码图片）。HTTP 状态不对就抛中文 `MedError`。"""
    status, raw = _raw_request("GET", path, token=token, timeout=timeout)
    if status >= 400:
        raise MedError(f"教务这次没给图片（错误码 {status}），稍后再试一次")
    return raw


def _json_of(status: int, raw: bytes) -> dict:
    """把响应体解成 dict；解不出来就包成 `code=-1`，让上层统一走 `error_text`。"""
    # 认证错误可能没有 JSON 正文，HTTP 状态优先于响应体的业务 code。
    if status == 401:
        return {"code": 401, "msg": "医学院的登录过期了，需要重新连一次。"}
    if status >= 400:
        return {"code": -1, "http_status": status, "msg": f"医学院教务请求失败（HTTP {status}），稍后再试。"}
    text = raw.decode("utf-8", "replace").strip()
    if not text:
        return {"code": -1, "msg": "教务这次没有返回内容，稍后再试一次"}
    try:
        data = json.loads(text)
    except (json.JSONDecodeError, ValueError):
        return {"code": -1, "msg": "教务返回的内容看不懂，可能是页面改了，稍后再试一次"}
    if not isinstance(data, dict):
        return {"code": -1, "msg": "教务返回的内容看不懂，稍后再试一次"}
    return data


# ---- 一步到位的取数（调用方挂路由时直接调这几个） -------------------------

def fetch_captcha(yzm_code: str, *, timeout: float = CAPTCHA_TIMEOUT) -> bytes:
    """拿验证码图片字节（JPEG，120×40）。时间戳要与登录体一致。"""
    return get_bytes(
        f"/appapi/captcha/getCaptchaCode?yzmcode={urllib.parse.quote(str(yzm_code), safe='')}",
        timeout=timeout,
    )


def image_data_url(raw: bytes, media: str = "image/jpeg") -> str:
    """图片字节 → `data:` URL，前端 `<img src>` 直接能吃（纯函数，可离线测）。"""
    return f"data:{media or 'image/jpeg'};base64," + base64.b64encode(raw or b"").decode("ascii")


def captcha_image(yzm_code: str, *, timeout: float = CAPTCHA_TIMEOUT) -> dict:
    """取一张新验证码，顺带回传它的时间戳（前端要原样带回登录体）。

    → `{"yzm_code": "<毫秒时间戳>", "image": "data:image/jpeg;base64,..."}`
    对应 `web/src/lib/med-api.ts` 的 `MedCaptcha`。
    """
    code = str(yzm_code or "").strip() or new_yzm_code()
    return {"yzm_code": code, "image": image_data_url(fetch_captcha(code, timeout=timeout))}


def sync_result(doc: dict) -> dict:
    """TimetableDoc → 前端 `/v1/med/sync` 要的六个字段（`MedSyncResult`）。

    纯函数：路由拿到解析结果后直接返回这个，不用自己数课程和时间段。
    """
    body = doc if isinstance(doc, dict) else {}
    courses = body.get("courses") if isinstance(body.get("courses"), list) else []
    meetings = [m for c in courses if isinstance(c, dict) for m in (c.get("meetings") or [])]
    errors = [m for m in (body.get("errors") or []) if m]
    warnings = [m for m in (body.get("warnings") or []) if m]
    return {
        # TimetableDoc 里没有 ok 字段（它的形状由 timetable_import.to_timetable_doc
        # 决定），所以 ok 的意思是"这次真的拿到了课"，不是读一个不存在的键。
        "ok": bool(courses),
        "semester": _text(body.get("semester")),
        "courses": len(courses),
        "meetings": len(meetings),
        # 一门课都没有的时候不能报 ok：那会让界面以为"已经完整同步了"
        "status": "ok" if (body.get("status") == "ok" and courses) else "partial",
        "message": "；".join(errors or warnings) if courses
        else "这次没有拿到任何课程，可能是新学期课表还没发布。",
    }


def login(
    username: str, password: str, verify_code: str, yzm_code: str, *,
    timeout: float = TIMEOUT,
) -> dict:
    """取图 → 提交 → 解析，一趟走完。`token` 由调用方自己保管。"""
    payload = post_api(
        "/appapi/applogin",
        body=login_body(username, password, verify_code, yzm_code),
        timeout=timeout,
    )
    return parse_login(payload)


def fetch_timetable(
    token: str, *, semester: str, zc: Any = "all", jc: str = "", teadm: str = "",
    timeout: float = TIMEOUT,
) -> dict:
    """课表：请求 + 解析，直接得到 TimetableDoc。"""
    payload = post_api(
        "/appapi/Studentkb/index",
        token=token,
        body=timetable_body(zc=zc, jc=jc, teadm=teadm),
        timeout=timeout,
    )
    # 先把"凭证失效 / 业务失败"认出来，再谈解析。
    # 原来直接 parse，401 的响应里没有 kbList，解析出来是空课表，
    # 界面就显示"新学期课表还没发布"——把过期说成没课，用户永远走不到重连。
    code = payload.get("code")
    if code not in (None, 200, "200"):
        if str(code) in {"401", "501"}:
            raise MedError("医学院的登录过期了，需要重新连一次。", path="Studentkb", code="med_expired")
        raise MedError(
            error_text(code, payload.get("msg")) or "医学院这次没有返回课表。",
            path="Studentkb",
            code="med_failed",
        )
    return parse_timetable(payload, semester=semester)


def fetch_grades(token: str, semester_code: str = "", *, timeout: float = TIMEOUT) -> dict:
    """成绩：先拿学期列表，再拿该学期列表（`{"semesters": [...], "grades": [...]}`）。"""
    first = post_api("/appapi/Studentcj/index", token=token, timeout=timeout)
    semesters = first.get("xnxqList") if isinstance(first.get("xnxqList"), list) else []
    code = _text(semester_code) or _text(first.get("xnxqdm"))
    payload = post_api(
        "/appapi/Studentcj/data", token=token, body=grades_body(code), timeout=timeout
    )
    merged = dict(payload)
    merged.setdefault("xnxqdm", code)
    merged.setdefault("xnxqmc", _text(first.get("xnxqmc")))
    return {
        "semesters": [s for s in semesters if isinstance(s, dict)],
        "semester_code": code,
        "grades": parse_grades(merged),
        "error": error_text(payload.get("code"), payload.get("msg")) if payload.get("code") not in (None, 200) else "",
    }


def fetch_exams(token: str, semester_code: str = "", *, timeout: float = TIMEOUT) -> dict:
    """考试：先拿学期/类型，再拿安排列表。"""
    first = post_api("/appapi/xsksxx/index", token=token, timeout=timeout)
    code = _text(semester_code) or _text(first.get("xnxqdm"))
    payload = post_api(
        "/appapi/xsksxx/datas", token=token, body=exams_body(code), timeout=timeout
    )
    merged = dict(payload)
    merged.setdefault("xnxqdm", code)
    merged.setdefault("xnxqmc", _text(first.get("xnxqmc")))
    return {
        "semester_code": code,
        "exams": parse_exams(merged),
        "error": error_text(payload.get("code"), payload.get("msg")) if payload.get("code") not in (None, 200) else "",
    }


def fetch_profile(token: str, *, timeout: float = TIMEOUT) -> dict:
    """学籍卡（院系/专业/年级在 `Studentxjkp/index` 里）。"""
    payload = post_api(
        "/appapi/Studentxjkp/index", token=token, body=profile_body(), timeout=timeout
    )
    return parse_profile(payload)


__all__ = [
    "BASE", "API", "PREFIX", "TOKEN_HEADER", "SOURCE",
    "RSA_B64_LENGTH", "PUBLIC_KEY_B64", "READ_PATHS", "MedError",
    "new_yzm_code", "captcha_url", "rsa_password", "login_body", "parse_login",
    "parse_timetable", "parse_grades", "parse_exams", "parse_profile",
    "error_text", "timetable_body", "grades_body", "exams_body", "profile_body",
    "is_read_path", "url_of",
    "get_api", "post_api", "get_bytes",
    "fetch_captcha", "captcha_image", "image_data_url", "sync_result",
    "login", "fetch_timetable", "fetch_grades", "fetch_exams", "fetch_profile",
]

# ---------------------------------------------------------------- 统一认证（CAS）

SERVICE = "https://jwgl.med.stu.edu.cn/app/index.html"
CAS_LOGIN = "https://sso.med.stu.edu.cn/cas/login"


def cas_login(username: str, password: str, *, service: str = SERVICE,
              timeout: float = 25.0) -> dict:
    """走学校统一认证拿票，再用票换 token。

    为什么默认走这条：账号密码是学生天天在浏览器里用的那组（统一认证），
    而 applogin 用的是教务本地口令——两套口令不一定同步，学生常见的情况是
    「浏览器能进、applogin 说密码错」。CAS 登录页是普通表单（username /
    password / execution），没有密码加密；异常时才出图形验证码。

    返回形状与 login() 一致：{"ok", "token", "user", "error"}。
    """
    import http.cookiejar
    import re
    import urllib.parse
    import urllib.request

    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    # 统一认证不带 UA 会直接给个非 200，必须伪装成普通浏览器
    opener.addheaders = [("User-Agent", "Mozilla/5.0"),
                         ("Accept", "text/html,application/xhtml+xml")]
    cas_url = CAS_LOGIN + "?service=" + urllib.parse.quote(service, safe="")

    try:
        html = opener.open(cas_url, timeout=timeout).read().decode("utf-8", "replace")
    except Exception:
        raise MedError("连不上学校统一认证，换个网络再试。", path="cas") from None

    found = re.search(r'name="execution"\s+value="([^"]+)"', html)
    if not found:
        raise MedError("统一认证的登录页变了，暂时连不上，先用手动导入。", path="cas")
    execution = found.group(1)

    form = urllib.parse.urlencode({
        "username": _text(username),
        "password": str(password or ""),
        "execution": execution,
        "_eventId": "submit",
        "geolocation": "",
    }).encode("utf-8")
    req = urllib.request.Request(cas_url, data=form, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        final = opener.open(req, timeout=timeout).geturl()
    except Exception as exc:
        raise MedError("统一认证没有响应，稍后再试。", path="cas") from None

    ticket = re.search(r"ticket=(ST-[^&\s\"]+)", final)
    if not ticket:
        # 表单回来了但没有票：要么口令错，要么这次要验证码
        if "captcha" in final.lower() or "验证码" in final:
            raise MedError("这次统一认证要验证码，用本地口令登录试试。", path="cas")
        raise MedError("统一认证说学号或密码不对，检查一下再试。", path="cas")

    payload = post_api(
        "/appapi/ssologin",
        body={"ticket": ticket.group(1), "service": service, "wxCode": None, "appId": None},
        timeout=timeout,
    )
    return parse_login(payload)

