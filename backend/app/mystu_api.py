"""MySTU 作业 / 课程：直连学校自己的 JSON 接口，不经过 stu-mcp 的页面解析器。

照抄的是汕大那台（B 机 `/root/mystu_fetch.py`）的路子 —— 它是能跑通的：

  GET https://my.stu.edu.cn/v3/services/api/user/validate?ver=1.1     会话是否活着
  GET https://my.stu.edu.cn/v3/services/api/course/query              课程清单
  GET https://my.stu.edu.cn/v3/services/api/courseactivity/query      每门课的动态
      ?moodleCourseId=<课程 url 里的 ?id=数字>&category=undefined&lang=zh-CN

为什么要绕：stu-mcp 0.7.3 对 MySTU 清单做了很严的结构校验（课程 id 重复就整份作废），
学校现在会把同一门课的不同学期放在同一份清单里，于是登录直接 `schema_changed`，
作业永远读不到。这里的解析只认需要的几个字段，缺字段的条目跳过而不是整份失败。
"""
from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

API = "https://my.stu.edu.cn/v3/services/api"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0.0.0"
TIMEOUT = 30.0


def _cookies(vault) -> dict[str, str]:
    state = vault.load("mystu") or {}
    return {str(c["name"]): str(c["value"]) for c in state.get("cookies", []) if c.get("name")}


def _ms(value) -> int | None:
    """把学校给的截止时间统一成 epoch 毫秒；认不出来就返回 None。"""
    if value in (None, "", 0):
        return None
    if isinstance(value, (int, float)):
        num = float(value)
        return int(num * 1000) if num < 10_000_000_000 else int(num)
    text = str(value).strip()
    if text.isdigit():
        num = int(text)
        return num * 1000 if num < 10_000_000_000 else num
    for pattern in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y/%m/%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S"):
        try:
            return int(datetime.strptime(text[:19], pattern).replace(tzinfo=SCHOOL_TZ).timestamp() * 1000)
        except ValueError:
            continue
    return None


# 学校在汕头。接口和页面上给的日期都是**不带时区的本地时间**，
# 按 UTC 解析会整整差 8 小时（2026-10-29 23:59 会被当成第二天 07:59），
# 于是首页把「今晚截止」显示成「明天」。审查报告第 18 项。
SCHOOL_TZ = timezone(timedelta(hours=8))


_DUE_RE = re.compile(
    r"(?:截止日期|截止时间|到期时间|Due date|Due)[^0-9]{0,40}"
    r"(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})日?[^0-9]{0,20}(\d{1,2}):(\d{2})",
    re.IGNORECASE,
)


def _due_from_page(client, url: str) -> int | None:
    """作业详情页（Moodle）里找"截止日期 2026-01-05 23:59"这类文字。

    课程动态接口的 additionalDueDate 只有老师额外设过才有值，绝大多数是 0，
    真实截止时间只在作业页上。取不到就返回 None，不猜。
    """
    if not url:
        return None
    try:
        res = client.get(url)
        if res.status_code != 200:
            return None
        match = _DUE_RE.search(res.text)
        if not match:
            return None
        year, month, day, hour, minute = (int(part) for part in match.groups())
        return int(datetime(year, month, day, hour, minute, tzinfo=SCHOOL_TZ).timestamp() * 1000)
    except (ValueError, OSError):
        return None


def _moodle_id(url: str) -> str:
    m = re.search(r"[?&]id=(\d+)", url or "")
    return m.group(1) if m else ""


def collect(vault, limit: int = 40) -> dict:
    """返回 {ok, reason, courses, tasks}；会话失效时 reason=login_expired。"""
    import httpx

    cookies = _cookies(vault)
    if not cookies:
        return {"ok": False, "reason": "no_session"}

    headers = {"User-Agent": UA, "Accept": "application/json"}
    with httpx.Client(timeout=TIMEOUT, verify=False, follow_redirects=True, cookies=cookies, headers=headers) as client:
        check = client.get(f"{API}/user/validate", params={"ver": "1.1"})
        if check.status_code != 200:
            return {"ok": False, "reason": "login_expired" if check.status_code in (401, 403) else f"validate_{check.status_code}"}

        listing = client.get(f"{API}/course/query")
        if listing.status_code != 200:
            return {"ok": False, "reason": f"course_{listing.status_code}"}
        try:
            data = listing.json()
        except ValueError:
            return {"ok": False, "reason": "course_not_json"}

        # 学校返回的是"列式表格"：fields 是逗号分隔的列名，items 里每行是一个值数组。
        # 先按列名建索引，再逐行拼成字典；字段缺了就跳过，不整份失败。
        columns = [str(name).strip() for name in str(data.get("fields") or "").split(",") if str(name).strip()]
        rows = data.get("items")
        if not isinstance(rows, list):
            rows = []

        records: list[dict] = []
        for row in rows:
            if not isinstance(row, list):
                continue
            records.append({columns[i]: row[i] for i in range(min(len(columns), len(row)))})

        courses: list[dict] = []
        seen: set[str] = set()
        for raw in records:
            cid = str(raw.get("id") or raw.get("groupId") or raw.get("url") or "").strip()
            if not cid or cid in seen:
                continue
            title = str(raw.get("name") or raw.get("groupName") or "").strip()
            if not title:
                continue
            seen.add(cid)
            courses.append({
                "id": f"course-{cid}",
                "external_id": f"course-{cid}",
                "kind": "course",
                "title": title,
                "teacher": str(raw.get("teacher") or "").strip(),
                "url": str(raw.get("url") or "").strip(),
                "courseCode": raw.get("courseCode"),
                "year": raw.get("attendanceYear"),
                "term": raw.get("attendanceSemester"),
                "source": "mystu",
            })
        # 同一门课在多个学期各一条：只保留最近一个学期参与抓作业
        courses.sort(key=lambda c: (c.get("year") or 0, c.get("term") or 0), reverse=True)

        tasks: list[dict] = []
        for course in courses[:12]:  # 一门课一次请求；12 门足够覆盖当期
            moodle = _moodle_id(course["url"])
            if not moodle:
                continue
            try:
                acts = client.get(
                    f"{API}/courseactivity/query",
                    params={"moodleCourseId": moodle, "category": "undefined", "lang": "zh-CN"},
                )
                if acts.status_code != 200:
                    continue
                payload = acts.json()
            except (ValueError, OSError):
                continue
            rows = payload.get("courseActivities") or []
            if not isinstance(rows, list):
                continue
            for row in rows:
                if not isinstance(row, dict):
                    continue
                title = str(row.get("activityTitle") or "").strip()
                if not title:
                    continue
                due = _ms(row.get("additionalDueDate") or row.get("dueDate") or row.get("due"))
                key = str(row.get("activityId") or row.get("id") or f"{moodle}-{title}")
                tasks.append({
                    "id": f"task-{key}",
                    "external_id": f"task-{key}",
                    "kind": "task",
                    "title": title[:120],
                    "course": course["title"],
                    "category": str(row.get("activityCategory") or "").strip(),
                    "url": str(row.get("activityUrl") or "").strip(),
                    "dueMs": due,
                    "source": "mystu",
                })
                if len(tasks) >= limit:
                    break
            if len(tasks) >= limit:
                break

        # 没有截止时间的，去作业页补一次（最多 12 条，控制请求量）
        missing = [t for t in tasks if not t.get("dueMs") and t.get("url")][:12]
        for task in missing:
            task["dueMs"] = _due_from_page(client, task["url"])

    return {"ok": True, "reason": "", "courses": courses, "tasks": tasks}
