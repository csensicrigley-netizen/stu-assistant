"""每日计划：校验、CRUD、排序、租户隔离、逾期统计。

    cd backend
    $env:STU_APP_DEMO="1"; $env:STU_APP_HOME="$env:TEMP\plan-test"
    .\.venv\Scripts\python.exe tests\check_plan.py

不联网、不碰学校系统。跑的是真实的 FastAPI app + 真实的 SQLite。

关于路由：`main.py` 里挂 `/v1/plan` 是另一个人的活。本脚本先看 app 上有没有；
没有就按交付说明里那 5 个签名**临时**挂一份参考实现（只在这个进程里），
这样现在就能跑通；等 main.py 挂好之后，测的就是 main.py 那份。
两种情况的结论一样：都走真 HTTP、真鉴权、真落库。
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

TMP = tempfile.mkdtemp(prefix="stu-plan-check-")
os.environ["STU_APP_HOME"] = TMP
os.environ["STU_APP_JWT_SECRET"] = "x" * 48
os.environ["STU_APP_DEMO"] = "1"
os.environ.pop("STU_MCP_HOME", None)
os.environ.pop("STU_MCP_KEY_FILE", None)

from fastapi import Depends, HTTPException, Query  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from pydantic import BaseModel, Field  # noqa: E402

from app.config import settings  # noqa: E402
from app.db import DB  # noqa: E402
from app.main import app, current_user  # noqa: E402
from app.plan import day_summary, normalize_fields, today_local  # noqa: E402

failures: list[str] = []


def expect(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


# --------------------------------------------------------------- 路由（参考实现）


class PlanIn(BaseModel):
    date: str | None = None
    title: str | None = None
    note: str | None = None
    start: str | None = None
    end: str | None = None
    done: bool | None = None
    priority: int | None = None
    sort: int | None = None


class PlanPatchIn(BaseModel):
    date: str | None = None
    title: str | None = None
    note: str | None = None
    start: str | None = None
    end: str | None = None
    done: bool | None = None
    priority: int | None = None
    sort: int | None = None


class ToggleIn(BaseModel):
    done: bool


class ReorderIn(BaseModel):
    date: str = Field(min_length=10, max_length=10)
    ids: list[str] = Field(default_factory=list, max_length=500)


def _clean(raw: dict, *, partial: bool) -> dict:
    try:
        return normalize_fields({k: v for k, v in raw.items() if v is not None}, partial=partial)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from None


def _summary(uid: str) -> dict:
    return day_summary(DB(settings.db_path).plan_list(uid), today_local())


def install_reference_routes() -> bool:
    """main.py 还没挂 /v1/plan 时，临时挂一份，返回 True。"""
    from fastapi.routing import APIRoute

    for route in app.router.routes:
        if isinstance(route, APIRoute) and route.path.startswith("/v1/plan"):
            return False

    @app.get("/v1/plan")
    def plan_list(start: str | None = Query(default=None), end: str | None = Query(default=None),
                  include_done: bool = Query(default=True),
                  uid: str = Depends(current_user)) -> dict:
        db = DB(settings.db_path)
        items = db.plan_list(uid, start=start, end=end, include_done=include_done)
        return {"items": items, "summary": _summary(uid)}

    @app.post("/v1/plan")
    def plan_create(body: PlanIn, uid: str = Depends(current_user)) -> dict:
        return DB(settings.db_path).plan_create(uid, _clean(body.model_dump(), partial=False))

    @app.post("/v1/plan/reorder")
    def plan_reorder(body: ReorderIn, uid: str = Depends(current_user)) -> dict:
        try:
            normalize_fields({"date": body.date}, partial=True)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from None
        moved = DB(settings.db_path).plan_reorder(uid, body.date, list(body.ids))
        return {"ok": True, "moved": moved}

    @app.post("/v1/plan/{plan_id}/toggle")
    def plan_toggle(plan_id: str, body: ToggleIn, uid: str = Depends(current_user)) -> dict:
        row = DB(settings.db_path).plan_toggle(uid, plan_id, body.done)
        if row is None:
            raise HTTPException(404, "plan_not_found")
        return row

    @app.patch("/v1/plan/{plan_id}")
    def plan_patch(plan_id: str, body: PlanPatchIn, uid: str = Depends(current_user)) -> dict:
        fields = _clean(body.model_dump(), partial=True)
        try:
            row = DB(settings.db_path).plan_update(uid, plan_id, fields)
        except ValueError as exc:
            raise HTTPException(400, str(exc)) from None
        if row is None:
            raise HTTPException(404, "plan_not_found")
        return row

    @app.delete("/v1/plan/{plan_id}")
    def plan_delete(plan_id: str, uid: str = Depends(current_user)) -> dict:
        if not DB(settings.db_path).plan_delete(uid, plan_id):
            raise HTTPException(404, "plan_not_found")
        return {"ok": True}

    # main.py 末尾有一条 /v1/{rest:path} 兜底 404，按注册顺序会先吃掉 /v1/plan。
    # 把刚挂的这几条挪到最前面，测的才是它们。
    mine = [r for r in app.router.routes if isinstance(r, APIRoute) and r.path.startswith("/v1/plan")]
    for route in mine:
        app.router.routes.remove(route)
    app.router.routes[0:0] = mine
    return True


attached = install_reference_routes()
print(f"[路由] {'main.py 已挂 /v1/plan，测的是它' if not attached else 'main.py 还没挂，本进程临时挂了一份参考实现'}")

client = TestClient(app)


def register(email: str) -> dict:
    r = client.post("/v1/auth/register", json={"email": email, "passphrase": "passphrase-123"})
    assert r.status_code == 200, r.text
    return {"Authorization": f"Bearer {r.json()['token']}", "_uid": r.json()["user"]["id"]}


def headers(user: dict) -> dict:
    return {"Authorization": user["Authorization"]}


# ---- 1. 未登录一律 401 ----------------------------------------------------
expect(client.get("/v1/plan").status_code == 401, "未登录 GET /v1/plan → 401")
expect(client.post("/v1/plan", json={"title": "x", "date": "2026-10-29"}).status_code == 401,
       "未登录 POST /v1/plan → 401")
expect(client.get("/v1/plan", headers={"Authorization": "Bearer nope"}).status_code == 401,
       "坏 token GET /v1/plan → 401")

a = register("plan-a@example.com")
b = register("plan-b@example.com")

# ---- 2. 建一条 ------------------------------------------------------------
r = client.post("/v1/plan", headers=headers(a), json={
    "date": "2026-10-29",
    "title": "  复习细胞生物学  ",
    "note": "第 4 章到第 6 章",
    "start": "14:00",
    "end": "15:30",
    "priority": 1,
})
expect(r.status_code == 200, f"建计划返回 200（实际 {r.status_code}）")
one = r.json() if r.status_code == 200 else {}
expect(str(one.get("id", "")).startswith("plan_") and len(one.get("id", "")) == 21,
       f"id 是 plan_ + 16 位 hex（{one.get('id')}）")
expect(one.get("title") == "复习细胞生物学", "标题去掉首尾空白后保存")
expect(one.get("date") == "2026-10-29", "date 原样保存")
expect(one.get("note") == "第 4 章到第 6 章", "note 保存")
expect(one.get("start") == "14:00" and one.get("end") == "15:30", "start / end 保存")
expect(one.get("done") is False, "新建默认 done=false")
expect(one.get("priority") == 1, "priority 保存")
expect(isinstance(one.get("sort"), int), "sort 是整数")
expect(isinstance(one.get("created_at"), float) and one["created_at"] > 0, "created_at 是时间戳")
expect(one.get("updated_at") == one.get("created_at"), "新建时 updated_at == created_at")
expect("user_id" not in one, "响应里不带 user_id")

# 只有 start 没有 end 的、以及只有 start 的写法
r = client.post("/v1/plan", headers=headers(a), json={"date": "2026-10-29", "title": "交作业"})
expect(r.status_code == 200 and r.json()["start"] is None and r.json()["end"] is None,
       "不写时间时 start / end 都是 null")

# ---- 3. 非法输入被拒 ------------------------------------------------------
def rejected(payload: dict, what: str) -> None:
    r = client.post("/v1/plan", headers=headers(a), json=payload)
    expect(r.status_code == 400, f"{what} → 400（实际 {r.status_code}）")


rejected({"date": "2026-10-29", "title": "   "}, "空标题（只有空白）被拒")
rejected({"date": "2026-10-29"}, "没给标题被拒")
rejected({"date": "2026-10-29", "title": "x" * 201}, "标题超 200 字被拒")
rejected({"date": "2026-13-01", "title": "坏月份"}, "坏日期 2026-13-01 被拒")
rejected({"date": "2026-02-30", "title": "不存在的日子"}, "不存在的日期 2026-02-30 被拒")
rejected({"date": "10/29/2026", "title": "格式不对"}, "非 YYYY-MM-DD 被拒")
rejected({"date": "2026-10-29", "title": "时间段反了", "start": "15:00", "end": "14:00"},
         "end <= start 被拒")
rejected({"date": "2026-10-29", "title": "同一时刻", "start": "14:00", "end": "14:00"},
         "end == start 被拒")
rejected({"date": "2026-10-29", "title": "只有一头", "start": "14:00"}, "只填 start 不填 end 被拒")
rejected({"date": "2026-10-29", "title": "坏时刻", "start": "25:00", "end": "26:00"},
         "25:00 不是有效时刻")
rejected({"date": "2026-10-29", "title": "优先级越界", "priority": 9}, "priority=9 被拒")
rejected({"date": "2026-10-29", "title": "备注太长", "note": "字" * 1001}, "note 超 1000 字被拒")

# 未知字段忽略，不报错
r = client.post("/v1/plan", headers=headers(a), json={
    "date": "2026-10-29", "title": "带未知字段", "wat": 1, "id": "plan_hack", "user_id": "别人的",
})
expect(r.status_code == 200 and r.json()["title"] == "带未知字段", "未知字段被忽略、不报错")
expect(r.json()["id"] != "plan_hack", "客户端传的 id 不会被采信")
expect("user_id" not in r.json(), "客户端传的 user_id 不会被采信")
extra_id = r.json().get("id")

# ---- 4. 列表按日期过滤 ----------------------------------------------------
client.post("/v1/plan", headers=headers(a), json={"date": "2026-10-27", "title": "前天的事"})
client.post("/v1/plan", headers=headers(a), json={"date": "2026-11-02", "title": "下周的事"})
client.post("/v1/plan", headers=headers(a), json={"date": "2026-10-31", "title": "周末的事"})

r = client.get("/v1/plan", headers=headers(a))
rows = r.json()["items"]
expect(r.status_code == 200 and len(rows) == 6, f"不带过滤返回全部 6 条（实际 {len(rows)}）")
days = [x["date"] for x in rows]
expect(days == sorted(days), f"列表按 date 升序（{days}）")

r = client.get("/v1/plan?start=2026-10-29&end=2026-10-31", headers=headers(a))
got = [x["date"] for x in r.json()["items"]]
expect(got == ["2026-10-29", "2026-10-29", "2026-10-29", "2026-10-31"],
       f"start/end 闭区间过滤正确（{got}）")

r = client.get("/v1/plan?start=2026-10-30", headers=headers(a))
expect(all(x["date"] >= "2026-10-30" for x in r.json()["items"]) and len(r.json()["items"]) == 2,
       "只给 start 时按 >= 过滤")

r = client.get("/v1/plan?start=2026-10-27&end=2026-10-27", headers=headers(a))
expect([x["title"] for x in r.json()["items"]] == ["前天的事"], "start=end 取单日")

# DB 层的 date= 精确过滤（HTTP 契约里没有这个参数，库方法有）
one_day = DB(settings.db_path).plan_list(a["_uid"], date="2026-10-27")
expect([x["title"] for x in one_day] == ["前天的事"], "DB.plan_list(date=...) 精确到某一天")

expect("summary" in r.json(), "列表响应带 summary")

# ---- 5. 更新标题 ----------------------------------------------------------
r = client.patch(f"/v1/plan/{one['id']}", headers=headers(a), json={"title": "复习细胞生物学（改）"})
expect(r.status_code == 200 and r.json()["title"] == "复习细胞生物学（改）", "PATCH 改标题生效")
expect(r.json()["updated_at"] >= one["updated_at"], "改完 updated_at 往前走")
expect(r.json()["note"] == "第 4 章到第 6 章", "只改标题时其它字段不动")

r = client.patch(f"/v1/plan/{one['id']}", headers=headers(a), json={"start": "16:00", "end": "17:00"})
expect(r.status_code == 200 and r.json()["start"] == "16:00" and r.json()["end"] == "17:00",
       "PATCH 改时间段生效")
r = client.patch(f"/v1/plan/{one['id']}", headers=headers(a), json={"start": "18:00", "end": "17:00"})
expect(r.status_code == 400, f"PATCH 成 end <= start 被拒（实际 {r.status_code}）")
r = client.patch(f"/v1/plan/{one['id']}", headers=headers(a), json={"start": "", "end": ""})
expect(r.status_code == 200 and r.json()["start"] is None and r.json()["end"] is None,
       "PATCH 传空串把时间段清掉")
expect(client.patch("/v1/plan/plan_doesnotexist00", headers=headers(a), json={"title": "x"}).status_code == 404,
       "PATCH 不存在的 id → 404")

# ---- 6. toggle ------------------------------------------------------------
r = client.post(f"/v1/plan/{one['id']}/toggle", headers=headers(a), json={"done": True})
expect(r.status_code == 200 and r.json()["done"] is True, "toggle 置 done=true")
r = client.post(f"/v1/plan/{one['id']}/toggle", headers=headers(a), json={"done": False})
expect(r.status_code == 200 and r.json()["done"] is False, "toggle 置 done=false")
client.post(f"/v1/plan/{one['id']}/toggle", headers=headers(a), json={"done": True})

r = client.get("/v1/plan?include_done=0", headers=headers(a))
expect(all(x["done"] is False for x in r.json()["items"]), "include_done=0 只回没做完的")
expect(client.post("/v1/plan/plan_doesnotexist00/toggle", headers=headers(a),
                   json={"done": True}).status_code == 404, "toggle 不存在的 id → 404")

# ---- 7. 删除 --------------------------------------------------------------
r = client.delete(f"/v1/plan/{extra_id}", headers=headers(a))
expect(r.status_code == 200 and r.json().get("ok") is True, "DELETE 返回 ok")
left = [x["id"] for x in client.get("/v1/plan", headers=headers(a)).json()["items"]]
expect(extra_id not in left, "删除后列表里没有它")
expect(client.delete(f"/v1/plan/{extra_id}", headers=headers(a)).status_code == 404,
       "再删一次 → 404")

# ---- 8. reorder -----------------------------------------------------------
day = "2026-10-28"
day_q = f"start={day}&end={day}"
made = [
    client.post("/v1/plan", headers=headers(a), json={"date": day, "title": f"第 {i} 条"}).json()["id"]
    for i in range(3)
]
before = [x["id"] for x in client.get(f"/v1/plan?{day_q}", headers=headers(a)).json()["items"]]
expect(before == made, "新建的按加的先后排（sort 递增）")

flipped = list(reversed(made))
r = client.post("/v1/plan/reorder", headers=headers(a), json={"date": day, "ids": flipped})
expect(r.status_code == 200 and r.json()["moved"] == 3, f"reorder 动了 3 条（{r.json()}）")
after = [x["id"] for x in client.get(f"/v1/plan?{day_q}", headers=headers(a)).json()["items"]]
expect(after == flipped, f"reorder 后顺序变了（{after}）")

r = client.post("/v1/plan/reorder", headers=headers(a),
                json={"date": day, "ids": [made[0], "plan_not_mine_0000"]})
expect(r.json()["moved"] == 1, "reorder 跳过不属于这一天的 id")

# ---- 9. 租户隔离 ----------------------------------------------------------
r = client.get("/v1/plan", headers=headers(b))
expect(r.status_code == 200 and r.json()["items"] == [], "用户 B 看不到用户 A 的计划")
expect(client.get(f"/v1/plan?{day_q}", headers=headers(b)).json()["items"] == [],
       "B 按 A 的日期查也是空的")
expect(client.patch(f"/v1/plan/{one['id']}", headers=headers(b), json={"title": "改别人的"}).status_code == 404,
       "B 改 A 的计划 → 404")
expect(client.post(f"/v1/plan/{one['id']}/toggle", headers=headers(b), json={"done": True}).status_code == 404,
       "B toggle A 的计划 → 404")
expect(client.delete(f"/v1/plan/{one['id']}", headers=headers(b)).status_code == 404,
       "B 删 A 的计划 → 404")
expect(client.post("/v1/plan/reorder", headers=headers(b), json={"date": day, "ids": made}).json()["moved"] == 0,
       "B reorder A 的那一天动了 0 条")
still = [x["title"] for x in client.get("/v1/plan?start=2026-10-29&end=2026-10-29",
                                        headers=headers(a)).json()["items"]]
expect("复习细胞生物学（改）" in still, "A 的计划没被 B 动过")

# ---- 10. day_summary 逾期计算（固定日期） ---------------------------------
sample = [
    {"date": "2026-10-29", "done": False},
    {"date": "2026-10-29", "done": True},
    {"date": "2026-10-29", "done": True},
    {"date": "2026-10-28", "done": False},   # 逾期
    {"date": "2026-10-20", "done": False},   # 逾期
    {"date": "2026-10-27", "done": True},    # 昨天做完了，不算逾期
    {"date": "2026-10-30", "done": False},
    {"date": "2026-10-30", "done": False},
    {"date": "2026-10-31", "done": False},
    {"date": "2026-11-01", "done": True},    # 未来已完成，不进 upcoming
]
summ = day_summary(sample, "2026-10-29")
expect(summ["today_total"] == 3, f"今天共 3 项（{summ['today_total']}）")
expect(summ["today_done"] == 2, f"今天已完成 2（{summ['today_done']}）")
expect(summ["today_open"] == 1, f"今天还剩 1（{summ['today_open']}）")
expect(summ["overdue"] == 2, f"逾期 2 项（{summ['overdue']}）")
expect(summ["upcoming"] == [{"date": "2026-10-30", "count": 2}, {"date": "2026-10-31", "count": 1}],
       f"upcoming 只数没做完的（{summ['upcoming']}）")

empty = day_summary([], "2026-10-29")
expect(empty == {"today_total": 0, "today_done": 0, "today_open": 0, "overdue": 0, "upcoming": []},
       "没有计划时概览全是 0")

# ---- 11. 纯函数补充 ------------------------------------------------------
expect(normalize_fields({"date": "2026-10-29", "title": "x", "start": "9:00", "end": "10:30"})["start"] == "09:00",
       "9:00 顺手补成 09:00")
expect(normalize_fields({"date": "2026-10-29", "title": "x"})["priority"] == 0, "priority 默认 0")
expect(normalize_fields({"title": "x"}, partial=False)["date"] == today_local(), "不传 date 时落到今天")
expect(normalize_fields({"note": "只有备注"}, partial=True) == {"note": "只有备注"},
       "partial=True 只回传进来的字段")

print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
sys.exit(1 if failures else 0)
