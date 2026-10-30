"""每日计划：本人自己写的待办。

为什么单独一个模块，而不是塞进 items 表：

  * `items` 里 `kind='task'` 的是 MySTU 推下来的作业——只读、会自己变、同步一次
    整批覆盖。本人写的东西混进去，第一次同步就没了。
  * 这里的校验（标题、日期、时间段、优先级）和统计（今天几项、逾期几项）都是
    **纯函数**，不碰数据库、不读时钟以外的外部状态，测试可以直接调。

时间口径：`date` 一律是 Asia/Shanghai 的本地日期。中国不实行夏令时，所以
`today_local()` 用固定 +08:00 算，不依赖系统 tzdata（服务器上常常没装）。
"""
from __future__ import annotations

import re
import time
import uuid
from datetime import date as _date

# 0 普通 / 1 重要 / 2 紧急
VALID_PRIORITY = {0, 1, 2}

MAX_TITLE = 200
MAX_NOTE = 1000
MAX_SORT = 1_000_000

SHANGHAI_OFFSET = 8 * 3600  # 中国无夏令时，固定 +08:00

_DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TIME_RE = re.compile(r"^(\d{1,2}):(\d{2})$")

# 客户端能写、服务端认的字段；其余一律忽略（前端多带个 updated_at 不该报错）
KNOWN_FIELDS = ("date", "title", "note", "start", "end", "done", "priority", "sort")


def today_local(now: float | None = None) -> str:
    """Asia/Shanghai 的今天，`YYYY-MM-DD`。"""
    ts = time.time() if now is None else now
    return time.strftime("%Y-%m-%d", time.gmtime(ts + SHANGHAI_OFFSET))


def new_plan_id() -> str:
    """`plan_` + 16 位十六进制，够短也够不撞。"""
    return "plan_" + uuid.uuid4().hex[:16]


# ---------------------------------------------------------------- 值清洗


def _text(raw: object, label: str) -> str:
    """None 当空串；非文字直接拒。"""
    if raw is None:
        return ""
    if not isinstance(raw, str):
        raise ValueError(f"{label}必须是文字")
    return raw


def _date_value(raw: object) -> str:
    if not isinstance(raw, str):
        raise ValueError("日期要写成 2026-10-29 这样")
    text = raw.strip()
    if not _DATE_RE.match(text):
        raise ValueError("日期要写成 2026-10-29 这样")
    try:
        _date(int(text[0:4]), int(text[5:7]), int(text[8:10]))
    except ValueError:
        raise ValueError("这个日期不存在") from None
    return text


def _time_value(raw: object, label: str) -> str | None:
    """空串 / None → None；`9:00` 顺手补成 `09:00`；其余必须 24 小时制 HH:MM。"""
    if raw is None:
        return None
    if not isinstance(raw, str):
        raise ValueError(f"{label}要写成 14:00 这样")
    text = raw.strip()
    if not text:
        return None
    match = _TIME_RE.match(text)
    if not match:
        raise ValueError(f"{label}要写成 14:00 这样（24 小时制）")
    hour, minute = int(match.group(1)), int(match.group(2))
    if hour > 23 or minute > 59:
        raise ValueError(f"{label}不是有效时刻")
    return f"{hour:02d}:{minute:02d}"


def _priority_value(raw: object) -> int:
    if isinstance(raw, bool):
        raise ValueError("优先级只能是 0、1、2")
    if isinstance(raw, int):
        value = raw
    elif isinstance(raw, str) and raw.strip().isdigit():
        value = int(raw.strip())
    else:
        raise ValueError("优先级只能是 0、1、2")
    if value not in VALID_PRIORITY:
        raise ValueError("优先级只能是 0、1、2")
    return value


def _bool_value(raw: object) -> bool:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, int) and raw in (0, 1):
        return bool(raw)
    if isinstance(raw, str) and raw.strip().lower() in {"1", "0", "true", "false"}:
        return raw.strip().lower() in {"1", "true"}
    raise ValueError("完成状态只能是 true 或 false")


def _sort_value(raw: object) -> int:
    if isinstance(raw, bool):
        raise ValueError("排序值要写成整数")
    if isinstance(raw, int):
        value = raw
    elif isinstance(raw, str) and raw.strip().lstrip("-").isdigit():
        value = int(raw.strip())
    else:
        raise ValueError("排序值要写成整数")
    if value < 0 or value > MAX_SORT:
        raise ValueError("排序值超出范围")
    return value


# ---------------------------------------------------------------- 主入口


def normalize_fields(fields: dict, *, partial: bool = False) -> dict:
    """校验并清洗输入；拒绝时抛 `ValueError`（中文原因，直接可以给用户看）。

    partial=False（新建）：缺的字段补默认值，`title` / `date` 一定有值。
    partial=True（改）：只处理传进来的字段，没传的一律不动。

    成对规则：`start` / `end` 要么都空、要么都填且 `end > start`。
    partial 时只改其中一头、另一头为空，会顺手把另一头也清掉——不会留下半截时间段。
    只改其中一头但另一头有值，则交给 `DB.plan_update` 用合并后的整行复查。
    """
    if not isinstance(fields, dict):
        raise ValueError("字段格式不对")

    out: dict = {}

    if "title" in fields or not partial:
        title = _text(fields.get("title"), "标题").strip()
        if not title:
            raise ValueError("标题不能为空")
        if len(title) > MAX_TITLE:
            raise ValueError(f"标题最多 {MAX_TITLE} 个字")
        out["title"] = title

    if "date" in fields:
        out["date"] = _date_value(fields.get("date"))
    elif not partial:
        out["date"] = today_local()

    if "note" in fields or not partial:
        note = _text(fields.get("note"), "备注").strip()
        if len(note) > MAX_NOTE:
            raise ValueError(f"备注最多 {MAX_NOTE} 个字")
        out["note"] = note

    start_in, end_in = "start" in fields, "end" in fields
    if start_in or end_in or not partial:
        start = _time_value(fields.get("start"), "开始时间")
        end = _time_value(fields.get("end"), "结束时间")
        if not partial or (start_in and end_in):
            if (start is None) != (end is None):
                raise ValueError("开始和结束时间要么都填，要么都空")
            if start and end and end <= start:
                raise ValueError("结束时间要晚于开始时间")
            out["start"] = start
            out["end"] = end
        elif start_in:
            out["start"] = start
            if start is None:
                out["end"] = None
        else:
            out["end"] = end
            if end is None:
                out["start"] = None

    if "priority" in fields:
        out["priority"] = _priority_value(fields["priority"])
    elif not partial:
        out["priority"] = 0

    if "done" in fields:
        out["done"] = _bool_value(fields["done"])
    elif not partial:
        out["done"] = False

    if "sort" in fields:
        out["sort"] = _sort_value(fields["sort"])
    elif not partial:
        out["sort"] = 0

    return out


def day_summary(plans: list[dict], today: str) -> dict:
    """今天概览 + 逾期 + 往后每一天有几项没做完。

    `upcoming` 只统计**还没勾掉**的：已完成的那条再出现在"接下来"里没有意义。
    日期都是 `YYYY-MM-DD`，字符串比较即时间先后。
    """
    total = done = 0
    overdue = 0
    ahead: dict[str, int] = {}

    for plan in plans or []:
        day = str(plan.get("date") or "")
        if not day:
            continue
        finished = bool(plan.get("done"))
        if day == today:
            total += 1
            if finished:
                done += 1
        elif day < today:
            if not finished:
                overdue += 1
        else:
            if not finished:
                ahead[day] = ahead.get(day, 0) + 1

    return {
        "today_total": total,
        "today_done": done,
        "today_open": total - done,
        "overdue": overdue,
        "upcoming": [{"date": day, "count": ahead[day]} for day in sorted(ahead)],
    }
