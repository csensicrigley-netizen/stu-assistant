# 01 stu-mcp v0.7.3 数据契约（一手材料）

> 基线：`stu-mcp` tag v0.7.3，commit `31fd2863a41e5e6f2ad13341b85b04e3f4eb2642`（已用 `git rev-parse HEAD` 核对），本地只读源码 `C:\Users\Administrator\AppData\Local\Temp\stu-mcp-v0.7.3`。全程未登录、未联网请求学校系统、未运行需要凭据的命令。
> 文件路径相对仓库根目录 `src/stu_mcp/`（另注明 `tests/`、`docs/` 的除外），行号指该 tag 下的文件。
> 能力地图与验收状态见 `03-capability-map.md`，官方作息（13 小节：1–9、0、A–C，分 5 大节）见 `02-official-sources.md`，本文不重复。
> 合成样例见同目录 `fixtures/`，保持 stu-mcp 原始返回形状。

---

## 1. 51 项工具总表

数量核对：`server.py` 中 `build_server()` 内的 `@tool(...)` 装饰器共 **51 个**，与 `03-capability-map.md` 的"51 个 MCP 工具"一致（逐行扫描 `server.py` 第 84–521 行）。

"读/写"按装饰器参数 `read=` 标注（`server.py:67-80`，`read_only_hint=read`）。`world=True` 表示会访问外部网络（`open_world_hint`）。注意：注解为"写"的工具不一定修改学校数据，很多只是写本地缓存或启动本机进程，见"说明"列。

| # | 工具名 | 来源 | 读/写 | 联网 | 一句话用途 | 定义位置 |
|---|---|---|---|---|---|---|
| 1 | `report_feedback` | 本地 → 宿主配置的反馈接收方 | 写 | 是 | 先本地预览，经用户同意后外发反馈 | server.py:84 |
| 2 | `check_updates` | 官方发行源 | 读 | 是 | 检查 stu-mcp 新版本，不安装 | server.py:104 |
| 3 | `update_self` | 官方发行源 | 写（destructive） | 是 | 下载校验并升级宿主上的 stu-mcp | server.py:111 |
| 4 | `get_capabilities` | 本地 | 读 | 否 | 列出功能、各来源登录状态与缓存时间 | server.py:120 |
| 5 | `search_takeout_shops` | takeout（第三方） | 读 | 是 | 查外卖商家 | server.py:125 |
| 6 | `find_takeout_meal_shops` | takeout | 读 | 是 | 按区域和实际批次查餐段商家 | server.py:139 |
| 7 | `get_takeout_delivery_slots` | takeout | 读 | 是 | 读店铺配送批次与截单时间 | server.py:154 |
| 8 | `get_takeout_shop` | takeout | 读 | 是 | 读商家公开详情 | server.py:165 |
| 9 | `get_takeout_menu` | takeout | 读 | 是 | 读菜单与分类 | server.py:172 |
| 10 | `get_takeout_product` | takeout | 读 | 是 | 读商品规格与标价 | server.py:180 |
| 11 | `prepare_takeout_selection` | takeout | 读 | 是 | 生成选餐清单，不下单 | server.py:188 |
| 12 | `get_checkin_status` | duifene（本机 stu-checkin 后台） | 读 | 否 | 查对分易后台状态 | server.py:196 |
| 13 | `open_checkin_page` | duifene | 写 | 是 | 启动/复用后台并打开网页 | server.py:205 |
| 14 | `list_checkin_courses` | duifene | 写 | 是 | 列出对分易课程（可能启动后台） | server.py:213 |
| 15 | `select_checkin_courses` | duifene | 写 | 是 | 保存监听课程 | server.py:220 |
| 16 | `start_checkin_monitor` | duifene | 写 | 是 | 启动监听，**可能自动提交签到** | server.py:228 |
| 17 | `pause_checkin_monitor` | duifene | 写 | 否 | 暂停监听 | server.py:238 |
| 18 | `get_checkin_results` | duifene | 读 | 否 | 读本机最近 200 条签到活动 | server.py:245 |
| 19 | `stop_checkin_service` | duifene | 写 | 否 | 关闭本机签到后台 | server.py:254 |
| 20 | `open_setup` | 本地 | 写 | 否 | 打开本机设置页登录/配置 | server.py:261 |
| 21 | `refresh_source` | public / oa / jw / mystu / yuketang | 写（写本地缓存） | 是 | 按来源抓取并写入缓存，报告范围与部分失败 | server.py:266 |
| 22 | `search_notices` | 缓存（public / oa） | 读 | 否 | 搜索本地通知缓存 | server.py:271 |
| 23 | `get_notice` | 缓存 + public / oa | 写（refresh 时写缓存） | 是 | 读通知正文与附件列表 | server.py:276 |
| 24 | `read_attachment` | public / oa | 写（注解） | 是 | 提取 PDF/文本附件文字 | server.py:281 |
| 25 | `get_grades` | 缓存（jw） | 读 | 否 | 读成绩缓存 | server.py:286 |
| 26 | `academic_summary` | 缓存（jw） | 读 | 否 | 按缓存成绩算学分加权均分/绩点 | server.py:291 |
| 27 | `get_exams` | 缓存（jw） | 读 | 否 | 读考试安排缓存 | server.py:296 |
| 28 | `get_academic_timetable` | jw（实时） | 读 | 是 | 实时读本人理论课表，不进缓存 | server.py:301 |
| 29 | `get_campus_card` | smartcard（实时） | 读 | 是 | 读一卡通概况、余额、卡状态 | server.py:309 |
| 30 | `get_campus_card_transactions` | smartcard（实时） | 读 | 是 | 按日期读一卡通流水（≤30 天） | server.py:318 |
| 31 | `get_courses` | 缓存（mystu / yuketang） | 读 | 否 | 读课程缓存 | server.py:328 |
| 32 | `get_tasks` | 缓存（mystu / yuketang） | 读 | 否 | 读作业/测验待办与截止时间 | server.py:333 |
| 33 | `get_course_resources` | 缓存（mystu / yuketang） | 读 | 否 | 读课程资料与活动链接 | server.py:338 |
| 34 | `get_schedule` | 缓存（mystu） | 读 | 否 | 读个人日程（过去 7 天至未来 31 天） | server.py:343 |
| 35 | `get_service_links` | 随包目录（public） | 读 | 否 | 查校园服务入口与核验范围 | server.py:348 |
| 36 | `diagnose_campus_network` | 本机网络 | 读 | 是 | 按服务诊断 DNS 与匿名直连 | server.py:358 |
| 37 | `audit_campus_network` | 本机网络 | 读 | 是 | 批量检查已登记学校入口 | server.py:378 |
| 38 | `prepare_repair_draft` | repair（本地） | 写（进程内存） | 否 | 准备宿舍报修草稿 | server.py:391 |
| 39 | `open_repair_form` | repair | 写 | 是 | 把草稿填入学校报修表单，不提交 | server.py:412 |
| 40 | `get_repair_draft` | repair（本地） | 读 | 否 | 读草稿与会话状态 | server.py:423 |
| 41 | `review_repair_draft` | repair | 写（注解） | 是 | 生成绑定实际表单的提交预览 | server.py:434 |
| 42 | `submit_repair_draft` | repair | 写（**修改学校数据**） | 是 | 经本人确认后提交一次报修 | server.py:444 |
| 43 | `check_repair_submission` | repair | 读 | 是 | 只读核对报修回执与处理状态 | server.py:455 |
| 44 | `close_repair_form` | repair（本地） | 写 | 否 | 关闭报修演练窗口 | server.py:466 |
| 45 | `get_charging_overview` | charging（第三方） | 读 | 是 | 按位置查充电设备与空闲统计 | server.py:473 |
| 46 | `get_charging_device` | charging | 读 | 是 | 读单台设备逐口状态 | server.py:483 |
| 47 | `search_huyou_posts` | huyou（第三方） | 写（写本地缓存） | 是 | 搜索狐友圈内公开帖 | server.py:491 |
| 48 | `get_huyou_post` | huyou | 写（写本地缓存） | 是 | 读公开帖正文与评论 | server.py:502 |
| 49 | `search_huyou_circles` | huyou | 读 | 是 | 搜索公开圈子 | server.py:511 |
| 50 | `set_task_status` | 本地 | 写（本机） | 否 | 把本地待办标为 todo/done/ignored | server.py:516 |
| 51 | `set_student_profile` | 本地 | 写（本机） | 否 | 保存学院/专业/年级/兴趣 | server.py:521 |

两种调用模式（对 App 设计影响很大）：

- **缓存查询**：`get_grades`、`get_exams`、`get_courses`、`get_tasks`、`get_course_resources`、`get_schedule`、`search_notices`、`academic_summary` 只读本地 SQLite 缓存，数据要先由 `refresh_source` 写入（`app.py:76-161`）。返回的 `cached` 恒为 `true`（`store.py:189-196`）。
- **实时直读**：`get_academic_timetable`、`get_campus_card`、`get_campus_card_transactions`、`get_charging_*` 每次实时请求，不写记录缓存（理论课表 `cached:false`，`features/jw/service.py:56-58`）。

---

## 2. 字段表

通用约定（所有小节适用）：

- 记录 ID 统一为 `来源:类型:24位十六进制`，是若干身份字段的 SHA-256 截断（`parsing.py:18-20`）。ID 只能用来对齐同一条记录，不能反解出内容。
- 字符串都经过 `clean()` 把连续空白压成一个空格（`parsing.py:14-15`）。教务字段为空时是 `""`，不是 `null`。
- 时区：stu-mcp 自己生成的时间（`checked_at`、`collected_at`、`queried_at`）都是 **UTC**，形如 `2026-10-28T02:03:04.123456+00:00`。从学校时间戳换算出来的时间（MySTU、雨课堂、一卡通流水）都是 **+08:00**（`parsing.py:11,28`；`features/smartcard/parser.py:238`）。前端必须按带偏移的 ISO 解析，不能当本地时间直接截字符串。

### 2.1 理论课表 `get_academic_timetable`

**直接回答：节次返回的是"小节编号的整数列表"，不是"第四大节"这类大节标签，也不是"8-9节""8-0节""A-C节"这类小节范围字符串。**

- 每个 meeting 的 `periods` 是一段连续的小节整数，例如 `[3, 4, 5]`。它从学校原文 `raw_time` 的 `[03-04-05节]` 解析而来（`features/jw/timetable.py:148-178`）。
- 学校原文里方括号中的连字符是**逐个列举**，不是区间：`[03-04-05节]` 表示 3、4、5 三个小节；`[01-03节]` 表示 1 和 3 两个小节，会被拆成两个 meeting（`tests/test_jw_timetable.py:88-92`）。这和周次里的连字符（`3-18(周)` 是区间）含义相反。
- 大节信息只存在于学校网格的行标题里，形如 `第一大节 (01,02,03小节)`。解析器只用它来核对小节是否落在这一行内（`timetable.py:262-270`、`286-287`），**不把大节标签输出给调用方**。
- 解析只接受数字小节，且每个小节必须在 1–30 之间（`timetable.py:152,169-171`）。所以学校原文如果写成 `0`、`A`、`B`、`C`，整张课表会报 `schema_changed` 失败，不会部分返回。源码和测试都**没有**定义 0、A、B、C 对应哪个整数。2026-10-24 的真实读取成功了（8 门课、14 组安排，见 `docs/verification/2026-10-23-academic-timetable.md`），说明那次的数据全是数字小节。但那次有没有晚课或第 0 节，没有记录。推断教务把 0、A、B、C 编成 10、11、12、13（需要真实样本核对）。前端应把整数映射到 `02-official-sources.md` §2 的 13 小节（1–9、0、A–C）后再显示时刻。
- 跨大节连排（例如 6–9 节）：因为每个 meeting 的小节必须落在同一网格行内，学校只能把它放在两行里，stu-mcp 会返回**两个 meeting**（`[6,7]` 和 `[8,9]`）。如果学校在一格里写 `[06-07-08-09节]`，整张课表会失败（`timetable.py:286-287`）。前端如果要显示成连续一块，需要自己合并相邻的 meeting。

**顶层信封**（`features/jw/service.py:56-58`；`timetable.py:330-338`）

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `ok` | bool | 否 | 固定为 `true`。失败时返回统一错误结构，见 §2.10 | jw/service.py:56 |
| `status` | `"ok"` \| `"partial"` | 否 | `pending` 非空时为 `partial` | jw/service.py:56 |
| `source` | `"jw"` | 否 | 来源 | jw/service.py:56 |
| `source_url` | string | 否 | 固定为 `https://jw.stu.edu.cn/jsxsd/xskb/xskb_list.do` | jw/service.py:57；jw/schema.py:13 |
| `checked_at` | string（UTC ISO） | 否 | 本次读取完成的时间 | jw/service.py:57 |
| `transport` | `"https"` \| `"school_legacy_http"` | 否 | 是否走了教务 HTTP 兼容回调 | jw/adapter.py:33-35 |
| `cached` | bool | 否 | 固定为 `false`，结果不进缓存 | jw/service.py:58 |
| `content_trust` | `"untrusted_source_data"` | 否 | 学校原文，不可信内容 | jw/service.py:58 |
| `semester` | string | 否 | 例如 `2026-2027-1`，格式为 `20\d{2}-20\d{2}-[123]` | timetable.py:330；jw/schema.py:24 |
| `available_semesters` | `[{semester, label}]` | 否（至少 1 项，为空时整张课表报 `schema_changed`） | 学校表单里真实的学期选项，`label` 是学校原文 | timetable.py:89-93 |
| `courses` | array | 否 | 已排课课程，见下表 | timetable.py:331 |
| `unarranged` | array | 否 | 学校"无课表课程"区的课程，**没有 meetings** | timetable.py:332 |
| `unplaced_details` | array | 否 | 课程明细里有、网格里找不到的教师安排 | timetable.py:310-311 |
| `pending` | string[] | 否 | 待核实提示，最多两条固定文案 | timetable.py:307-313 |
| `coverage` | object | 否 | 读取范围，见下表 | timetable.py:334-338 |

**`courses[]`**（`timetable.py:235-241, 324-327`）

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `id` | string | 否 | `jw:timetable:…`，由学期、课程编号、**教学班**算出。同名不同班的课 ID 不同 | timetable.py:235 |
| `title` | string | 否 | 课程名称，学校原文，长度不限（单字段上限 2000 字） | timetable.py:107-111 |
| `course_code` | string | 否 | 课程编号 | timetable.py:236 |
| `class_name` | string | 否 | 上课班级（教学班） | timetable.py:236 |
| `teachers` | string[] | 否 | 课程明细里每行一位教师，排序后去重 | timetable.py:240-241, 325 |
| `teacher` | string | 否 | `teachers` 用"、"连接，可能为 `""` | timetable.py:326 |
| `meetings` | array | 否（至少 1 项） | 每组上课安排 | timetable.py:298 |

**`courses[].meetings[]`**（`timetable.py:296-301`）

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `id` | string | 否 | `jw:meeting:…`，由课程、通知单编号、星期、小节、周次集合算出。换老师或换教室，ID 不变；改周次，ID 会变 | timetable.py:290-294 |
| `weekday` | int 1–7 | 否 | 1 为星期一，7 为星期日 | timetable.py:251 |
| `weeks` | int[] | 否（至少 1 项） | 展开并排序后的校历周次。单双周已经过滤好 | timetable.py:155-166 |
| `periods` | int[] | 否 | **连续的一段小节编号**，见本节开头 | timetable.py:167-178 |
| `teacher` | string | 否 | 这一组安排的教师，学校原文 | timetable.py:299 |
| `location` | string | 否 | `room`，或 `building + " " + room`（room 不以楼名开头时） | timetable.py:288-289 |
| `building` | string | 否（可为 `""`） | 教学楼原文 | timetable.py:288 |
| `room` | string | 否（可为 `""`） | 教室原文 | timetable.py:288 |
| `raw_time` | string | 否 | 学校原文，例如 `3-18(周)[03-04-05节]` | timetable.py:300 |
| `notes` | string | 否（可为 `""`） | 备注原文（已去掉"备注："前缀） | timetable.py:301 |
| `online_info` | string | 否（可为 `""`） | 网络授课信息原文 | timetable.py:208-212, 301 |

周次原文的写法（`timetable.py:148-166`，测试见 `tests/test_jw_timetable.py:87-95`）：

| 学校原文 | `weeks` | `periods` 分组 |
|---|---|---|
| `3-18(周)[03-04-05节]` | 3…18 | `[[3,4,5]]` |
| `3,5,7,9(周)[08-09节]` | `[3,5,7,9]` | `[[8,9]]` |
| `1-7(单周)[01-02节]` | `[1,3,5,7]` | `[[1,2]]` |
| `1-8(周)(双)[01-02节]` | `[2,4,6,8]` | `[[1,2]]` |
| `1、3、5（周）［01-03节］`（全角符号） | `[1,3,5]` | `[[1],[3]]`，拆成两个 meeting |
| `待定`、`2(单周)[01节]`（过滤后为空）、`[31节]`、周次超过 60 | — | 整张课表报 `schema_changed` |

**`unarranged[]`**（`timetable.py:315-323, 328-329`）：`id`、`title`、`course_code`、`class_name`、`teachers`（string[]）。没有 `meetings`，也没有 `teacher` 字符串。

**`unplaced_details[]`**（`timetable.py:310-311`）：`course_id`、`class_name`、`title`、`teacher`。

**`coverage`**（`timetable.py:334-338`）

| 字段 | 类型 | 含义 |
|---|---|---|
| `kind` | `"theory"` | 只含理论课，不含实验课 |
| `weeks` | `"all"` | 全部周次 |
| `complete` | bool | `= unplaced_details 为空`。**未排课不影响它**：有未排课时可以是 `complete=true` 但 `status=partial` |
| `course_count` / `meeting_count` / `grid_arrangements` | int | 课程数 / meeting 数 / 网格里的安排块数 |
| `unarranged_count` / `unplaced_detail_count` | int | 未排课数 / 未对应明细数 |
| `calendar_verified` | `false`（固定） | 没有核对校历 |
| `period_times_verified` | `false`（固定） | **没有核对节次时刻** |
| `experimental_timetable_included` | `false`（固定） | 不含实验课 |

空学期和失败的区别：一个正常的空学期返回 `ok=true`、`status="ok"`，`courses`、`unarranged`、`pending` 都是 `[]`，`coverage.complete=true`（`tests/test_jw_timetable.py:238-248`；2026-10-24 真实读取 `2025-2026-3`，见验收记录）。登录页返回 `login_expired`，维护页或未知页面返回 `schema_changed`，不会被当成空课表（`timetable.py:24-26, 303-304`）。

### 2.2 考试 `get_exams`（缓存查询）

字段来自学校表头的同义词映射（`features/jw/schema.py:36-42`）。**只有表格里真的有的列才会出现在记录里**，缺列时 key 不存在，不是 `null`（`features/jw/parser.py:175`）。必需列只有 `course_code`、`title`、`exam_time`、`location`（`jw/schema.py:44-47`）。

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `id` | string | 否 | `jw:exam:…`，由学期、课程编号、考试时间原文算出 | jw/parser.py:185 |
| `kind` | `"exam"` | 否 | — | jw/parser.py:186 |
| `semester` | string | 否 | 查询用的学期。留空刷新时取学校选中的考试学期 | jw/parser.py:186；jw/service.py:27 |
| `course_code` / `title` | string | 否 | 课程编号 / 课程名称 | jw/schema.py:39 |
| `exam_time` | string | **可为 `""`** | **学校原文，不解析**。合成样例为 `2026-11-01 09:00-11:00`。未公布时为 `""` | tests/fixtures/exams-current.html；tests/test_jw_parser.py:313-319 |
| `location` | string | **可为 `""`** | 考场原文 | jw/schema.py:40 |
| `campus` / `exam_campus` / `exam_session` | string | key 可能不存在 | 校区 / 考试校区 / 考试场次原文 | jw/schema.py:37-38 |
| `teacher` | string | key 可能不存在 | 授课教师 | jw/schema.py:39 |
| `seat_number` / `exam_id` | string | key 可能不存在 | 座位号 / 准考证号 | jw/schema.py:41 |
| `notes` | string | key 可能不存在 | 备注原文 | jw/schema.py:41 |
| `jw_schema_version` | int（当前为 2） | 否 | 版本不符的旧缓存不会列出，计入 `unverified_count` | jw/parser.py:178；store.py:174-176 |
| `source` / `collected_at` | `"jw"` / UTC ISO | 否 | 写入缓存时加上 | store.py:72 |

- 同一门课可以有多场不同时间的考试。同一门课、同一时间出现两条时，整页报 `schema_changed`（`tests/test_jw_parser.py:291-300`）。
- 考试读取失败不影响成绩写入：`refresh_source(jw)` 会返回 `errors[]`，其中 `part="考试安排"`（`jw/service.py:26-34`）。

### 2.3 成绩 `get_grades` / `academic_summary`（缓存查询）

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `id` | string | 否 | `jw:grade:…`，由学期、课程编号、考试性质、分组算出。补考和正常考试是两条记录 | jw/parser.py:182-183 |
| `semester` | string | 否 | 开课学期原文，例如 `2026-2027-1` | jw/schema.py:28 |
| `course_code` / `title` | string | 否 | — | jw/schema.py:28 |
| `score` | **string** | 否（可为 `""`） | 成绩原文。百分制是 `"90"`，等级制是 `"优秀"` 等，**不转成数字** | jw/schema.py:28；tests/fixtures/grades-current.html |
| `credits` | number \| null | 是 | 学分。非数字（`""`、`待公布`、`NaN`）时为 `null` | jw/parser.py:180-181；tests/test_jw_parser.py:302-310 |
| `gpa` | number \| null | 是 | 绩点。非数字时为 `null` | 同上 |
| `score_marker` | string | key 可能不存在 | 成绩标识原文，例如 `正常`、`等级制` | jw/schema.py:29 |
| `hours` | string | key 可能不存在 | 学时，**保持字符串** | jw/schema.py:29；tests/test_jw_current.py:45 |
| `retake_semester` | string | key 可能不存在 | 补重学期原文，没有时为 `""` | jw/schema.py:30 |
| `exam_method` | string | key 可能不存在 | 考核方式，例如 `考试`、`考查` | jw/schema.py:31 |
| `exam_type` | string | key 可能不存在 | 考试性质，例如 `正常考试`、`补考` | jw/schema.py:31 |
| `course_attribute` / `course_nature` / `category` / `group` | string | key 可能不存在 | 课程属性 / 性质 / 通选课类别 / 分组名称 | jw/schema.py:32-33 |
| `kind` / `jw_schema_version` / `source` / `collected_at` | — | 否 | 同 §2.2 | — |

- **没有独立的"重修""缓考""未出成绩"布尔字段。**这些信息只能从 `exam_type`、`score_marker`、`retake_semester`、`score` 的学校原文里看出来（字段集见 `jw/schema.py:27-34`）。源码里没有"缓考"这个枚举值，前端不能自己定义枚举，只能原样显示。
- 刷新时每次最多读 200 行（`JW_GRADE_ROW_LIMIT`，`jw/schema.py:6`；`jw/service.py:22-24`）。

**`academic_summary` 返回**（`app.py:334-361`）

| 字段 | 类型 | 含义 |
|---|---|---|
| `ok` / `cached` | `true` / `true` | — |
| `semester` | string | 传入的学期，或 `"all"` |
| `course_count` | int | 参与统计的成绩条数（包括非数字成绩） |
| `weighted_score` / `weighted_gpa` | `{value, included_count, included_credits}` | `value` 保留 4 位小数，没有可计算项时为 `null` |
| `method` | string | 固定口径说明：按学分加权；非数字成绩排除；**重修和补考逐条计入**；不是学校官方认定 |
| `freshness` / `scan_capped` / `unverified_count` | — | 见 §2.11 |

### 2.4 MySTU / 雨课堂：课程、任务、资料、日程（缓存查询）

`get_courses`、`get_tasks`、`get_course_resources`、`get_schedule` 的返回都包在缓存列表信封里（§2.11）。

**课程 `kind="course"`**

| 字段 | MySTU（`features/mystu/service.py:32-34`） | 雨课堂（`features/yuketang/service.py:27-31`） |
|---|---|---|
| `id` | `mystu:course:…` | `yuketang:course:…` |
| `title` | 课程名 | 课程名 |
| `url` | string \| null | string（课程日志页） |
| `course_id` | 来源课程 ID | 数字字符串 |
| `semester` | **`"2026-1"`**（年-学期，不是教务的 `2026-2027-1`）或 null | **`str(term)`**，例如 `"20261"` 或 null |
| `year` / `term` | int / int | 只有 `term`（int 或 string） |
| `teacher` | string，可为 `""` | string，可为 `""` |

出处：`mystu/parser.py:149-150`；`tests/test_mystu_service.py:181`；`tests/test_course_sync.py:257`。三个来源的学期写法不同，前端不能拿来互相比较。

**任务/资料 `kind="task"|"resource"`，MySTU**（`mystu/service.py:44-70`）

| 字段 | 类型 | 可空 | 含义 |
|---|---|---|---|
| `id` | string | 否 | `mystu:task:…` / `mystu:resource:…` |
| `title` | string | 否 | 活动标题原文 |
| `course` / `course_id` / `semester` / `year` / `term` | — | — | 所属课程 |
| `url` | string \| null | 是 | 活动链接 |
| `category` | string | 否 | `assignment`、`assign`、`quiz` 归为 task（`mystu/schema.py:26`），其余为 resource |
| `due_at` | string \| null | 是 | 截止时间，见下方说明 |
| `school_status` | string \| null | 是 | 学校页面的"提交状态"原文 |
| `status` | `"todo"` \| `"done"` \| `"ignored"` | 否 | **本地状态**，默认为 `todo`，可用 `set_task_status` 改写（`store.py:186-187`）。不是学校状态 |
| `body` | string | 否（可为 `""`） | 说明，最长 6000 字 |
| `detail_status` | `"not_read"` \| `"read"` \| `"unavailable"` \| `"limit_reached"` | 只有 task 有 | 是否读到了详情页。每次刷新最多读 20 个详情（`DETAIL_LIMIT`） |
| `detail_error` | 错误对象 | 只在 `unavailable` 时有 | 统一错误结构 |
| `due_at_status` | `"available"` \| `"not_found_in_detail"` \| `"detail_not_read"` \| `"detail_unavailable"` \| `"detail_limit_reached"` | 只有 task 有 | 截止时间缺失的原因 |

**雨课堂任务/资料**（`yuketang/parser.py:178-184`）：`id`、`kind`、`title`、`course`、`url`、`course_id`、`semester`、`term`、`activity_type`（int，3 和 19 为 task）、`leaf_id`、`due_at`（+08:00 或 null）、`status`（`"todo"`）、`published_at`（+08:00 或 null）。雨课堂课程公告是 `kind="notice"`，会出现在 `search_notices` 里（`yuketang/parser.py:205-208`）。

**日程 `kind="event"`，`get_schedule`**（`mystu/parser.py:263-277`）：`id`、`title`、`start_at`、`end_at`（都是 +08:00 ISO 或 null）、`location`（string，可为 `""`）。没有全天标记，也没有所属课程字段。

时间格式：

- 来自时间戳的 `due_at`、`start_at`、`end_at`、`published_at` 由 `stamp()` 生成，形如 `2026-10-30T23:59:00+08:00`。毫秒时间戳有小数时，会带微秒，例如 `…T23:59:00.123000+08:00`；`≤0` 时为 null（`parsing.py:23-30`）。
- **MySTU 详情页解析出的 `due_at` 可能不是 ISO。**`parse_date()` 认不出格式时，会原样返回清理后的学校文字（`parsing.py:33-48`）。前端必须先尝试按 ISO 解析，失败就原样显示。
- "已过期""今天截止"都**不由 stu-mcp 计算**，需要前端拿 `due_at` 和当前时间（Asia/Shanghai）比较。

完整性标记：记录本身**没有** `complete` 字段。完整性在 `refresh_source` 的返回里：

- `status` 为 `"partial"`，或 `limited=true`
- `coverage.inventory_complete`：课程清单是否完整
- `coverage.course_snapshot_complete`：课程快照是否完整
- `coverage.complete_activity_courses`：活动读全的课程数
- `errors[]`：每项带 `part`、`stage`、`course_id`、`item_id`

出处：`mystu/service.py:15-27, 76-82`；`collection.py:20-31`。缓存查询时还会返回 `scopes[]`、`unverified_count`、`scope_message`（`store.py:189-196`；`app.py:153-156`）。日程窗口固定为过去 7 天到未来 31 天，最多 50 条，超出时 `limited=true`（`mystu/adapter.py:65-69`；`mystu/schema.py:28`；`mystu/parser.py:277`）。

### 2.5 通知 `refresh_source(public|oa)` / `search_notices` / `get_notice` / `read_attachment`

**列表记录 `kind="notice"`**

| 字段 | 类型 | 可空 | 含义 | 出处 |
|---|---|---|---|---|
| `id` | string | 否 | public 由 URL 算出；oa 由 `docid` 算出 | notices/parser.py:57, 165 |
| `title` | string | 否 | 标题原文，**不截断**（雨课堂公告截到 200 字） | notices/parser.py:53-54；yuketang/parser.py:207 |
| `url` | string | 否 | 原文链接 | notices/parser.py:58, 166 |
| `category` | string | 只有 public 有 | `学校要闻` / `综合新闻` / `活动预告` / `学生通知` | sources.py:42-47 |
| `department` | string | 只有 oa 有，可为 `""` | 发布部门原文 | notices/parser.py:166 |
| `docid` | string | 只有 oa 有 | OA 文档 ID | notices/parser.py:165 |
| `published_at` | string \| null | 是 | **只是正则截出的日期原文**，可能是 `2026-10-26`、`2026/10/26`、`2026.10.26`、`2026年10月26日`，**没有时分，也不是 ISO**。截不到时为 null | parsing.py:73-75 |
| `source` / `collected_at` | — | 否 | 写入缓存时加上 | store.py:72 |

**正文与附件**（`get_notice(item_id, refresh=true)` 之后，`notices/parser.py:222-252`；`store.py:73-86`）

| 字段 | 类型 | 含义 |
|---|---|---|
| `body` | string | 纯文本正文，最长 60000 字（`parsing.py:65-70`）。只有附件、没有正文时可为 `""`（`notices/parser.py:216, 239`） |
| `attachments` | `[{name, url}]` | 最多 30 个。**OA 的 url 是绝对地址**（`…/weaver/weaver.file.FileDownload?fileid=…`）；**public 的 url 是页面里的原始 href，可能是相对路径**（`notices/parser.py:249`） |
| `detail_collected_at` | UTC ISO | 正文抓取时间，和列表的 `collected_at` 分开记录 |

- `get_notice` 返回 `{ok, item, cached: !refresh, content_trust}`（`app.py:262-275`）。
- `read_attachment` 返回 `{ok, name, text, url, content_trust, truncated}`，`text` 最长 60000 字（`app.py:277-317`）。
- **没有逐条的"需要 WebVPN"布尔字段。**判断方法：OA 记录的 `url` 如果是 `https://oa-stu-edu-cn.webvpn.stu.edu.cn/…`，读正文和附件就要用 WebVPN 会话（`features/notices/adapter.py:45-46`；`app.py:269, 287-288`）。如果是 `http://oa.stu.edu.cn/…`，就是匿名直读。这一次 OA 列表走的是哪条路，看 `refresh_source(oa)` 返回的 `coverage.access`：`"anonymous_http"` 或 `"authenticated_https"`（`notices/service.py:31-40`）。`get_capabilities` 里 oa 的 `access` 为 `"anonymous_first"`（`app.py:57`）。

### 2.6 一卡通 `get_campus_card` / `get_campus_card_transactions`（实时）

**信封**（`features/smartcard/service.py:23-25`）：`ok`、`status`（`pending` 非空时为 `partial`）、`source="smartcard"`、`source_url`、`checked_at`（UTC）、`transport="school_http"`（官方入口是明文 HTTP）、`cached=false`、`content_trust`。

**概况**（`smartcard/parser.py:83-112`；测试见 `tests/test_smartcard_reads.py:33, 70-71`）

| 字段 | 类型 | 可空 | 含义 |
|---|---|---|---|
| `balance` | **string，单位为元，保留两位小数** | 否 | 卡余额，例如 `"18.50"`。负数时带 `-`。由 `money()` 用 Decimal 格式化（`smartcard/parser.py:54-65`） |
| `currency` | `"CNY"` | 否 | 固定值 |
| `balance_breakdown` | `{card_balance, current_transition_balance, previous_transition_balance}` | key 可能不存在 | 只有学校原文同时带三种标签时才出现，三者**不相加**（`smartcard/parser.py:68-85`） |
| `card_status` | string \| null | 是 | 卡状态原文，例如 `正常` |
| `freeze_status` | string \| null | 是 | 冻结状态原文，例如 `未冻结` |
| `check_status` | string \| null | 是 | 检查状态原文 |
| `loss_status` | string \| null | 是 | 挂失状态原文，例如 `未挂失` |

状态字段缺失时为 `null`，表示未知，不代表正常（`smartcard/parser.py:109`）。学号和姓名**不返回**（`smartcard/parser.py:106, 111`）。

**流水**（`smartcard/parser.py:309-321`；`smartcard/adapter.py:75-77`）

| 字段 | 类型 | 含义 |
|---|---|---|
| `items[].occurred_at` | string | `YYYY-MM-DDTHH:MM:SS+08:00`（`smartcard/parser.py:230-238`） |
| `items[].type` | string | 交易类型原文，例如 `扣款`、`消费`、`补助`、`转账` |
| `items[].subsystem` / `category` | string | 子系统名称 / 类别原文 |
| `items[].amount` | string（元） | 例如 `"-1.50"`，**保留原始正负号** |
| `items[].balance` | string（元） | 交易后余额 |
| `items[].sequence` | string（纯数字） | 次数 |
| `items[].status` | string | 状态原文，例如 `成功` |
| `coverage` | `{complete, pages_read: 1, total_pages, total_records, rows_read}` | `complete = total_pages <= 1`。多页时**只读第一页**，`complete=false`，并在 `pending` 里给出提示 |
| `pending` | string[] | 多页时有一条提示 |
| `query` | `{start_date, end_date, transaction_type}` | 实际查询条件 |
| `currency` | `"CNY"` | — |
| `available_transaction_types` | `[{value, label}]` | 学校的类型选项 |

- 学校表格里的姓名和操作员列会被**丢弃**（`smartcard/parser.py:309`）。
- 日期范围必须是同一天到 30 天以内，否则报 `invalid_date_range`（`smartcard/parser.py:217-227`）。
- 单页最多 200 行，超出报 `response_too_large`（`smartcard/schema.py:11`；`smartcard/parser.py:312-313`）。

### 2.7 充电 `get_charging_overview` / `get_charging_device`（实时，第三方）

**插座状态枚举**（`features/charging/schema.py:4-5`）

| `state` | `label` | 计入 |
|---|---|---|
| 1 | 空闲 | `free` |
| 2 | 占用 | `occupied` |
| 0 | 粘连故障 | `fault` |
| 3 | 开路故障 | `fault` |
| 4 | 设备急停 | `fault` |
| 5 | 过温保护 | `fault` |
| 6 | 烟感报警 | `fault` |
| 7 | 过压故障 | `fault` |
| 8 | 欠压故障 | `fault` |
| 其他非负整数 | `未知状态` | `unknown` |

出处：`features/charging/parser.py:262-279`。

**单台设备视图**（overview 的 `items[]`，或 detail 的 `device`；`features/charging/service.py:27-44`；`charging_devices.py:18-21`）

| 字段 | 类型 | 可空 | 含义 |
|---|---|---|---|
| `device_code` / `name` / `location` | string | 否 | 来自随包清单（14 台，2026-10-16 核验） |
| `expected_port_count` | int | 否 | 清单登记的口数，默认 20 |
| `registry_verified_on` | `"2026-10-16"` | 否 | 清单核验日期 |
| `source_url` | string | 否 | 第三方设备页 |
| `device_type` | int \| null | 是 | 平台设备类型 |
| `ports` | `[{num, state, label}]` \| null | 是 | **只有 detail 返回**；overview 会去掉 `ports`（`service.py:103`）。没有成功样本时为 null |
| `ports_complete` | bool | 否 | 拿到的口数是否等于登记口数 |
| `counts` | `{total, observed, free, occupied, fault, unknown}` \| null | 是 | 没有成功样本时为 null |
| `collected_at` | UTC ISO \| null | 是 | **最后一次成功采样的时间**。失败时不会被覆盖 |
| `last_attempt_at` | UTC ISO \| null | 是 | 最后一次尝试的时间 |
| `status` | `"ok"` \| `"partial"` \| `"stale"` \| `"not_cached"` \| 错误码 | 否 | 有 `unknown` 口时为 `partial`；超过 120 秒为 `stale`；最近一次失败时为错误码，例如 `device_offline` |
| `online` | `false` \| null | 是 | **只有 `false`（平台报告设备不在线）和 `null`（未知）两种值，不会是 `true`** |
| `stale` | bool | 否 | 没有数据、最近一次失败、或采样时间超过 120 秒时为 true |
| `cache_age_seconds` | int \| null | 是 | 距离 `collected_at` 的秒数 |
| `cached` | bool | 否 | 这次是否没有新抓取 |
| `retained_from_cache` | bool | 否 | 有旧数据，但本次失败 |
| `error` | 错误对象 \| null | 是 | 统一错误结构 |
| `retry_after_seconds` | int | key 可能不存在 | 冷却中时出现 |

**overview 信封**（`service.py:78-114`）：

- `ok`：**没有任何数据时可能为 `false`**，这时不走统一错误结构，而是 `status="unavailable"`
- `status`：`ok` / `partial` / `unavailable`
- 固定字段：`source="charging"`、`official=false`、`provider="isfdz"`
- 时间与缓存：`queried_at`（UTC）、`stale`（只要有一台 stale 就为 true）、`cached`、`cache_ttl_seconds=60`、`stale_after_seconds=120`
- `errors[]`：每项带 `device_code`
- `items[]`
- `counts`：只汇总**非 stale** 设备，stale 或没有数据的设备的登记口数全部计入 `unknown`；如果没有一台是新鲜的，`counts=null`
- 分页：`location`、`total_count`、`offset`、`has_more`、`next_offset`
- `coverage`：`catalog_device_count`、`catalog_verified_on`、`inventory_complete=null`、`counts_scope="returned_devices_only"`、`counts_fresh_only=true`、`scope` 文案
- `message`

detail 信封为同样的公共字段，再加上 `device`（`service.py:116-121`）。刷新节流为同一设备 60 秒内不重抓（`charging_cache.py:11-12, 36-58`）。

### 2.8 报修（只读部分）`get_repair_draft` / `check_repair_submission`

App 只建议读这两个只读工具（见 `03-capability-map.md` §1.4）。草稿只存在 stu-mcp 进程内存里，进程重启就没了（`features/repair/service.py:103`，`storage="process_memory_only"`）。

| 字段 | 类型 | 含义 | 出处 |
|---|---|---|---|
| `draft_id` | string（32 位十六进制） | 草稿 ID | repair/service.py:72-73 |
| `status` | string 枚举 | 见下方说明 | repair/service.py:77-302；repair/submission.py:65-106 |
| `window_status` | `open` / `closing` / `closed` | 报修窗口状态 | repair/service.py:124, 274-318 |
| `preview` | object | 草稿字段，电话已打码（`138****1234` 这种形式） | repair/service.py:97-99 |
| `problems` / `missing_page_fields` / `field_matches` | array / array / object | 缺项与回读核对结果 | repair/service.py:77-78, 302 |
| `checked_at` | **number（Unix 秒，`time.time()`）\| null** | 回读核对时间。**不是 ISO 字符串** | repair/service.py:252, 302 |
| `review_id` / `review_expires_at` | string \| null | 预览 ID，有效期 5 分钟 | server.py:438 |
| `request_sent` / `submitted_by_mcp` | bool | 是否已经发出提交请求 / 是否确认由本程序提交成功 | repair/submission.py:99-106 |
| `receipt` | object \| 缺失 | 回执，含 `order_id`、`processing_status`、`detail_url`、`checked_at`。**内部形状只能从 docstring 推断，需要真实样本核对** | server.py:459；repair/submission.py:100 |
| `receipt_fresh` | bool | 回执是不是本次刚核对的 | repair/submission.py:100, 106 |
| `error` | 错误对象 \| null | — | repair/service.py:277-287 |
| `system` / `entry_url` / `form_url` / `form_verified_on` / `building_options` / `confirmation_required` / `storage` / `login_session_saved` / `message` | — | 固定的元信息 | repair/service.py:100-104 |

`status` 的取值：

- 填写阶段：`needs_information`、`draft_ready`、`starting`、`opening_form`、`filling`、`filled_unsubmitted`、`needs_manual_fields`、`form_changed`
- 确认阶段：`awaiting_confirmation`、`confirmation_invalidated`、`confirmation_expired`
- 提交阶段：`submitting`、`submitted`、`submission_unknown`、`submission_not_sent`
- 结束或异常：`preview_expired`、`repair_session_error`、`login_timeout`、`closed`

### 2.9 本地待办 / 画像 / 偏好

| 工具或字段 | 返回或形状 | 说明 | 出处 |
|---|---|---|---|
| `set_task_status(item_id, status)` | `{ok, id, status}` | `status` 只能是 `todo`/`done`/`ignored`，并且只对 `kind=task` 有效，否则返回 `invalid_task`。ID 不存在返回 `not_cached` | store.py:205-210, 198-202 |
| 覆盖规则 | `get_tasks` 返回的 `status` 会被本地覆盖值替换 | 课程范围变化导致任务被移除时，覆盖值也一起删除 | store.py:186-187, 96-99 |
| `set_student_profile(...)` | `{ok, profile: {college, major, entry_year, interests}}` | 全是字符串，序列化后不超过 4000 字 | server.py:521-525；runtime.py:186-196 |
| `get_capabilities().profile` | 同上 | — | app.py:61 |
| `get_capabilities().transport` | `{jw_http_compat: bool, oa_http_compat?: bool}` | **没有 MCP 工具能写它**，只能在 CLI 里改 | runtime.py:198-207；cli.py:262 |

### 2.10 统一错误结构与错误码

**结构**（`runtime.py:15-76`）。所有工具抛出的 `AppError` 都由装饰器转成这个结构返回（`server.py:72-78`）：

```json
{
  "ok": false,
  "status": "<错误码>",
  "message": "中文说明，可直接展示",
  "source": "jw | mystu | … | null",
  "retryable": false,
  "next_action": "open_setup | wait_then_retry | …",
  "http_status": 429,
  "retry_after_seconds": 60,
  "network": { "failure_layer": "dns | tls | …" }
}
```

- 最后三个字段只在有值时出现（`runtime.py:66-76`）。
- `retryable=true` 只有这 6 个码：`rate_limited`、`source_unavailable`、`network_error`、`request_timeout`、`refresh_running`、`media_unavailable`（`runtime.py:62-63`）。
- `network.failure_layer` 为 `dns`、`tls`、`tls_verification`、`connect_or_tls` 时，会强制 `retryable=false`（`runtime.py:71-76`）。
- 依赖库抛出的非 `AppError` 异常，一律返回 `operation_failed`，不输出认证信息（`server.py:76-78`）。
- 部分失败**不会**整体返回 `ok=false`，而是放进成功结果里：
  - `refresh_source` 的 `errors[]`，每项是 `{part, stage?, course_id?, item_id?, ...错误结构}`（`collection.py:20-26`）
  - 充电的 `errors[]`，每项带 `device_code`（`features/charging/service.py:88`）
  - MySTU 任务的 `detail_error`（`mystu/service.py:56`）

**错误码**：源码里以字面量出现的 `AppError` 码共 **205 个**（扫描 `src/stu_mcp/**/*.py`）。另有 `app.py:98, 150` 两处会把登录状态（`needs_login`、`login_expired`、`session_invalid`、`secure_storage_unavailable` 等）直接当作错误码返回。下表只列学生 App 会遇到的部分：

| 类别 | 错误码 | 含义 | `next_action` | 位置（首个） |
|---|---|---|---|---|
| 登录 | `needs_login` | 没有保存的会话 | `open_setup` | vault.py:54；network.py:237 |
| 登录 | `login_expired` | 会话过期，或返回了登录页 | `open_setup` | network.py:237, 263, 297；jw/timetable.py:26；smartcard/parser.py:32 |
| 登录 | `session_invalid` | 会话损坏，或身份和本人不符 | `open_setup` | vault.py:74, 112；smartcard/parser.py:105, 304 |
| 登录 | `session_changed` | 读取期间登录状态变了，结果已丢弃 | `refresh_source` | app.py:113；jw/service.py:55；smartcard/service.py:22 |
| 登录 | `needs_elc_login` / `resource_login_required` | ELC 或单个活动要求登录 | `open_setup` / `open_source_page` | mystu/parser.py:197, 226；mystu/service.py:53 |
| 访问 | `access_denied`（403） | 来源拒绝访问 | `open_source_page` | network.py:241；charging/adapter.py:69 |
| 访问 | `not_found`（404） | 资源不存在 | `refresh_then_check_source` | network.py:247 |
| 访问 | `rate_limited`（429） | 来源限流 | `wait_then_retry` | network.py:244；charging/adapter.py:72 |
| 访问 | `source_unavailable`（5xx 或维护页） | 来源暂时异常 | `retry_later` | network.py:250；notices/parser.py:202；notices/service.py:25-26 |
| 访问 | `source_rejected`（其他 4xx） | 来源不接受这次请求 | `open_source_page` | network.py:272 |
| 访问 | `network_error` / `request_timeout` / `request_deadline` | 网络失败 / 超时 / 本轮读取超时 | `check_network` / `retry_later` / `reduce_scope` | network.py:256, 277；charging/adapter.py:84-89 |
| 访问 | `response_too_large` / `request_limit` | 响应超过上限 / 请求次数超过上限 | `reduce_scope` | network.py:280；smartcard/adapter.py:41, 67 |
| 访问 | `activity_password_required` | 测验要求活动密码 | `open_source_page` | network.py:292 |
| 解析 | `schema_changed` | 页面结构变化或有歧义，**不会当成空结果**（53 处） | `report_source_change` | jw/timetable.py:21；jw/parser.py:25 等 |
| 解析 | `source_error` | 来源数据处理失败 | `report_source_change` | app.py:124；charging/parser.py:243 |
| 解析 | `oa_unavailable` | 这个网络读不到 OA 列表 | `check_request` | notices/parser.py:182 |
| 缓存 | `not_cached` | 缓存里没有这条记录 | `check_request` | store.py:202 |
| 缓存 | `cache_needs_refresh` | 旧教务缓存需要重新刷新 | `check_request` | app.py:339 |
| 缓存 | `refresh_running` | 已有刷新在进行（全局一把锁） | `wait_then_retry` | app.py:88；charging_cache.py:43 |
| 参数 | `invalid_semester` / `invalid_source` / `invalid_kind` / `invalid_query` / `invalid_limit` / `invalid_offset` / `query_required` | 参数不合法 | `check_request` | app.py:84-86, 131-136；store.py:138；app.py:78-82 |
| 参数 | `invalid_date_range` / `invalid_transaction_type` | 一卡通日期或类型不合法 | `check_request` | smartcard/parser.py:227, 192 |
| 参数 | `invalid_task` / `invalid_profile` / `invalid_notice` | 本地写入参数不合法 | `check_request` | store.py:207；runtime.py:189；app.py:265 |
| 通知附件 | `attachment_not_cached` / `attachment_too_long` / `attachment_unreadable` / `unsupported_attachment` / `unsupported_source` / `unsupported_article` | 附件或正文不可读 | `check_request` | app.py:281-313；notices/service.py:46 |
| 一卡通 | `unsupported_scope` | 多张卡，或没有卡账户 | `check_request` | smartcard/parser.py:170 |
| 一卡通 | `captcha_rejected` / `invalid_credentials` / `login_rejected` | 登录阶段失败（只在宿主 CLI 出现） | `check_request` | auth_http/smartcard.py:56, 90, 92 |
| 充电 | `device_offline` | 平台报告设备不在线 | `check_request` | charging/parser.py:240 |
| 充电 | `unknown_device` / `unsupported_device` / `invalid_location` / `invalid_refresh` | 设备或参数问题 | `check_request` | charging/service.py:119；charging/parser.py:248；charging_devices.py:43；charging/service.py:48 |
| 本机 | `secure_storage_unavailable` / `local_state_busy` / `operation_failed` | 本机密钥库不可用 / 本地状态忙 / 未知失败 | `check_system_keyring` / `check_request` / `check_local_state` | credentials.py:48；runtime.py:132；server.py:78 |

`next_action` 的完整映射见 `runtime.py:25-60`，未列出的码默认为 `check_request`。

### 2.11 来源与新鲜度字段

| 字段 | 出现位置 | 类型或格式 | 含义 | 出处 |
|---|---|---|---|---|
| `freshness[]` | `get_capabilities`、所有缓存查询、`refresh_source` | `[{source, attempted_at, success_at, status, saved}]` | 每个来源一行。`attempted_at` 是最后一次尝试（UTC）。`success_at` 是最后一次 `ok` 或 `partial` 的时间（UTC），失败时**不更新**。`status` 是最后一次的状态或错误码 | store.py:124-133 |
| `collected_at` | 每条缓存记录 | UTC ISO | 这一批写入缓存的时间。**不是学校发布时间** | store.py:72 |
| `detail_collected_at` | 通知记录 | UTC ISO | 正文和附件的抓取时间 | store.py:73-86 |
| `scopes[]` | 课程类缓存查询 | `[{source, semester, course_count, collected_at}]` | 当前课程范围快照 | store.py:192-195 |
| `checked_at` | 理论课表、一卡通 | UTC ISO | 实时读取完成的时间 | jw/service.py:57；smartcard/service.py:24 |
| `queried_at` / `collected_at` / `last_attempt_at` / `cache_age_seconds` / `stale` | 充电 | UTC ISO / int / bool | 采样时间和新鲜度。**整个代码库里只有充电（和签到）有 `stale` 字段** | charging/service.py:27-44, 85 |
| `cached` | 几乎所有返回 | bool | 缓存查询恒为 `true`；实时读取为 `false`；`get_notice` 为 `!refresh` | store.py:190；jw/service.py:58 |
| `source` / `source_url` / `transport` | 记录和实时信封 | string | 来源 ID / 学校入口 / `https`、`school_legacy_http`、`school_http` | 各 service |
| `official` | 充电、狐友 | `false` | 不是学校官方来源 | charging/service.py:84；app.py:159 |
| `content_trust` | 几乎所有返回 | `"untrusted_source_data"` | 学校或第三方原文，不可信 | app.py:160 |
| `auth[]` | 缓存查询 | `[{source, status, verified_live: false}]` | 本地会话状态。**`verified_live` 恒为 false**，有会话不代表当前在线可读 | app.py:145-146；vault.py status |
| `unverified_count` / `scope_message` / `unavailable[]` / `scan_capped` | 缓存查询 | int / string / `[{source, status}]` / bool | 旧版本或超出范围的记录数 / 提示文案 / 解密失败的来源 / 是否碰到 5000 条扫描上限 | store.py:174-196；app.py:153-156 |
| `message` | 缓存查询无结果时 | string | 固定文案："缓存中没有匹配记录…不能据此判断学校没有该事项" | app.py:151-152 |

- 代码库里**没有 `cached_at` 字段**（全仓库 grep 为 0）。
- 缓存查询信封（`store.py:189-196` + `app.py:145-160`）完整为：`ok`、`items`、`total_count`、`offset`、`has_more`、`cached`、`unavailable`、`unverified_count`、`scopes`、`scan_capped`、`freshness`、`auth`、`content_trust`，再加上可选的 `message` 和 `scope_message`。

---

## 3. 边界情况清单（源码或文档明写）

| # | 边界情况 | stu-mcp 的处理 | 出处 |
|---|---|---|---|
| 1 | 缓存为空 | 不等于学校没有，返回 `message` 提示 | server.py:41；app.py:151-152；store.py:202 |
| 2 | 理论课表的范围 | 不含实验课、校历、节次时刻、调课，`coverage` 三个核验标志恒为 false | jw/timetable.py:337-338；docs/timetable.md:18, 69 |
| 3 | 节次写成非数字（0、A–C）或超过 30 | 整张课表 `schema_changed`，不部分返回 | jw/timetable.py:152, 169-171 |
| 4 | 节次中的连字符 | 是逐个列举（`[01-03节]` 等于第 1 节和第 3 节），离散节次拆成多个 meeting | jw/timetable.py:168-178；tests/test_jw_timetable.py:92；skills/…/reviewed-input.md:16 |
| 5 | 安排的节次超出所在网格行 | 整张失败；跨大节连排只能是两行两个 meeting | jw/timetable.py:286-287；tests/test_jw_timetable.py:229-230 |
| 6 | 同一格多组安排 | 用 `-----` 分隔；不同周次、不同老师分成多个 meeting | jw/timetable.py:193；tests/test_jw_timetable.py:68-84 |
| 7 | 未排课课程 | 放进 `unarranged`，产生 `pending`，`status=partial`，但 `coverage.complete` 可以是 true | jw/timetable.py:308-309, 334 |
| 8 | 明细里有教师、网格里找不到 | 放进 `unplaced_details`，`complete=false`，不认定为没课 | jw/timetable.py:310-313；tests/test_jw_timetable.py:169-177 |
| 9 | 空学期 | `ok` 加空数组，`complete=true`；登录页或维护页是错误，不是空 | tests/test_jw_timetable.py:238-248；jw/timetable.py:24-26, 303-304 |
| 10 | 指定的学期不在学校选项里 | 返回 `invalid_semester` | jw/service.py:49-50 |
| 11 | 读取期间换号或退出 | 返回 `session_changed`，丢弃结果 | jw/service.py:54-55；smartcard/service.py:21-22；app.py:111-113 |
| 12 | meeting ID 的稳定性 | 改周次会生成新 ID；换老师或教室 ID 不变；重复导入不承诺去重 | jw/timetable.py:290-294；docs/verification/2026-10-23-academic-timetable.md:16 |
| 13 | 同名不同班 | 课程 ID 包含教学班，不会合并 | jw/timetable.py:235；skills/…/calendar-rules.md:7 |
| 14 | 考试时间或考场未公布 | 字段为 `""`，记录照常返回 | tests/test_jw_parser.py:313-319 |
| 15 | 同一门课、同一时间出现两条考试 | 整页 `schema_changed` | tests/test_jw_parser.py:291-300 |
| 16 | 旧 schema 版本的教务缓存 | 不列出，计入 `unverified_count`，并给出 `scope_message` | store.py:174-176；app.py:153-155 |
| 17 | 学分或绩点不是数字 | 为 `null`；等级制成绩不参与加权 | tests/test_jw_parser.py:302-310；app.py:344-354 |
| 18 | 补考和重修 | 加权时逐条计入，不去重 | app.py:358 |
| 19 | 成绩上限 | 每次刷新最多 200 行 | jw/schema.py:6；jw/service.py:22-24 |
| 20 | jw、public、oa、雨课堂刷新 | 构造时 `limited=True`，`status` **恒为 `partial`**（有界读取），不代表出错 | jw/service.py:16；notices/service.py:13, 38；yuketang/service.py:15；collection.py:30-31 |
| 21 | 考试读取失败 | 不影响成绩写入，只在 `errors[]` 里出现 | jw/service.py:26-34 |
| 22 | MySTU 的 `due_at` | 认不出格式时原样返回学校文字，不是 ISO | parsing.py:33-48 |
| 23 | MySTU 读取上限 | 详情每次最多 20 个，超出的标 `limit_reached`；活动每门课 50 个；日程 50 条，窗口为过去 7 天到未来 31 天 | mystu/schema.py:26-28；mystu/service.py:46-60；mystu/adapter.py:66 |
| 24 | 单个活动返回登录页 | 转成 `resource_login_required`，不判定整个会话失效 | mystu/service.py:51-55 |
| 25 | 测验要求活动密码 | 返回 `activity_password_required` | network.py:290-293 |
| 26 | 三套学期写法 | jw 为 `2026-2027-1`；MySTU 为 `2026-1`；雨课堂为 `20261` | jw/schema.py:24；mystu/parser.py:149；tests/test_course_sync.py:257 |
| 27 | 课程范围变化 | 旧任务和资料被移除，或计入 `unverified_count`；本地状态覆盖一并删除 | store.py:93-99, 180-181 |
| 28 | 通知发布时间 | 只截日期原文，没有时分，格式不统一 | parsing.py:73-75 |
| 29 | 正文和附件上限 | 正文 60000 字；附件最多 30 个；PDF 最多 100 页、8 MiB；只支持 PDF 和文本，扫描件不 OCR；`truncated` 标记截断 | parsing.py:65；notices/parser.py:237, 250；app.py:295-317；server.py:282 |
| 30 | OA 读取路径 | 先匿名直读，失败才走 WebVPN；WebVPN 的记录读正文也必须用 WebVPN | notices/service.py:31-40；notices/adapter.py:45-46 |
| 31 | OA 列表为空 | 必须同时有搜索表单和空态文字，才算正常空，否则返回 `oa_unavailable` | notices/parser.py:176-182 |
| 32 | 正文页是错误页或维护页 | 返回 `source_unavailable`，不保存为正文 | notices/parser.py:197-202 |
| 33 | 只有附件没有正文 | 允许，`body` 可以为空 | notices/parser.py:216, 239 |
| 34 | 一卡通卡余额和过渡余额 | 三者分开列，**不相加** | smartcard/parser.py:68-85；server.py:311 |
| 35 | 一卡通状态缺失 | 为 `null`，表示未知，不代表正常 | smartcard/parser.py:109-111 |
| 36 | 一卡通流水多页 | 只读第一页，`complete=false`，并给出 `pending` | smartcard/parser.py:318-321 |
| 37 | 一卡通查询范围 | 单次 1–30 天；类型筛选结果和条件不符时返回 `schema_changed` | smartcard/parser.py:217-227；smartcard/adapter.py:73-74 |
| 38 | 一卡通链路 | 官方入口是明文 HTTP，`transport=school_http` | sources.py:8；smartcard/service.py:24 |
| 39 | 充电：离线或未知 | 不能当作零空闲；`online` 只有 `false` 和 `null` 两种值 | server.py:45；charging/service.py:38；charging/parser.py:240 |
| 40 | 充电：失败 | 保留上次成功的样本和 `collected_at`，`stale=true`，`retained_from_cache=true` | charging_cache.py:63-67；charging/service.py:31, 41 |
| 41 | 充电：汇总 | `counts` 只算新鲜设备，stale 设备的口数全部计入 `unknown` | charging/service.py:97-102 |
| 42 | 充电：清单范围 | 14 台已核验设备，不代表全校；空闲不保证有车位 | charging_devices.py:23-38；charging/service.py:113-114 |
| 43 | 充电：刷新节流 | 同一设备 60 秒内不重抓；超过 120 秒为 stale；被限流后全体冷却 | charging_cache.py:11-12, 45, 70-80 |
| 44 | 缓存查询上限 | 每次扫描 5000 条（`scan_capped`）；`limit` 1–50；`offset` ≤10000；查询文字 ≤200 字 | store.py:137-145, 196；app.py:132-133 |
| 45 | 并发刷新 | 全局一把刷新锁，同时只能有一个刷新，其他返回 `refresh_running` | app.py:87-88 |
| 46 | 任务状态 | 本地状态（`status`）和学校状态（`school_status`）分开 | server.py:334；mystu/service.py:69 |
| 47 | 报修数据 | 草稿、预览、回执只在进程内存；`checked_at` 是 Unix 秒 | repair/service.py:103, 252 |
| 48 | 未知异常 | 统一返回 `operation_failed`，不输出认证信息 | server.py:76-78 |

---

## 4. 对前端设计的直接影响

**可能很长的字段**（需要截断、展开或换行，不能假设一行能放下）：

- 课程名 `title`：单字段上限 2000 字，`jw/timetable.py:109`。
- 通知标题：不截断。
- 通知正文：60000 字。
- 附件文字：60000 字。
- MySTU 任务的 `body`：6000 字。
- 雨课堂公告正文：10000 字。
- `location`：楼名加教室，可能很长。
- `raw_time`、`notes`、`online_info`。
- 考试 `notes`。
- 错误 `message`。

**常为空或可能缺失**：

- 课表的 `building`、`room`、`notes`、`online_info`，以及课程级 `teacher`：为 `""`。
- 未公布考试的 `exam_time`、`location`：为 `""`。
- 考试和成绩的可选列：**key 可能不存在**。前端要用"缺失或空"的统一判断，不能 `record.field.length`。
- 通知：`published_at` 为 null，OA 的 `department` 为 `""`。
- MySTU：任务的 `due_at` 为 null（看 `due_at_status` 区分原因），`school_status` 为 null，课程 `url` 为 null，日程 `location` 为 `""`。
- 一卡通：四个状态字段为 null，`balance_breakdown` 可能不存在。
- 充电：`counts`、`ports`、`collected_at` 为 null（没有成功样本时）。

**必须显示来源与时间**：

- 充电：
  - 显示 `collected_at`，不要用 `queried_at` 代替。
  - 显示 `stale` 和 `cache_age_seconds`。
  - 注明 `official=false`，第三方来源。
  - 离线设备显示"状态未知"。
- 一卡通：
  - 显示 `checked_at`。
  - 标注明文 HTTP 链路。
  - 流水 `complete=false` 时提示"只显示第一页"。
- 理论课表：
  - 显示 `checked_at`。
  - 固定提示"仅理论课，节次时刻未核验"（`period_times_verified=false`）。
  - `status=partial` 时展示 `pending` 和未排课列表。
- 所有缓存类数据（成绩、考试、作业、日程、通知）：
  - 显示对应来源的 `freshness.success_at`，或记录的 `collected_at`。
  - 失败时还要显示 `freshness.status`，因为 `success_at` 不会随失败更新，界面要能看出"上次成功是 X，最近一次失败"。
  - 空列表文案必须是"缓存里没有"，不能说"学校没有"。
- `academic_summary`：必须展示 `method`，说明这不是官方绩点。
- 通知：显示来源（`category` 或 `department`）和原始日期。

**只能原样显示学校原文，不能推导**：

- 考试 `exam_time`：字符串，不解析，不换算时区。
- `score`：等级制和百分制混在一起，不要把"优秀"换算成分数。
- `score_marker`、`exam_type`、`retake_semester`：重修、缓考、补考都只有原文，不能自造枚举。
- 一卡通四个状态字段、流水的 `type`、`category`、`status`：原样显示。不能把"扣款"归为餐饮消费，服务端也明确说不能混作餐饮消费（`server.py:322`）。
- `balance` 和过渡余额：**不能相加**成"可用余额"。
- 通知 `published_at`：原样显示，排序时只能按日期粗排。
- `room`、`location`、`building`：不拆楼栋，与 `02-official-sources.md` §7.9 一致。
- `raw_time`：作为核对依据，可以放在详情里显示。
- MySTU 认不出格式的 `due_at`：原样显示。
- `school_status`：原样显示。
- 充电口状态：用返回的 `label`，不要自己映射。
- `pending` 和错误 `message`：原样显示。

**可以推导，但要标注依据**：

- `periods` 整数换算成小节时刻：用 `02-official-sources.md` §2 的作息表。10–13 对应 0、A、B、C 是推断，需要真实样本核对。拿到真实样本前，晚课和第 0 节应加"时刻待核对"标记。
- `weeks` 加校历第 1 周周一，推出具体日期：要用学期配置，stu-mcp 不提供。
- 把 ISO 格式的 `due_at` 和当前北京时间比较，判断已过期或今天截止：stu-mcp 不计算。
- 相邻 meeting 合并成跨大节的连续色块：同一课程、同一星期、周次相同、小节相连才合并。

**状态设计**：

- 每个模块至少要区分 5 种状态：正常、空（确认为空）、部分（`partial`、`pending`、`complete=false`）、缓存过旧（`stale` 或 `success_at` 较早）、错误（`ok=false`，按 `next_action` 给出操作）。
- `refresh_source` 的 `partial` 在 jw、public、oa、雨课堂上是常态（边界 #20），**不能**把它当作错误标红。

---

## 附：合成 fixture 索引（`docs/phase0/fixtures/`）

每个文件都是对应工具的**原始返回形状**，顶层多了一个 `_comment` 字段，说明这个文件覆盖了哪些极端情况、出处在哪，以及哪些形状是推断的。`errors.json` 外层的 `examples` 只用来组织样例。所有人名、班级、教室、金额、ID 输入都是虚构的。记录 ID 是按 `parsing.py:18-20` 的规则，用这些虚构输入真实算出来的。周次展开、排序、`counts` 汇总、加权统计都按源码规则计算。

| 文件 | 对应工具 / 场景 |
|---|---|
| `academic-timetable.json` | `get_academic_timetable`：长课名、同名不同班、离散周次、单双周、跨大节连排、第 0 节、A–C 节、多教师多教室、未排课、待核实 |
| `academic-timetable-empty.json` | `get_academic_timetable`：完整空学期，以及它和失败的区别 |
| `exams.json` | `get_exams`：时间为学校原文、地点缺失、同日两场、完全未公布 |
| `grades.json` / `academic-summary.json` | `get_grades` / `academic_summary`：等级制与百分制混合、补考、重修、缓考、未出成绩、加权统计 |
| `mystu-tasks.json` / `mystu-schedule.json` / `mystu-refresh.json` | `get_tasks` / `get_schedule` / `refresh_source('mystu')`：已过期、今天截止、无截止、超长标题、非 ISO 截止时间、`complete=false` |
| `notices.json` / `notice-detail.json` / `notice-attachment.json` | `search_notices` / `get_notice` / `read_attachment`：PDF 与文本附件、超长标题、只有链接、只有附件、需要 WebVPN |
| `smartcard.json` / `smartcard-transactions.json` | `get_campus_card` / `get_campus_card_transactions`：余额、卡状态异常、多类型流水、`complete=false` |
| `charging.json` / `charging-device-stale.json` | `get_charging_overview` / `get_charging_device`：全空闲、全占用、离线、旧样本保留、纯 stale、完整状态枚举 |
| `errors.json` | 每类错误码一例，加上一个"成功里带部分失败"的例子 |
