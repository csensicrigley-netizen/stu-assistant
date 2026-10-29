"""Collection: wrap stu-mcp feature services into (code, records) results.

Each collector returns a `CollectResult` whose `code` follows the exit-code
convention inherited from the B machine's ensure_jw.py:

    0  ok
    1  failed            (real problem -> alert the user)
    2  maintenance       (school closed the port -> silent, no alert)
    3  need_input        (session gone: the user has to connect again)
    4  keystore          (local key problem -> needs operator)

The services used here are stu-mcp's own (`features/*/service.py`), so the
records keep stu-mcp's shapes and stable ids (docs/phase0/01-data-contract.md).
"""
from __future__ import annotations

import re
import socket
import threading
import time
from dataclasses import dataclass, field
from urllib.parse import urlparse

CODE_OK = 0
CODE_FAILED = 1
CODE_MAINTENANCE = 2
CODE_NEED_INPUT = 3
CODE_KEYSTORE = 4


@dataclass
class CollectResult:
    source: str
    code: int
    detail: str = ""
    kind_records: dict[str, list[dict]] = field(default_factory=dict)

    @property
    def total(self) -> int:
        return sum(len(v) for v in self.kind_records.values())


def tcp_probe(url: str, port: int | None = None, timeout: float = 6.0) -> tuple[bool, str]:
    """Port check first: the school closes jw at night, and a login attempt then
    only wastes a request and inflates the failure counter (B machine lesson)."""
    parsed = urlparse(url)
    host = parsed.hostname
    if not host:
        return False, "bad_url"
    target_port = port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((host, target_port), timeout=timeout):
            return True, "open"
    except socket.gaierror as exc:
        return False, f"dns_error:{exc}"
    except ConnectionRefusedError:
        return False, "refused"
    except socket.timeout:
        return False, "timeout"
    except OSError as exc:
        return False, f"os_error:{exc}"


_NEED_INPUT = {"needs_login", "login_expired", "session_invalid", "session_changed", "needs_elc_login"}
_MAINTENANCE = {"network_error", "request_timeout", "request_deadline", "source_unavailable", "rate_limited"}


def _app_error_code(exc: Exception) -> int:
    code = getattr(exc, "code", "")
    if code in _NEED_INPUT:
        return CODE_NEED_INPUT
    if code == "secure_storage_unavailable":
        return CODE_KEYSTORE
    if code in _MAINTENANCE:
        return CODE_MAINTENANCE
    return CODE_FAILED


def _why(exc: Exception) -> str:
    """A short, safe description: stu-mcp's error code, never a traceback."""
    return str(getattr(exc, "code", "") or type(exc).__name__)[:80]


# --------------------------------------------------------------------------
# notices: 学校 OA 通知（经 WebVPN 会话，每天 10 条）
#
# 改源（2026-10-29）：原来读的是公开栏目 + OA 匿名直连，学校侧关掉 80 端口
# 之后就空窗。现在以 OA 为主：读租户自己的 WebVPN 会话，会话存在租户 vault
# 里（进程重启不丢），真过期由 sync 里用已存密码静默重登；只有学校要令牌
# 时才回到「等本人输数盾动态码」。
# 公开栏目只当兜底：OA 完全读不到时界面别空着。
# --------------------------------------------------------------------------

OA_DAILY_LIMIT = 10


def collect_notices(vault, *, limit: int = OA_DAILY_LIMIT, include_oa: bool = True) -> CollectResult:
    from stu_mcp.features.notices.adapter import NoticeAdapter

    try:
        cap = max(1, min(int(limit), OA_DAILY_LIMIT))
    except (TypeError, ValueError):
        cap = OA_DAILY_LIMIT
    adapter = NoticeAdapter()

    oa_detail = ""
    if include_oa:
        if vault is None:
            # 没有租户会话就别去直连 OA：拿到的是统一认证登录页，不是通知
            return CollectResult("public", CODE_NEED_INPUT, "OA 需要先连接 WebVPN")
        # 学校 OA 的目录路由是 http / 8118：不开兼容开关就只会拿到
        # oa_http_compat_required，通知永远是空的（会话本身没问题）
        try:
            runtime = vault.runtime
            if not runtime.preferences().get("oa_http_compat"):
                runtime.save_preferences({"oa_http_compat": True})
        except Exception:  # noqa: BLE001
            pass
        try:
            # protected_oa 返回的是 Access（记录在 .value 里），不是 list。
            # 直接把它当 list 用会让 len() 抛 TypeError，被下面 except 吞掉，
            # 表现就是「OA 一条都读不到」——这个坑踩过一次，别再踩第二次。
            accessed = adapter.protected_oa(vault, cap)
            records = list((accessed.value if accessed is not None else None) or [])
        except Exception as exc:  # noqa: BLE001 - OA 坏了不该让整个 public 源挂掉
            code = _app_error_code(exc)
            if code == CODE_NEED_INPUT:
                return CollectResult("public", CODE_NEED_INPUT, _why(exc))
            oa_detail = f"OA 未读到：{_why(exc)}"
        else:
            if records:
                return CollectResult("public", CODE_OK, f"OA {len(records)} 条",
                                     {"notice": records[:cap]})
            oa_detail = "OA 今天没有新通知"

    return _notices_public_fallback(adapter, cap, oa_detail)


def _notices_public_fallback(adapter, cap: int, prefix: str) -> CollectResult:
    """公开栏目兜底：只在 OA 读不到时用，条数同样不超 cap。"""
    from stu_mcp.sources import PUBLIC_LISTINGS

    ok, why = tcp_probe("https://www.stu.edu.cn")
    if not ok:
        return CollectResult("public", CODE_MAINTENANCE, f"学校站点不可达：{why}")

    records: list[dict] = []
    failed = 0
    for category, url in PUBLIC_LISTINGS:
        try:
            with adapter.public_session() as http:
                records.extend(adapter.public_listing(http, url, category, cap))
        except Exception:  # noqa: BLE001 - one bad listing must not kill the rest
            failed += 1
    if failed == len(PUBLIC_LISTINGS):
        return CollectResult("public", CODE_MAINTENANCE, "公开栏目都没有读到")

    detail = f"{len(PUBLIC_LISTINGS) - failed}/{len(PUBLIC_LISTINGS)} 个栏目（OA 兜底）"
    if prefix:
        detail = f"{prefix}；{detail}"
    return CollectResult("public", CODE_OK, detail, {"notice": records[:cap]})


# --------------------------------------------------------------------------
# jw: theory timetable (whole document) + grades + exams
# --------------------------------------------------------------------------

def _patch_jw_timetable(log=print) -> None:
    """Use our fixed copy of stu-mcp's timetable parser.

    stu-mcp 0.7.3 checks each 授课安排 block against the single 大节 row it sits
    in. The school legitimately prints [06-07-08-09节] inside the 第三大节 cell
    (a class that runs across two 大节), so every sync failed with
    ``schema_changed``. `app/jw_timetable.py` is that parser with the check
    widened to the whole page and nothing else changed.
    """
    from .stucompat import install

    install()


def _grade_terms(current: str, depth: int = 8) -> list[str]:
    """The current term plus the previous few, newest first.

    教务的成绩接口是"按学期查询"，当前学期往往一条都没有（还没出分），
    只问一次等于永远读不到历史成绩。深 8 个学期 = 4 个学年，本科全周期都在。
    """
    m = re.fullmatch(r"(\d{4})-(\d{4})-([12])", current or "")
    if not m:
        return [""]
    year, term = int(m.group(1)), int(m.group(3))
    out: list[str] = []
    for _ in range(depth):
        out.append(f"{year}-{year + 1}-{term}")
        if term == 2:
            term = 1
        else:
            year -= 1
            term = 2
    return out


def collect_jw(vault, *, semester: str | None = None) -> CollectResult:
    from stu_mcp.features.jw.adapter import JwAdapter
    from stu_mcp.features.jw.service import academic_timetable

    _patch_jw_timetable()

    ok, why = tcp_probe("https://jw.stu.edu.cn")
    if not ok:
        return CollectResult("jw", CODE_MAINTENANCE, f"教务端口不可达：{why}")

    # 教务一次会话只撑得住很少几次读写，读猛了就把会话踢回登录页。
    # 所以这里按"最要紧的先读、每步隔一下、能少一次就少一次"排：
    #   课表（App 的地基）→ 成绩（按学期找，读到有数据的那一学期就停）→ 考试。
    notes: list[str] = []
    grades: list[dict] = []
    exams: list[dict] = []

    # The timetable is the one thing the App cannot work without; a failure
    # here is the source's failure. "" = the semester the school has selected.
    try:
        timetable = academic_timetable(vault, semester or "")
    except Exception as exc:  # noqa: BLE001
        return CollectResult("jw", _app_error_code(exc), f"课表：{_why(exc)}")
    notes.append(f"学期 {timetable.get('semester', '')}")

    term_now = timetable.get("semester", "") or semester or ""
    # 成绩是"按学期查询"的，而且行里写的是学校自己的学期名（2025-2026-2 的行标成
    # "2026年春季学期"）。stu-mcp 的 service 层拿这个字段和请求的学期字符串比对，
    # 永远不相等 → 全部丢掉。这里直接问 adapter，查到有数据的那一学期就停。
    seen: set[str] = set()
    read_terms = 0
    empty_streak = 0
    try:
        with JwAdapter(vault) as adapter:
            for term in _grade_terms(term_now):
                time.sleep(1.0)
                try:
                    rows = adapter.grades(term)
                except Exception as exc:  # noqa: BLE001
                    notes.append(f"成绩：{_why(exc)}")
                    break
                if not rows:
                    empty_streak += 1
                    # 连空两学期就停：更早的学期一定也没有成绩
                    if empty_streak >= 2:
                        break
                    continue
                empty_streak = 0
                read_terms += 1
                for row in rows:
                    key = str(row.get("id") or "")
                    if key and key in seen:
                        continue
                    seen.add(key)
                    grades.append(row)
            time.sleep(1.0)
            try:
                exams = adapter.exams(term_now)
            except Exception as exc:  # noqa: BLE001 - exams must not take grades down
                notes.append(f"考试：{_why(exc)}")
    except Exception as exc:  # noqa: BLE001 - grades must not take the timetable down
        notes.append(f"成绩：{_why(exc)}")
    if read_terms:
        notes.append(f"成绩 {len(grades)} 条 / {read_terms} 个学期")

    result = CollectResult("jw", CODE_OK, "；".join(notes))
    result.kind_records["timetable"] = [timetable]
    result.kind_records["grade"] = grades
    result.kind_records["exam"] = exams
    return result


# --------------------------------------------------------------------------
# mystu: courses, tasks (homework / quizzes with deadlines), personal events
# --------------------------------------------------------------------------

def collect_mystu(vault, *, limit: int = 30) -> CollectResult:
    ok, why = tcp_probe("https://my.stu.edu.cn")
    if not ok:
        return CollectResult("mystu", CODE_MAINTENANCE, f"MySTU 不可达：{why}")

    # 先走学校自己的 JSON 接口（汕大那台就是这么抓的，稳）；它只在会话失效时报错，
    # 这时再退回 stu-mcp 的解析器当备用。
    from . import mystu_api

    try:
        direct = mystu_api.collect(vault, limit=limit)
    except Exception as exc:  # noqa: BLE001 - 网络抖动也当"直连没成"
        direct = {"ok": False, "reason": type(exc).__name__}

    if direct.get("ok"):
        tasks = direct.get("tasks", [])
        courses = direct.get("courses", [])
        notes = [f"{len(courses)} 门课程，{len(tasks)} 项作业"]
        with_due = sum(1 for t in tasks if t.get("dueMs"))
        if with_due != len(tasks):
            notes.append(f"{len(tasks) - with_due} 项没有截止时间")
        result = CollectResult("mystu", CODE_OK, "；".join(notes))
        result.kind_records["course"] = courses
        result.kind_records["task"] = tasks
        result.kind_records["schedule"] = []
        return result

    if direct.get("reason") in {"login_expired", "no_session"}:
        return CollectResult("mystu", CODE_NEED_INPUT, "MySTU：login_expired")

    try:
        from stu_mcp.features.mystu.service import mystu as mystu_collect

        collection = mystu_collect(vault, limit)
    except ImportError:
        return CollectResult("mystu", CODE_FAILED, f"MySTU 直连失败：{direct.get('reason')}")
    except Exception as exc:  # noqa: BLE001
        return CollectResult("mystu", _app_error_code(exc), _why(exc))

    by_kind: dict[str, list[dict]] = {"course": [], "task": [], "event": []}
    for item in collection.items:
        kind = item.get("kind")
        if kind in by_kind:
            by_kind[kind].append(item)

    notes = [f"{len(by_kind['course'])} 门课程，{len(by_kind['task'])} 项作业"]
    if collection.errors:
        notes.append(f"{len(collection.errors)} 处未读到")
    if collection.limited:
        notes.append("部分活动超出读取上限")

    result = CollectResult("mystu", CODE_OK, "；".join(notes))
    result.kind_records["course"] = by_kind["course"]
    result.kind_records["task"] = by_kind["task"]
    result.kind_records["schedule"] = by_kind["event"]
    return result


# The public listings are the same for every user: read them once per quarter
# hour for the whole site instead of once per user.
#
# 注意：OA 现在走「租户自己的 WebVPN 会话」，所以带 vault 的读取不能共享缓存
# （会把 A 的会话结果喂给 B，也会让"会话过期"这件事在用户之间串味）。
# 只有匿名兜底读取（vault 为 None）才进共享缓存。
_PUBLIC_TTL = 900
_public_cache: tuple[float, CollectResult] | None = None
_public_lock = threading.Lock()


def collect_public_shared(vault=None, **kw) -> CollectResult:
    """学校官网公开栏目（2026-10-29 起只做这个）。

    按爸爸的要求，OA 通知整块下线：不再读 OA、不再需要 WebVPN 会话、
    也不再需要本人授权——官网栏目本来就是公开页面。
    `vault` 参数保留只为兼容旧调用点，已不再参与任何读取。
    """
    global _public_cache

    with _public_lock:  # concurrent users wait for one read, then share it
        if _public_cache and time.time() - _public_cache[0] < _PUBLIC_TTL:
            cached = _public_cache[1]
            return CollectResult(cached.source, cached.code, cached.detail,
                                 {k: list(v) for k, v in cached.kind_records.items()})
        result = collect_notices(None, include_oa=False, **kw)
        if result.code == CODE_OK:
            _public_cache = (time.time(), result)
        return result


COLLECTORS = {
    "public": collect_public_shared,
    "jw": collect_jw,
    "mystu": collect_mystu,
}


def collect(vault, source: str, **kwargs) -> CollectResult:
    fn = COLLECTORS.get(source)
    if fn is None:
        return CollectResult(source, CODE_FAILED, "unknown_source")
    return fn(vault, **kwargs)
