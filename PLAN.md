# STU 事项助手 · 架构方案

一个手机 App：学生输入校园账号密码 → 自动汇总通知 / 课表 / 作业 / 成绩 / 考试 → 推送提醒。

参考对象是 B 机（汕头大学机）已经在跑的那套：定时抓取 → 缓存 JSON → 分析 → 推送。本文把这套单用户脚本升级成多租户服务。

---

## 一、先说结论：三层，中间层复用现成采集库

```
┌─────────────────────────────────────────────────┐
│  Flutter App（Android + iOS）                    │
│  登录 / 首页汇总 / 课表 / 通知 / 成绩 / 设置      │
└────────────────────┬────────────────────────────┘
                     │ HTTPS + JWT
┌────────────────────▼────────────────────────────┐
│  FastAPI 网关（多租户）                          │
│  · 认证、租户隔离、限流                          │
│  · 同步调度、缓存、去重、推送                    │
│  · 调 stu-mcp 采集层（不重写登录/解析）          │
└────────────────────┬────────────────────────────┘
                     │ 进程内调 Python
┌────────────────────▼────────────────────────────┐
│  stu-mcp（现成，MIT）                            │
│  auth_http/ 登录 · features/ 解析 · vault 加密   │
└────────────────────┬────────────────────────────┘
                     │ HTTPS
            jw / my.stu / sso / OA / 一卡通
```

**关键判断：不要重写登录和解析。** stu-mcp 已经把汕大 13 个数据源全吃下来了：

```
anonymous  charging  duifene  huyou  jw  mystu  oa  public
repair  school  smartcard  webvpn  yuketang
```

而且它的登录核心**本来就可编程**：

```python
from stu_mcp.auth_http import login_http, Credentials, login_school

# 一次 SSO 打通教务 + MySTU + 报修
login_school(vault, services=["jw", "mystu", "repair"],
             interaction=MyAdapter(student_id, password))
```

`login_http()` 有 `credentials=` 参数，传了就不走终端交互。这是整个方案能成立的根本。

---

## 二、B 机的工作逻辑，哪些直接抄

| B 机做法 | 抄不抄 | 原因 |
|---|---|---|
| **先探端口再登录**（`tcp_probe`） | ✅ 抄 | 学校夜里关教务站，探测失败直接退出码 2，不刷失败计数、不触发告警。App 端表现为「教务系统维护中」，而不是「登录失败」 |
| **退出码分五类**（0/1/2/3/4） | ✅ 抄 | 把「预期的不可用」和「真故障」分开，这是容错的关键。App 端每种码给不同文案 |
| **check → 失效才重登 → 重登后复查** | ✅ 抄 | 避免无谓登录（登录次数多了会被风控） |
| **缓存 JSON + 去重** | ✅ 抄 | 但要去重键化，不能只存一份覆盖 |
| 固定账号密码写死在脚本里 | ❌ 改 | 多租户绝对不能这么干。见第五节 |
| pty 交互式登录 | ❌ 改 | 服务端没有终端，改用 `credentials=` 直传 |
| 单用户 keyring | ❌ 改 | 改用 `STU_MCP_KEY_FILE` 文件密钥，每租户一份 |
| GLM 分析后推微信 | ✅ 抄 | 升级成 APNs / FCM / 厂商推送 |

### 同步状态机（照 B 机退出码改造）

```
                    ┌─────────┐
                    │  probe  │  TCP 443 探测
                    └────┬────┘
              ┌──────────┴──────────┐
        不可达│                     │可达
              ▼                     ▼
        code=2 maintenance     ┌─────────┐
        （静默，不告警）        │  check  │ 带 cookie 请求一次列表页
                               └────┬────┘
                          ┌─────────┴─────────┐
                     200+有效DOM           失效/401
                          │                   │
                          ▼                   ▼
                    ┌──────────┐        ┌─────────┐
                    │ collect  │        │ relogin │
                    └────┬─────┘        └────┬────┘
                         │         ┌─────────┴────────┐
                         │    需要验证码/二维码      失败
                         │         │                │
                         │         ▼                ▼
                         │   code=3 need_input   code=1 failed
                         │   （推送给 App 让用户补）  （告警 + 退避）
                         ▼
                    code=0 ok
```

**每条路径都要有明确的 code**，App 端据此显示不同 UI，而不是统一弹「出错了」。

---

## 三、多租户改造的四个点

这是唯一需要动的工程部分，其余全复用。

### 3.1 租户文件布局

```
/data/tenants/<user_uuid>/
├── stu-mcp/                      ← STU_MCP_HOME
│   ├── sessions/jw.bin           ← Fernet 加密 cookie
│   ├── sessions/mystu.bin
│   └── cache/
└── master.key                    ← STU_MCP_KEY_FILE（0600，每租户独立）
```

```python
os.environ["STU_MCP_HOME"] = f"/data/tenants/{uid}/stu-mcp"
os.environ["STU_MCP_KEY_FILE"] = f"/data/tenants/{uid}/master.key"
```

**注意**：`os.environ` 是进程全局，并发不安全。骨架里用**每租户一把锁 + 上下文管理器**串行化；生产建议每租户一个 worker 进程（`multiprocessing` + 队列），彻底隔离。

### 3.2 登录：从 pty 换成程序化

```python
class WebLoginInteraction:
    """把 stu-mcp 的交互点接到 App：验证码/二维码/动态码回传前端。"""
    def __init__(self, username, password, broker): ...
    def credentials(self):
        return Credentials(self.username, self.password)   # 密码只用一次，不落盘
    def captcha(self, image, *, media_type):
        return self.broker.ask("captcha", image=image)      # 抛 challenge 给 App
    def show_qr(self, content):
        self.broker.show_qr(content)
    def code(self):
        return self.broker.ask("totp")
```

多步登录用现成的 `SetupAuthJobs`（`setup_auth.py`），它本来就是为 Web 设置页设计的状态机：`start()` / `submit_input()` / `snapshot()`，天然对应 App 的「等待用户输入」页面。

### 3.3 采集：直接调 feature adapters

```python
from stu_mcp.features.jw.adapter import JwAdapter
from stu_mcp.features.notices.adapter import NoticeAdapter

with JwAdapter(vault) as jw:
    form = jw.timetable_form()
    tt   = jw.timetable(form, semester="2026-2027-1")
    grades = jw.grades(semester)
    exams  = jw.exams(semester)

na = NoticeAdapter()
for rec in na.public_listing(na.public_session(), url, "notice", limit=20):
    ...
```

### 3.4 会话失效：只提示重登，不存密码

vault 里只有加密 cookie。密码在绑定那一刻用掉就丢。这是**刻意设计**——存密码就意味着要为泄露兜底，而 cookie 泄露的爆炸半径小得多，还能被用户改密码一键吊销。

---

## 四、数据模型

```sql
-- 平台账号（App 自己的登录）
CREATE TABLE users (
  id            UUID PRIMARY KEY,
  email         TEXT UNIQUE NOT NULL,
  pass_hash     TEXT NOT NULL,           -- argon2id
  student_id_h  TEXT,                    -- 学号只存哈希，用于防重复绑定
  status        TEXT NOT NULL DEFAULT 'active',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 采集结果统一收口，kind 区分类型
CREATE TABLE items (
  id            BIGSERIAL PRIMARY KEY,
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  source        TEXT NOT NULL,           -- jw / mystu / oa / charging / huyou ...
  kind          TEXT NOT NULL,           -- notice / timetable / grade / exam / homework / schedule
  external_id   TEXT NOT NULL,           -- 源站 ID（课程号/公告 ID/成绩行 hash）
  title         TEXT,
  payload       JSONB NOT NULL,
  first_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, source, kind, external_id)   -- ★ 幂等键
);

-- 每次同步一行，code 用 B 机那套
CREATE TABLE sync_runs (
  id          BIGSERIAL PRIMARY KEY,
  user_id     UUID NOT NULL,
  source      TEXT NOT NULL,
  code        SMALLINT NOT NULL,         -- 0 ok / 1 failed / 2 maintenance / 3 need_input / 4 keystore
  detail      TEXT,
  items_seen  INT DEFAULT 0,
  started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ended_at    TIMESTAMPTZ
);

CREATE TABLE devices (
  id         BIGSERIAL PRIMARY KEY,
  user_id    UUID NOT NULL,
  platform   TEXT NOT NULL,              -- ios / android
  token      TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, token)
);

CREATE TABLE push_log (
  id       BIGSERIAL PRIMARY KEY,
  user_id  UUID NOT NULL,
  kind     TEXT NOT NULL,
  payload  JSONB NOT NULL,
  sent_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  ok       BOOLEAN NOT NULL
);
```

`UNIQUE (user_id, source, kind, external_id)` 是核心：重复同步不会产生重复条目，新条目才能触发推送。

---

## 五、API 契约

```
POST   /v1/auth/register        {email, passphrase}          → {token}
POST   /v1/auth/login           {email, passphrase}          → {token}

POST   /v1/bind                  {services[], student_id, password}
                                                                → {job_id}
GET    /v1/bind/{job_id}                                     → {state, phase, challenges[]}
POST   /v1/bind/{job_id}/input   {challenge_id, values}       → {state}

POST   /v1/sync                                               → {jobs:[{source, code}]}
GET    /v1/feed?since=<ts>&kinds=notice,grade,homework         → {items[]}
GET    /v1/timetable?semester=&week=                          → {days[], courses[]}
GET    /v1/notices?limit=30&cursor=                           → {items[], next}
GET    /v1/grades?semester=                                   → {rows[]}
GET    /v1/exams?semester=                                    → {rows[]}

POST   /v1/devices              {platform, token}             → {ok}
DELETE /v1/devices              {token}                       → {ok}
POST   /v1/account/logout                                     → {ok}
DELETE /v1/account                                            → {ok}   清空租户目录
```

`/v1/bind` 返回的 `challenges` 就是 App 需要弹的交互：

```json
{
  "state": "waiting_input",
  "phase": "captcha",
  "challenges": [
    { "id": "c1", "kind": "captcha", "image_b64": "iVBORw0..." },
    { "id": "c2", "kind": "qr",      "content": "https://..." },
    { "id": "c3", "kind": "totp",    "label": "动态码" }
  ]
}
```

---

## 六、部署

```
B 机
├── nginx :443  → 后端 :8100
├── FastAPI (systemd: stu-app.service)
├── /data/tenants/          租户数据
└── Postgres / SQLite

App Studio → APK / IPA
```

**为什么放 B 机**：它本来就是汕大业务机，出口 IP 与学校系统同城，延迟低；而且已经跑着 stu-mcp 的宿主环境（uv / Python 3.12）。

单机起步完全够：1000 个用户 × 每天 4 次同步 × 每源 2 次请求 ≈ 8000 请求/天，一台 2 核就够。

扩容路径：`/data/tenants/` 挂到 NFS，后端无状态横向扩，租户 ID 做一致性哈希。

---

## 七、工程约束（写进代码，不靠自觉）

1. **限速**：每个租户对每个源最小间隔 10 分钟，全局对 `jw.stu.edu.cn` 并发 ≤ 3
2. **登录节流**：同一租户 1 小时内最多 3 次登录尝试，失败退避（1/5/15 分钟）
3. **不并发轰炸**：单租户单源同时只有一个请求在跑（租户锁）
4. **会话优先**：能用 cookie 就不登录；登录是昂贵操作
5. **数据最小化**：`items.payload` 只存展示需要的字段，原始 HTML 不入库
6. **可删除**：`DELETE /v1/account` 删掉数据库行 + `/data/tenants/<uuid>` 整个目录
7. **密码不落盘**：任何日志、任何表、任何 traceback 里都不许出现密码（`logging` 里加过滤器）
8. **维护窗口不算故障**：`code=2` 不写告警、不推送给用户

---

## 八、参考资料（本机已有）

| 内容 | 路径 |
|---|---|
| stu-mcp 完整源码（116 commit） | `../stu-mcp-reverse/repo` |
| 采集层入口 | `repo/src/stu_mcp/features/*/adapter.py` |
| 登录核心 | `repo/src/stu_mcp/auth_http/__init__.py` → `login_http` |
| 多步登录状态机 | `repo/src/stu_mcp/setup_auth.py` → `SetupAuthJobs` |
| 会话加密 | `repo/src/stu_mcp/vault.py` |
| 文件密钥 | `repo/src/stu_mcp/credentials.py` → `FileKeyStore` |
| B 机会话维持脚本 | B 机:`/root/ensure_jw.py` |
| B 机课表生成 | B 机:`/root/weekly_thisweek.py` |
