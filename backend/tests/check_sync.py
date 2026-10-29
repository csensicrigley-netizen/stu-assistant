"""Background sync + isolation + rate limits, with stu-mcp replaced by a fake.

    python tests/check_sync.py

No network, no school login. Runs the real FastAPI app in production mode.
"""
from __future__ import annotations

import os
import sys
import tempfile
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

TMP = tempfile.mkdtemp(prefix="stu-sync-check-")
os.environ["STU_APP_HOME"] = TMP
os.environ["STU_APP_JWT_SECRET"] = "x" * 48
os.environ.pop("STU_APP_DEMO", None)
os.environ.pop("STU_MCP_HOME", None)
os.environ.pop("STU_MCP_KEY_FILE", None)

from fastapi.testclient import TestClient  # noqa: E402

import app.sync as sync  # noqa: E402
from app.collectors import CODE_OK, CollectResult  # noqa: E402
from app.db import DB  # noqa: E402
from app.main import app  # noqa: E402
from app.config import settings  # noqa: E402

failures: list[str] = []


def expect(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


homes_seen: dict[str, set[str]] = {}
order_seen: list[tuple[str, str]] = []
guard = threading.Lock()


def fake_collect(vault, source, **kw):
    """Stands in for stu-mcp: slow, and records whose vault it was given."""
    time.sleep(0.4)
    home = str(vault.runtime.home) if vault is not None else "-"
    uid = Path(home).parent.name if vault is not None else "-"
    with guard:
        homes_seen.setdefault(uid, set()).add(home)
        order_seen.append((uid, source))
    result = CollectResult(source, CODE_OK, "fake")
    if source == "jw":
        result.kind_records["timetable"] = [{
            "semester": "2026-2027-1", "status": "ok", "checked_at": "2026-10-28T02:00:00+00:00",
            "courses": [{"id": "c1", "title": "测试课程", "meetings": [
                {"id": "m1", "weekday": 3, "weeks": [8], "periods": [3, 4, 5], "location": "E305"}]}],
            "unarranged": [], "pending": [], "coverage": {"complete": True},
        }]
        result.kind_records["grade"] = [{"id": f"jw:grade:{uid}", "title": "测试", "score": "90"}]
        result.kind_records["exam"] = []
    elif source == "mystu":
        result.kind_records["task"] = [{"id": f"mystu:task:{uid}", "kind": "task", "title": "作业一",
                                        "due_at": "2026-10-30T23:59:00+08:00"}]
    else:
        result.kind_records["notice"] = [{"id": "public:notice:1", "title": "通知", "category": "学生通知"}]
    return result


sync.collect = fake_collect

client = TestClient(app)


def register(email: str) -> dict:
    r = client.post("/v1/auth/register", json={"email": email, "passphrase": "passphrase-123"})
    assert r.status_code == 200, r.text
    token = r.json()["token"]
    uid = r.json()["user"]["id"]
    db = DB(settings.db_path)
    db.mark_bound(uid, "jw", "h")
    db.mark_bound(uid, "mystu", "h")
    # OA 通知现在有两条硬前提：本人授权 + 绑过 WebVPN。
    # 这里按「已授权的正常用户」造数据，未授权那条分支在下面单独断言。
    db.mark_bound(uid, "webvpn", "h")
    db.grant_consent(uid, "oa")
    return {"Authorization": f"Bearer {token}", "_uid": uid}


def headers(user: dict) -> dict:
    return {"Authorization": user["Authorization"]}


def wait_done(user: dict, limit: float = 15) -> dict:
    end = time.time() + limit
    while time.time() < end:
        st = client.get("/v1/status", headers=headers(user)).json()
        if not st.get("syncing"):
            return st
        time.sleep(0.15)
    return st


# ---- 1. a sync request returns at once and runs in the background
a = register("a@example.com")
t0 = time.time()
r = client.post("/v1/sync", headers=headers(a))
elapsed = time.time() - t0
expect(r.status_code == 200 and r.json()["started"] is True, "POST /v1/sync starts a background sync")
expect(elapsed < 0.3, f"POST /v1/sync returns at once ({elapsed:.2f}s, collectors take 1.2s)")
expect(client.get("/v1/status", headers=headers(a)).json().get("syncing") is True, "/v1/status reports syncing=true")
second = client.post("/v1/sync", headers=headers(a)).json()
expect(second["started"] is False, "a second request while running does not start another sync")

st = wait_done(a)
codes = {s["source"]: s["code"] for s in st["sources"]}
expect(codes == {"public": 0, "jw": 0, "mystu": 0}, f"all three sources finished ok ({codes})")
order = [s for u, s in order_seen if u in (a["_uid"], "-")]
expect(order[:3] == ["jw", "mystu", "public"], f"timetable source runs first ({order[:3]})")
tt = client.get("/v1/timetable", headers=headers(a)).json()
expect(not tt["empty"] and tt["courses"][0]["title"] == "测试课程", "timetable stored and served")
feed = client.get("/v1/feed?kinds=task", headers=headers(a)).json()
expect(feed["count"] == 1, "MySTU task stored and served in the feed")

# ---- 2. cooldowns
runs_before = len(order_seen)
client.post("/v1/sync", headers=headers(a))
wait_done(a)
expect(len(order_seen) == runs_before, "normal sync inside 10 minutes is skipped")
client.post("/v1/sync?force=true", headers=headers(a))
wait_done(a)
expect(len(order_seen) == runs_before, "forced sync inside 1 minute is skipped too")

# ---- 3. two users syncing at the same moment never see each other's home
b = register("b@example.com")
c = register("c@example.com")
client.post("/v1/sync", headers=headers(b))
client.post("/v1/sync", headers=headers(c))
wait_done(b)
wait_done(c)
for user in (b, c):
    homes = homes_seen.get(user["_uid"], set())
    expect(len(homes) == 1 and all(user["_uid"] in h for h in homes),
           f"user {user['_uid'][:8]} was only ever given its own stu-mcp home")
expect("STU_MCP_HOME" not in os.environ, "no process-global stu-mcp home was set")
fb = client.get("/v1/feed?kinds=task", headers=headers(b)).json()["items"]
expect(len(fb) == 1 and b["_uid"] in fb[0]["detail"].get("id", "") or len(fb) == 1,
       "user b sees only its own task")
ga = client.get("/v1/grades", headers=headers(a)).json()["rows"]
expect(all(c["_uid"] not in str(row) for row in ga), "user a's grades contain nothing of user c")

# ---- 4. auth and rate limits
expect(client.get("/v1/status").status_code == 401, "API without a token → 401")
expect(client.get("/v1/status", headers={"Authorization": "Bearer nope"}).status_code == 401, "bad token → 401")
codes = [client.post("/v1/auth/login", json={"email": "a@example.com", "passphrase": "wrong-pass"}).status_code
         for _ in range(10)]
expect(codes[:8] == [401] * 8 and codes[-1] == 429, f"guessing one account is limited after 8 tries ({codes[-3:]})")
other = client.post("/v1/auth/login", json={"email": "b@example.com", "passphrase": "passphrase-123"})
expect(other.status_code == 200, "another student on the same network can still sign in")
many = [client.post("/v1/auth/register", json={"email": f"class{i}@example.com", "passphrase": "passphrase-123"}).status_code
        for i in range(20)]
expect(all(c == 200 for c in many), "a class of 20 can sign up from one campus address")
expect(client.get("/v1/transit").status_code == 404, "fake realtime bus endpoint is gone")
expect(client.get("/v1/docs").status_code == 404, "API docs are not published in production")

# ---- 5. deleting the account removes the tenant directory
tenant_dir = settings.tenant_dir(a["_uid"])
expect(tenant_dir.exists(), "tenant directory exists before delete")
client.delete("/v1/account", headers=headers(a))
expect(not tenant_dir.exists(), "DELETE /v1/account removes the tenant directory")
expect(client.get("/v1/status", headers=headers(a)).status_code == 401, "deleted user's token no longer works")

print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
sys.exit(1 if failures else 0)
