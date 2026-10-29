# STU 事项助手

汕头大学校园信息的个人助手：把**教务课表、MySTU 作业、学校 OA 通知**收在一处，
网页版和安卓 App 同源，按教务处官方作息算准每一节课。

> 本项目是**学生自用**的只读工具，与学校官方无关。
> 只读你本人账号本来就能看到的内容，**不代签到、不交作业、不缴费、不提交任何东西**。

---

## 功能

| 模块 | 内容 |
|---|---|
| 今天 | 现在/下一节课、还剩多久、教室怎么走；今天的课与空档排成一条时间轴 |
| 课表 | 整学期课表、周次、调课与停课、考试安排 |
| 学业 | 作业按截止时间排序、成绩与学分加权、考试倒数 |
| 校园 | 充电桩实时空闲口、图书馆开放时间、到校交通、常用入口 |
| 我的 | 连接校园账号、通知偏好、实时活动开关、清除数据 |

界面细节见 [`DESIGN.md`](DESIGN.md)，需求与取舍见 [`PLAN.md`](PLAN.md)。

---

## 架构

```
浏览器 / 安卓 App（React PWA + Capacitor）
        │  HTTPS
        ▼
  FastAPI 后端（多租户，每个用户一份独立加密空间）
        │
        ├── stu-mcp：学校统一认证 / 教务 / MySTU / WebVPN 的登录与解析层
        └── 第三方充电平台：公开只读接口
```

- **前端**：React + TypeScript + Vite，PWA 可装到桌面；安卓包用 Capacitor 打包同一份前端。
- **后端**：FastAPI + SQLite（零外部依赖），多租户隔离，每个用户一个租户目录。
- **学校对接**：全部交给 [`stu-mcp`](https://github.com/0error0warning/stu-mcp)（MIT），
  本项目只做编排、缓存、限流与呈现。

---

## 快速开始（本地）

需要 **Python 3.12** 与 **Node 20+**。

```bash
# 后端
cd backend
python -m venv .venv
.venv/Scripts/activate            # Linux/macOS: source .venv/bin/activate
pip install -c constraints.txt -r requirements.txt wheels/stu_mcp-0.7.3-py3-none-any.whl
export STU_APP_DEMO=true          # 演示模式：假数据、不连学校系统
export STU_APP_HOME=./data
uvicorn app.main:app --port 8100
```

```bash
# 前端
cd web
npm ci
npm run dev                       # http://localhost:5180
```

前端开发服务器把 `/v1` 代理到 `127.0.0.1:8100`，两边都起来就能用。

**演示模式**下不需要任何学校账号，界面里全是示例数据，适合先看效果。

---

## 环境变量

| 变量 | 说明 |
|---|---|
| `STU_APP_HOME` | 数据目录（SQLite、租户会话、上传），默认 `/data/stu-app` |
| `STU_APP_JWT_SECRET` | 登录令牌签名密钥。正式模式留空会**拒绝启动**；生成见 `.env.example` |
| `STU_APP_DEMO` | `true` 用假数据、不连学校系统 |
| `STU_APP_CORS_ORIGINS` | 允许的跨域来源；浏览器版同源不需要，打包 App 的 WebView 需要 |
| `STU_APP_WEB_DIR` | 设为 `web/dist` 时，后端同时托管前端静态文件 |

完整示例见 [`.env.example`](.env.example)。

---

## 部署

`deploy/` 里是一键安装脚本，面向一台你自己的 Linux 服务器：

```bash
sudo bash deploy/install.sh <你的域名>
```

它会建系统用户、装 venv、写 systemd 服务、配 Caddy 反代与证书，并跑一次 `app.doctor` 自检。
**安装脚本不包含任何真实域名、IP、密钥**；这些都要你自己填。

安卓包：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\make-release.ps1 -Server https://你的域名
```

- 需要你自己准备签名密钥（`android-signing/keystore.properties`），**仓库里不含任何签名材料**
- 脚本会校验 `web/src/lib/version.ts` 与 `android/app/build.gradle` 的版本号一致，不一致直接中止
- 产物在 `release/`，同时把版本写进服务端 `latest_version.json`，旧客户端就能收到更新提示

---

## 测试

```bash
cd backend
STU_APP_DEMO=true STU_APP_HOME=./tmp python tests/check_static.py
STU_APP_DEMO=true STU_APP_HOME=./tmp python tests/check_stu_mcp.py
STU_APP_DEMO=true STU_APP_HOME=./tmp python tests/check_sync.py

cd ../web
npm run typecheck
npm run build
```

三份自检分别覆盖：静态资源与安全头、stu-mcp 集成与租户隔离、同步编排与限流。

---

## 授权与边界（重要）

详见 [`docs/授权与边界.md`](docs/授权与边界.md)。要点：

- **账号密码由使用者本人输入**，勾选记住时加密存在**该用户自己的租户目录**里，用于会话过期后自动重连
- **动态令牌（如数盾 OTP）由本人当场输入**，服务器不生成、不保存、不代输、不转发
- **本人授权**：读 OA 通知前必须由本人在界面上勾选授权（`POST /v1/consent`），没有这条记录连 WebVPN 都不放行
- **只读**：不向学校任何系统写入或提交；不代签到、不代缴费、不代交作业
- **可撤回**：`DELETE /v1/consent` 立刻停止读取；`DELETE /v1/account` 连租户目录一起删除
- 数盾、统一认证等均为学校方系统，本项目不绕过任何认证环节

---

## 不做什么

- 不接「车来了」实时公交（上游禁止第三方访问，宁可空着也不编造到站时间）
- 不在 App 内转载树洞内容
- 不用环境变量切换租户、不共用会话目录
- 不猜解密码、不爆破、不伪造令牌

---

## 目录

```
backend/
  app/            后端源码（main.py 是路由，collectors.py 是采集，sync.py 是编排）
  tests/          三份自检脚本
  wheels/         stu-mcp 的官方 wheel（MIT）
web/
  src/            React 源码（screens/ 页面、components/ 组件、lib/ 逻辑）
  scripts/        打包与截图脚本
  android/        Capacitor 安卓工程
deploy/           安装与发布脚本
docs/             数据契约、官方来源、能力地图、授权边界、接口手册
```

---

## 致谢

- [`stu-mcp`](https://github.com/0error0warning/stu-mcp)（MIT）：学校登录与解析层，没有它这个项目无从谈起
- 图标与配色为自制；字体使用系统字体

## 许可

MIT。见 [`LICENSE`](LICENSE)。
