# STU 事项助手 · 能力地图（Phase 0 一手材料）

> 目的：把 stu-mcp 采集层「能做什么、验收到哪一步、数据多新」和 App「现在接了什么」一次摊开，作为后续用 Kano 做功能取舍的依据。本文只做整理与风险标注，**不排序、不下结论要不要做**。
>
> 材料基线：开源 MIT 仓库 `stu-mcp` tag **v0.7.3**（`<本地 stu-mcp 检出目录>`），共 51 个 MCP 工具、`SOURCES` 实际登记 8 个来源；App 侧为 `backend/app/`（FastAPI 多租户）+ `web/`（React PWA，五个 tab）。
>
> 关键区分（全文沿用 stu-mcp 自己的口径）：
> - **接入程度**：`implemented`（已有业务适配器）/ `navigation_only`（只有入口导航，不办业务）/ `not_integrated`（未接入）。
> - **验收状态**：`已真实验收`（真人账号/真实匿名请求读到数据）/ `仅合成测试`（只有合成 HTML/JSON 回归）/ `未验收`。三者是不同证据层级，历史成功 ≠ 当前在线，合成通过 ≠ 真实可用。
> - 一个「入口存在」不等于「已验证可读」，「已验证可读」不等于「App 已接入」。

---

## 0. 速查：stu-mcp 的 8 个已登记来源 + 3 个独立通道

| 来源/通道 | 认证归组 | 接入程度 | App 是否已接 |
| --- | --- | --- | --- |
| `public` 官网/学生处通知 | 无需登录（匿名） | implemented | ✅ 已接 |
| `oa` OA 通知（含附件文字） | 匿名直读，受限时走 WebVPN | implemented | ⚠️ 随 `public` 采集器捎带（`include_oa=True`），前端未单列 |
| `jw` 教务：成绩/考试/理论课表 | 学校统一账号（CAS SSO） | implemented | ✅ 已接 |
| `mystu` 课程/作业/资料/个人日程 | 学校统一账号（CAS SSO） | implemented | ✅ 已接 |
| `yuketang` 雨课堂课程/作业/公告 | 雨课堂手机扫码 | implemented | ❌ 未接 |
| `huyou` 狐友·汕大树洞公开讨论 | 无需登录（匿名，第三方） | implemented | ❌ 未接 |
| `charging` 充电设备状态（第三方 wx.isfdz.com） | 无需登录（匿名，第三方） | implemented | ✅ 已接（生活 tab） |
| `smartcard` 一卡通本人概况/流水 | 独立查询密码 + 图片验证码（明文 HTTP） | implemented | ❌ 未接 |
| `repair` 桑浦山宿舍报修（草稿/预览/提交/回执） | 学校统一账号（CAS SSO） | implemented（提交写操作） | ⚠️ bind 流程里会登录，但无读取/提交界面 |
| `duifene` 对分易监听/签到（独立 stu-checkin 后台） | 微信回调授权 | implemented（含签到写操作） | ❌ 未接 |
| `takeout` 外卖目录/菜单/选餐（第三方，实验） | 宿主导入第三方签名配置 | implemented（实验） | ❌ 未接 |

> App 另有一个 stu-mcp 没有的能力：`transit` 校车/公交到站（`backend/app/transit.py`，demo provider，预留车来了 `chelaile`）。这是 App 自建，不来自采集层。

---

## 1. 学生任务地图

按大学生真实任务归类。「认证归组」用 stu-mcp 的 auth_family 口径。「数据新鲜度/缓存」优先引用 stu-mcp 的来源策略；App 另有每源 10 分钟同步节流（`config.py sync_min_interval=600`）。

### 1.1 上课 / 课表 / 作业 / 考试 / 成绩（学业核心）

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 这学期课在哪上、第几节 | `get_academic_timetable`（jw 理论课表） | 学校统一账号 CAS | implemented | 已真实验收（2026-10-24：当前学期 8 门/14 组安排、2 门未排课；历史空学期；出处 `verification/2026-10-23-academic-timetable.md`、`2026-10-23-v0.7.1.md`）；单双周等情形仅合成测试 | 按需读取，stu-mcp 不建课表缓存；App 侧按 `timetable` 快照整条替换（`sync.py SNAPSHOT_KINDS`）。不含实验课/校历/作息/调课 |
| 近期个人日程 | `get_schedule`（mystu） | 学校统一账号 CAS | implemented | 已真实验收（2026-10-21，MySTU 身份+课程） | 固定窗口：过去 7 天 ~ 未来 31 天（`features/mystu/adapter.py`），非完整学期 |
| 作业/测验 ddl、课程资料 | `get_tasks`/`get_course_resources`/`get_courses`（mystu + yuketang） | 学校统一账号 CAS（mystu）/ 雨课堂扫码（yuketang） | implemented | mystu 已真实验收（2026-10-21：10 课/34 资料/9 任务）；yuketang 已真实验收（2026-10-21：10 课/13 资料/3 任务） | mystu 按来源刷新；yuketang 每课最多 50 活动/20 公告（`features/yuketang/schema.py`）；App 仅接 mystu，未接 yuketang |
| 考试安排 | `get_exams`（jw） | 学校统一账号 CAS | implemented | 已真实验收（真人账号当前学期空考试表为真实返回；有记录的字段用合成样本，`2026-10-05-to-18-releases.md`） | 按请求读取，App 每源 10 分钟节流；按数量截取标 `partial` |
| 成绩 / 加权绩点 | `get_grades`/`academic_summary`（jw） | 学校统一账号 CAS | implemented | 已真实验收（2026-10-21：HTTPS 读 39 条成绩 + 加权统计） | 最多 200 行标 `partial`（`features/jw/service.py`）；App 入库去重 |
| 选课 / 评教 | —（未接入） | 学校统一账号 CAS | not_integrated | 评教空态仅在调查中读到，选课仅本人结果 10 条（`academic-network-round2.md`），未接入产品 | — |

### 1.2 通知 / 活动 / 竞赛

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 官网要闻/综合新闻/活动预告/学生处通知 | `refresh_source`/`search_notices`/`get_notice`/`read_attachment`（public） | 匿名 | implemented | 已真实验收（匿名读到官网栏目 + 学生处，`2026-10-05-to-18-releases.md`） | 每栏目限定数量（非全站/全历史）；App 每源 10 分钟节流 |
| OA 通知列表/正文/附件文字 | 同上（oa，匿名优先，受限走 webvpn） | 匿名 或 WebVPN | implemented | 已真实验收（2026-10-22/10-21：匿名读列表 5 条 + 正文 + 1 附件，`verification/2026-10-22-oa-entry-boundaries.md`） | 列表首页 1–50 条；附件 PDF/文本 ≤8 MiB、≤100 页，扫描件不 OCR |
| 竞赛/活动的「活动申报」等办事入口 | `get_service_links`（一网通办仅导航） | 学校统一账号 CAS | navigation_only | 一网通办 50 事项为匿名列表/详情（`service-matters.csv`，`proof=anonymous_list_and_detail…not_apply_permission`），未接入 | 入口导航不办业务、不代表登录成功 |

### 1.3 一卡通 / 消费 / 财务

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 一卡通余额 / 卡状态 | `get_campus_card`（smartcard） | 独立查询密码 + 图片验证码（明文 HTTP） | implemented | 已真实验收（2026-10-24，v0.7.1：卡余额 + 两项过渡余额 + 4 项卡状态，`verification/2026-10-23-v0.7.1.md`） | **不建持久缓存**，`cached=false`；只读概况，不含完整个人资料 |
| 一卡通历史流水 | `get_campus_card_transactions`（smartcard） | 同上 | implemented | 已真实验收（2026-10-24：按日期/类型读到流水，分类正确）；多页/多卡/自然过期仅合成测试 | 单次最多 30 天；分页未验证时只读第一页并标 `complete=false` |
| 水电（宿舍电费/用量） | —（not_integrated） | 学校统一账号 CAS | not_integrated | 调查中读到历史账单 + 读数（`dorm-utilities-followup.md`），未接入产品 | — |
| 校园卡充值/挂失/改密 | —（明确不接入） | — | not_integrated | 明确排除（`smartcard.md`：不接挂失/充值/改密/统计图） | — |

### 1.4 宿舍 / 报修 / 校园服务

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 宿舍设施报修（桑浦山 repair.stu.edu.cn） | `prepare/open/review/submit/check_repair_draft` 等 7 工具（repair） | 学校统一账号 CAS | implemented（写操作） | 登录+填表+完整预览 已真实验收（2026-10-21，22 字段预览，未发真实工单）；**HTTP 最终提交+学校回执未真实验收**（`http_submission_live_verified=false`）；浏览器真实提交为 2026-10-19 历史证据 | 草稿/预览/回执仅进程内存；预览 5 分钟失效；提交须本人逐次确认，单发不重试 |
| 综合报修 `r`（含校园网故障，两校区） | 无适配器，仅 `get_service_links` 导航 | 学校统一账号 CAS | navigation_only | 2026-10-18 人工登录看过表单；HTTP 交接中 `IMuserid` 语义未定，未建业务会话（`repair-r.md`） | 入口导航 |
| ITS 校园网报修 | 仅保留引用 | — | navigation_only | 2026-10-18 匿名直连失败/超时，停用未证实 | — |
| 一网通办事项目录（请假/综测/奖助/活动申报…） | `get_service_links`（一网通办）；具体 50 事项见 `service-matters.csv` | 学校统一账号 CAS | navigation_only / not_integrated | 公开目录匿名可读；本人待办/办理记录 `auth_read_verified` 仅在调查内，未接入 | 入口导航 |
| 宿舍管理 / 学生事务（xg 门户 19 应用） | —（not_integrated） | 学校统一账号 CAS | not_integrated | 调查中读到本人基本信息/住宿/违纪/奖助等（`xg-completion.md`），未接入产品 | — |

### 1.5 图书馆 / 学习资源

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 馆藏检索（书名/ISBN） | 拟新增 `search_library_books`/`get_library_book`（计划） | 匿名 | not_integrated（计划接入） | OPAC 公开检索已在调查实读（ANY 两页 40 书目、ISBN 正例/空态，`library-round2.md`）；TITLE 分支有异常；产品未接 | — |
| 本人借阅/到期、续借 | —（not_integrated） | 馆内账号/一卡通/MySTU（未验证） | not_integrated | 仅官方说明，实际认证未验证 | — |
| 图书馆空间/研讨间预约 | —（not_integrated） | 未核实 | not_integrated | newic 公开配置空态读到，认证后资源未验证 | — |
| 数据库资源导航 | —（not_integrated） | 匿名/CARSI/WebVPN | not_integrated | 公开目录可读；订购全文权限未验证 | — |

### 1.6 网络 / 出行 / 生活

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 校园网故障自查 / 入口连通诊断 | `diagnose_campus_network`/`audit_campus_network` | 匿名 | implemented（工具能力） | 合成为主 + 本机匿名探测（`network.md`、`network-audit.md`） | 进程内缓存 15 秒；不改 DNS/代理/路由 |
| 上网账号/套餐/流量余额（netms） | —（navigation_only） | 学校统一账号 CAS | navigation_only | 调查中认证后读到个人信息/套餐/当天流量包；实时已用流量接口未找到（`round2-completion.md`） | — |
| 充电桩空位/逐口状态 | `get_charging_overview`/`get_charging_device`（charging） | 匿名（第三方） | implemented | 已真实验收（2026-10-17：14 台设备、13 台 260 口、1 台离线，`charging.md`） | 同设备两次读 ≥60s；>120s 或最近失败标 `stale`；失败保留上次成功；**App 生活 tab 轮询 60s** |
| 校车/公交到站 | App 自建 `transit`（非 stu-mcp） | 无（demo）/ 第三方车来了 | implemented（App 侧，demo 数据） | 未验收（demo provider 确定性造数；chelaile 需填 city/sign key） | App 轮询 30s + 本地倒计时 |
| 外卖商家/菜单/配送批次/选餐 | `search_takeout_shops` 等 7 工具（takeout） | 宿主导入第三方签名配置 | implemented（实验） | 查询真实读过（2026-10-21：12 家奶茶/91 商品/规格），但多为合成 + 近期一次 `network_error`；不下单/不支付 | 进程缓存 30s；批次按 Asia/Shanghai 算截单 |

### 1.7 课程讨论 / 社区 / 签到

| 学生任务 | stu-mcp 工具/来源 | 认证归组 | 接入程度 | 验收状态 | 数据新鲜度/缓存 |
| --- | --- | --- | --- | --- | --- |
| 树洞/狐友公开讨论检索、看帖/评论 | `search_huyou_posts`/`get_huyou_post`/`search_huyou_circles`（huyou） | 匿名（第三方搜狐狐友） | implemented | 已真实验收（2026-10-06：3 圈子、1 帖正文 + 评论 + 内嵌回复，`huyou.md`） | 公开有界读取；缓存可合并；默认圈子＝汕大树洞 |
| 对分易课堂签到/监听 | `start/pause/get_checkin_*` 等（duifene，独立 stu-checkin 后台） | 微信回调授权 | implemented（含签到写操作） | 后台存活/重连/状态 已工程验收；**真实课堂数字码/二维码/汉字签到仍待本人实测（合成）**；定位签到默认关闭（`checkin.md`） | 后台加密保存最近 200 条活动；轮询 `poll_min/max 3~5s`；限流静默期不发请求 |

---

## 2. App 现状对照：stu-mcp 已实现但 App 未接

App 后端 `collectors.py` 的 `COLLECTORS` 只注册 **public、jw、mystu** 三个采集器（`public` 内部捎带 OA）；另有 App 自建的 **transit**（demo）与 **charging**（经 stu-mcp 充电客户端）。`web/src/screens` 五个 tab：今天/这周/消息(Inbox)/生活(Life)/我的(Me)，生活 tab 只用到 transit + charging。bind 绑定 `jw, mystu, repair`——repair 会登录但前端无读取/提交入口。

下表＝**stu-mcp v0.7.3 已 implemented、但 App 尚未暴露给用户**的能力：

| 能力 | stu-mcp 状态 | App 现状 | 落差说明 |
| --- | --- | --- | --- |
| 雨课堂 课程/作业/公告（yuketang） | implemented，真实验收 | 未接 | 作业 ddl 是学业核心，消息 tab 只有 notice/homework 过滤位但无 yuketang 数据源 |
| 一卡通 余额/流水（smartcard） | implemented，真实验收 | 未接 | 明文 HTTP + 独立查询密码，接入要单独做凭据通道 |
| 狐友·树洞 公开讨论（huyou） | implemented，真实验收 | 未接 | 社区内容，涉他人发言展示（见第 3 节风险） |
| 外卖 目录/菜单/选餐（takeout） | implemented（实验） | 未接 | 需宿主导入第三方签名配置，非学校业务 |
| 对分易 监听/签到（duifene） | implemented（签到写） | 未接 | 学术诚信红线（见第 3 节） |
| 宿舍报修 提交+回执（repair） | implemented（登录/预览真实验收，提交未真实验收） | bind 会登录，无界面 | 写操作，需本人确认 + 回执核查 UI |
| OA 通知单列为独立来源 + 搜索/正文/附件 | implemented | 仅随 public 捎带 | 前端未把 OA 单列，`search_notices`/`read_attachment` 未暴露 |
| MySTU 课程资料/任务明细（get_tasks/get_course_resources） | implemented | 部分（collect_mystu 只取 courses + schedule） | 资料/任务详情未进入 App feed |
| 教务理论课表的「未排课/待核对项」展示 | implemented（返回 coverage/unarranged） | 字段已入库未必展示 | 需要在「这周」tab 呈现 partial/未排课状态 |
| 校园网诊断、服务入口导航（diagnose/audit/get_service_links） | implemented（工具能力/导航） | 未接 | 「我的」或「生活」可做轻量自查/找入口 |
| 维护类：更新检查、本地待办、反馈（check_updates/set_task_status/report_feedback） | implemented | 未接 | 多为本机单机语义，多租户下未必适用 |

> 注：stu-mcp 另有大量 `navigation_only`/`not_integrated` 条目（一网通办、xg 学工、水电、图书馆、ehall 预约、邮箱、wpay、研究生/医学院…）——这些**在 stu-mcp 里也没接入**，不属于「App 未接但已实现」，仅作为后续候选，见第 5 节。

---

## 3. 法律与伦理风险表（中国大陆语境）

依据简称：**《个保法》**＝《个人信息保护法》；**《网安法》**＝《网络安全法》；**《数据安全法》**。核心语境变化：stu-mcp 原设计是「学生在自己电脑上单用户运行」，本 App 把它改成「**服务器替多个学生集中登录、集中保存会话**」，这使多条原本可接受的行为风险等级显著上升。

| 能力 | 风险点 | 依据 | 建议（做/有条件做/不做） |
| --- | --- | --- | --- |
| **对分易监听 + 自动签到** | 代替学生「到课签到」，伪造出勤，破坏考勤真实性；属学术诚信/校纪问题，服务端批量代签还可能被认定为有组织作弊 | 高校学生管理规定与校纪；stu-mcp 自声明：定位签到默认关闭、真实课堂签到「仍待本人实测」、签到是写操作且「已发出的提交无法撤回」（`checkin.md`、`SECURITY.md`） | **不做**（自动签到）。如只读「签到提醒/课堂活动通知」可另评为有条件做，且绝不代为提交 |
| **对分易定位签到（坐标反推教室）** | `trilateration.py` 用距离预言机反推教室经纬度代签，等于伪造在场位置 | 学术诚信；`location.py`/`trilateration.py` 注释自述「无需教师权限反推坐标」 | **不做** |
| **树洞/狐友他人发言在多租户 App 内展示** | 转载他人匿名发言+昵称+头像链接到自家 App，涉：未经授权转载他人内容、处理他人个人信息（昵称/UID）、可能传播不当内容；平台接口为搜狐内部接口、非稳定授权 API | 《个保法》处理他人个人信息需告知-同意；《民法典》肖像/名誉；著作权转载；`huyou.md` 自述「非稳定第三方 API 承诺」「不代表学校政策」 | **不做 / 至多有条件做**：若做仅限用户本人检索、不落库他人内容、不聚合展示、明确第三方来源与免责；默认倾向不做 |
| **一卡通流水（smartcard）** | 消费流水属金融账户类敏感个人信息；集中保存可反推作息/行踪/宗教饮食等 | **《个保法》第 28 条**（金融账户＝敏感个人信息，需单独同意 + 严格必要）；第 29 条单独同意 | **有条件做**：单独同意、最小化（只存展示必要字段，不存原始流水 HTTP 续行地址）、加密、可一键删除；stu-mcp 已做结果最小化、不缓存，App 侧需同等约束 |
| **一卡通明文 HTTP 在服务端代用户登录** | 官方入口为明文 HTTP，查询密码 + 会话在链路明文传输，服务端集中代登录放大窃听/篡改面 | 《网安法》第 21/22 条网络安全等级保护与传输安全；`smartcard.md`/`SECURITY.md` 自述「明文 HTTP，存在网络暴露风险」 | **有条件做**：告知链路明文风险、单独同意、限固定主机与只读路径、不保存查询密码；否则不做 |
| **课表推导出的行踪轨迹** | 课表+日程+充电位置+一卡通消费可拼出个人行踪轨迹 | 《个保法》第 28 条（行踪轨迹＝敏感个人信息） | **有条件做**：对「可拼出行踪」的组合展示做最小化与单独同意；避免跨维度聚合用户位置 |
| **外卖代查/下单** | 使用第三方平台内部签名接口；若扩展到下单/支付涉资金与代理风险；平台 TOS 限制 | 第三方平台服务条款；`takeout.md` 自述「不下单/不支付/不改地址」「非稳定 API」 | **有条件做（仅只读查询）/ 不做（下单支付）**：维持 stu-mcp 只读边界，绝不触碰交易 |
| **宿舍报修代提交** | 写操作，代学生提交工单；错报/误报影响后勤与他人；需本人确认 + 回执 | 《个保法》告知-同意；`repair.md`/`SECURITY.md`：须完整展示实际表单→本人明确确认→单次提交→只读核对回执，HTTP 提交尚未真实验收 | **有条件做**：必须「完整预览→本人逐次确认→单次提交→回执核对」，不得自动/批量；提交链路与回执未真实验收前仅做「只读报修状态/草稿」 |
| **服务端集中保存学生会话** | 一台机器集中持有大量学生加密 cookie，成为高价值攻击目标；一次泄露波及全体；原设计明确「不防御已控制主机的进程」 | 《数据安全法》《网安法》数据安全义务；`SECURITY.md`：「系统密钥库无法隔离同机恶意软件」「没有声称能防御已控制电脑的进程」；PLAN.md 刻意只存 cookie 不存密码 | **有条件做（本就是架构前提）**：每租户独立密钥与目录、cookie 可吊销、`DELETE /v1/account` 整目录清除、最小化入库、密码绝不落盘；需等保/安全评估 |
| **请求频率 / 学校信息化管理规定** | 服务端集中高频登录/抓取易触发风控，违反学校信息化使用规定 | 学校信息化/网络管理规定；PLAN.md §七 自定限速（每源 10 分钟、jw 并发≤3、1 小时≤3 次登录）；stu-mcp：登录是昂贵操作、`code=2 维护窗口不告警` | **有条件做**：严格限速 + 会话优先 + 维护窗口静默，遵守学校规定；避免被当作爬虫 |
| **教务回调曾走 HTTP、WebVPN TOTP 等凭据处理** | 代登录涉及学校统一账号口令/动态码；明文回调与集中令牌放大风险 | `SECURITY.md`：密码只发 HTTPS SSO、教务 HTTP 回调为受限例外、TOTP 密钥入受保护后端 | **有条件做**：沿用 stu-mcp「密码只发 HTTPS、各来源独立 cookie jar、不复制 SSO cookie」边界 |

> stu-mcp 文档自声明的限制（应一并计入取舍）：多处强调「合成测试 ≠ 真实验收」「历史成功 ≠ 当前在线」「单用户私有实例、无多租户/无公开 OAuth」（`server.md`、`SECURITY.md`）；明确排除：不碰微信私聊/群聊、不扫聊天记录、无第三方模型代理（`SECURITY.md`）。

---

## 4. 多租户适配性（「服务器替多人跑」与 stu-mcp 本机单用户假设的冲突）

stu-mcp 的 `server.md` 明说第一版是「**一人一个实例、一个服务进程**」，`SECURITY.md` 说 Streamable HTTP 是「单用户私有实例……不提供公开 OAuth 或多租户」。App 把它塞进共享服务器，以下能力与其单机假设直接冲突：

| 能力 | 冲突点（一句话影响） |
| --- | --- |
| **雨课堂手机扫码**（official_phone_qr） | 登录需本人手机确认官方二维码，服务器无法代扫——必须把二维码实时推给对应学生手机并等其确认，绑定期要挂起会话，批量并发时每人一条独立等待链。 |
| **对分易微信回调**（wechat_callback） | 授权要本人在微信打开链接再把回调粘回，服务端拿不到微信态——只能逐用户转发授权链并隐藏接收回调，且 stu-checkin 后台原是「本机单实例进程锁」，多租户要每租户隔离后台，与其单实例设计相悖。 |
| **WebVPN TOTP**（webvpn） | 自动重登需长期 TOTP 密钥进「受保护后端」，服务器集中保管多名学生的动态码种子＝高敏凭据集中化，单机 keyring 模型在多租户下失去隔离前提。 |
| **一卡通图片验证码 + 明文 HTTP**（smartcard） | 每次登录要本人看图填验证码，服务器不能代识别——须把验证码图推给学生实时回填；且明文 HTTP 会话集中在服务器，放大链路与集中存储风险。 |
| **教务 HTTP 回调**（jw） | 原为「本机本次一次性许可」的受限回调，多租户下变成服务端常态化代多人走该路线，需逐租户隔离 cookie jar 并严格限定主机，许可语义从「本机一次」被迫变成「服务端长期」。 |
| **对分易/签到后台进程模型** | `stu-checkin` 用 `127.0.0.1` 绑定 + 进程锁 + 单实例复用，设计为一台机器一个人；多租户要么每租户一后台（资源放大），要么重写为多租户调度，原模型不可直接共享。 |
| **`os.environ` 全局切换租户** | tenancy.py 用「每租户锁 + 进程内切换 `STU_MCP_HOME`」串行化，stu-mcp 读的是进程级环境变量，并发天然不安全；PLAN.md 自己也指出生产应「每租户一 worker 进程」。 |
| **报修提交的进程内状态** | 草稿/预览/回执只在进程内存且「不跨重启恢复」，多租户共享进程时需保证不串号、不跨租户复用预览 ID。 |

---

## 5. 结论：候选功能清单（只列，不排序）

标注：`可上首页候选` / `二级` / `不建议做`。最终取舍交给后续 Kano，这里只给一句依据。

**可上首页候选**
- 今日/本周课表（jw 理论课表）——已真实验收、学业刚需、App 已接，首页天然内容。
- 作业/测验 ddl 聚合（mystu + 雨课堂）——ddl 是最高频焦虑点；但雨课堂多租户扫码是接入成本（第 4 节）。
- 成绩更新提醒（jw 成绩）——已真实验收、出分即推送是强需求。
- 考试安排（jw 考试）——已真实验收、与课表同源、低成本。
- 通知聚合（public + OA）——已真实验收、匿名无登录成本，适合做首页信息流。

**二级**
- 一卡通余额/流水（smartcard）——高频但敏感（第 3 节 28 条）+ 明文 HTTP + 多租户验证码，放设置区而非首页。
- 充电桩空位（charging）——已接、已验收，生活类工具，非人人每天用。
- 校车/公交到站（transit）——App 自建但目前 demo 数据，需接真实 provider 才有价值。
- 宿舍报修状态查询（repair 只读部分）——写提交未真实验收，先做只读/草稿较稳。
- 校园网自查 / 服务入口导航（diagnose/get_service_links）——低风险工具类，适合「我的」页。
- 外卖只读查询（takeout）——第三方实验、需签名配置，便民但非核心。
- 图书馆馆藏检索（计划中 library）——匿名可读、无登录成本，但 stu-mcp 尚未接入，需等实现。

**不建议做**
- 对分易自动签到/定位签到（duifene）——学术诚信红线，一句话：代签伪造出勤、定位签到靠反推教室坐标，违背诚信且不可撤回。
- 树洞/狐友他人发言的聚合展示（huyou）——一句话：在自家多租户 App 转载他人匿名发言+个人标识，涉他人个人信息与内容授权，且接口非稳定授权 API。
- 外卖下单/支付/改地址——一句话：触碰资金与代理责任、违反平台条款，stu-mcp 本身也划了只读红线。
- 一卡通充值/挂失/改密——一句话：资金与账户写操作，风险远超便利，stu-mcp 已明确排除。
- 服务端常态化保存学校统一账号明文口令——一句话：PLAN/SECURITY 刻意只存可吊销 cookie、密码用完即弃，集中存口令是不可接受的爆炸半径。

---

### 附：出处索引（stu-mcp v0.7.3 仓库内相对路径）
- 能力总览/认证关系：`docs/campus-services.md`、`src/stu_mcp/capability_catalog.py`、`src/stu_mcp/sources.py`
- 分批接入计划：`docs/feature-expansion-plan.md`
- 安全与隐私边界：`SECURITY.md`、`docs/server.md`、`docs/feedback.md`
- 各功能：`docs/smartcard.md`、`docs/charging.md`（+`charging-platform.md`）、`docs/huyou.md`、`docs/yuketang.md`、`docs/checkin.md`、`docs/repair.md`、`docs/takeout.md`、`docs/network.md`
- 验收记录：`docs/verification.md` 索引；`docs/verification/2026-10-21-http-default.md`、`2026-10-21-webvpn-http.md`、`2026-10-21-http-auth-live.md`、`2026-10-21-takeout.md`、`2026-10-23-academic-timetable.md`、`2026-10-23-v0.7.1.md`、`2026-10-22-oa-entry-boundaries.md`、`2026-10-22-campus-entries.md`
- 50 项事项/逐项覆盖：`docs/investigation/2026-10-23/README.md`、`round2-completion.md`、`business-protocol-ledger.csv`、`service-matters.csv`、`entry-coverage.csv`
- 对分易定位反推：`src/stu_checkin/vendor/duifene/trilateration.py`、`location.py`、`processor.py`
- App 侧：`backend/app/collectors.py`、`sync.py`、`tenancy.py`、`charging.py`、`transit.py`、`bind.py`、`main.py`、`config.py`；`web/src/components/TabBar.tsx`、`web/src/screens/Life.tsx`、`PLAN.md`
