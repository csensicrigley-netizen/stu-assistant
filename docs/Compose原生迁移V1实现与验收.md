# Compose 原生迁移 V1：实现与验收

更新：2026-10-30。本文件记录已落地的代码和本轮实际证据，不替代逐项验收。

1.0.36 后续修复见[原生登录与卡片抽屉修复](原生登录与卡片抽屉修复.md)：服务器配置退出正式用户流程，首页可点卡片与抽屉补齐交互反馈。

[首版功能对照表](Compose首版功能对照表.md)的 54 项均已完成代码迁移；**不表示 54 项都已在设备逐项通过**。本轮已完成 APK 资源核验、安装启动和下列限定流程；产物哈希以 `release/` 的构建回执为准。

## 实现范围与定位

Android 入口已切为 `NativeActivity`，业务页面由 Compose 承载；Kotlin 直接调用现有服务 API。原 JS／PWA 保留作为独立客户端及行为基线。唯一主导航合并为 **今天／课表／事项／校园／我的**；事项保留待办、考试、成绩，待办提供全部／个人／作业筛选。考试、成绩没有迁入课表或我的。

下表 Kotlin 文件均位于 `web/android/app/src/main/java/com/stuassistant/app/`，用于从功能 ID 定位实现。

| 对照表 ID／能力 | 主要实现文件 |
| --- | --- |
| T01–T09、W01–W06、I01–I15；今天、七日分钟谱、课程详情、个人计划、作业、考试、成绩 | `nativeapp/NativeAcademicScreens.kt`；时间、校历、节次、合课及记录解析在 `NativeAcademicDomain.kt` |
| C01–C09；通知搜索／来源筛选、生活服务、充电、图书馆、公交、校历 | `nativeapp/NativeCampusScreens.kt` |
| M01–M08；账号／来源、同步、外观、触感、反馈、退出与删除 | `nativeapp/NativeMeScreen.kt`；版本流程在 `NativeUpdates.kt`；反馈播放在 `NativeFeedback.java` |
| G01–G02；HTTP、会话、局部失败、账号缓存、同步、Plan 写入 | `nativeapp/NativeApi.kt`、`NativeStore.kt`、`NativeState.kt`、`NativeSecureStorage.kt` |
| G03；路由、返回、IME、Sheet 和底栏互斥 | `NativeActivity.kt`、`nativeapp/NativeModal.kt`、`LiquidGlassBottomBar.kt` |
| G04；欢迎、预览、内置后台注册／登录、校园绑定与人工挑战 | `nativeapp/NativeAccountScreens.kt` |
| G05；医学院 SSO／本地口令、验证码、同步与解除 | `nativeapp/NativeMedicalScreen.kt` |
| G06；粘贴／CSV／手动导入、预览、校验与保存 | `nativeapp/NativeImportScreen.kt` |
| G07；公共控件、状态、主题、圆角与字体 | `nativeapp/NativeUi.kt`、`NativeTokens.kt`、`NativeModal.kt`；固定上游控件在 `glass/` |
| 同包升级的旧存储导入 | `nativeapp/LegacyStorageMigration.kt` 与 `NativeStore.importLegacy` |

Today“全部作业”进入 `items:school`；校历／通知进入校园对应子页。导入和医学院属于课表，保留底栏，点击已选课表可返回且不增加导航反馈。Sheet、IME 打开时隐藏底栏。当前 1.0.34 所有液态玻璃按钮均按用途播放两段式，保持原 1.0 力度，主导航单次为 0.8；见 [触感分型与原强度恢复](Compose触感分型与原强度恢复.md)。上一版滑条与切页修复见 [历史记录](Compose触感与滑条修复.md)。

## 数据与网页边界

- 业务日期使用 `Asia/Shanghai`，保留 13 节时刻、100／155 分钟合课、午晚餐断开、冲突、partial／pending／unarranged。
- 作业本机勾选按服务器与账号隔离，学校状态独立保留；个人计划通过服务端 API 完成、新增、排序和删除。安排日不冒充截止，未知原文不改成时间或 0。
- 缓存和凭据使用 Android Keystore 保护；会话切换后拒绝旧响应覆盖新账号。当前账号缓存包含已读取的计划快照，**没有离线写入队列**；缓存回显不能当作写入成功。计划页原本没有完整编辑入口，本版没有把新增编辑列为迁移缺项。
- 产品页不加载业务 WebView。仅保留按版本触发的一次性旧 Capacitor `https://localhost/` 存储读取：阻断网络、白名单导入、成功后记录迁移标记并销毁 WebView。来源不明的旧缓存不直接归入当前账号。
- 通知原文、门户、水电等外链交给系统浏览器；不在助手内代读账单、代付款或追加 HTML 正文抓取。

## 实时玻璃与素材

`NativeActivity` 的同一 Compose 窗口包含背景 `base` 与当前页面 `page` 两个 `layerBackdrop` 内容源；底栏和 Sheet 位于兄弟层，消费 `rememberCombinedBackdrop(base, page)`。玻璃自身不回录到页面内容源，渲染随 Compose／GPU 内容更新；没有 Bitmap／定时截图、背景刷新 RPC 或 JS 截图桥。

Kyant 控件来自固定提交 [`65ab177e90e5c1d8c62e70cf7755841982da65f6`](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6)，使用 Backdrop `2.0.1`、Shapes `1.2.1`。来源、原参数及本地适配见 [UPSTREAM.md](../web/android/app/src/main/java/com/stuassistant/app/glass/UPSTREAM.md)；Apache-2.0 文本随源码和 APK assets 保留。

原网页 `web/public/fonts/schibsted-grotesk-latin-400-700.woff2` 转为同源 400／600／700 TTF，位于 `res/font/stu_regular.ttf`、`stu_semibold.ttf`、`stu_bold.ttf`；保留 [Schibsted Grotesk OFL](../web/android/app/src/main/assets/licenses/Schibsted-Grotesk-OFL.txt)。标志、常用图标和四套主题色沿用原源路径／配方；没有引入另一套产品品牌素材。

## 已运行的验证

| 层级 | 本轮真实结果 | 证据与限制 |
| --- | --- | --- |
| 原 JS 回归 | 35 项通过 | `npm --prefix web test`；仅证明该测试集合，不等于全部原生 UI 已验收 |
| Kotlin／JUnit | 15 项通过：原有 9 项＋academic domain 6 项 | `:app:testDebugUnitTest`；domain fixture 由真实 JS helpers 生成，涵盖时区／截止优先级、合课／空档／冲突、校历、成绩分母与未知考试原文 |
| API 37 设备 instrumentation | `NativeStoreInstrumentedTest` 9／9，51.085 秒 | `tmp/native-ui-qa/store-instrumentation-9.log`；真实 Keystore API、HTTPS 传输和真实旧 origin 读取；响应由本机测试后端提供，不是校园正式服务 |
| 手动原生 UI | 已走通下列限定流程 | 干净 `.gpuqa` 安装后注册仍停留 Bind，“稍后再连”进入 Shell；本机假后端挑战输入 `1234` 后连接成功；个人计划新增；Week／课程 Sheet／下拉关闭／Sheet 隐藏底栏及背景语义；校园生活无数据分支；粘贴导入主链见下 |
| 原 JS 手机视觉基线 | 深／浅两套，411×900 | 固定上海 `2026-10-28T10:20:00+08:00`；今天、课表、作业／考试／成绩、预览计划，另存全页 PNG 和尺寸 JSON |

Instrumentation 覆盖会话认证错误区别、服务器／账号隔离、局部失败保留、凭据加密与清理、退出／删除、旧数据导入、POST 响应丢失不重放、Plan 陈旧读取与写入竞态；未把本机假后端成功扩写为真实校园登录成功。

粘贴导入已手动走通：Week 入口 → 其他校区 → `NativeTest | Mon 1-2 | 1-16 | Teacher | Room101` → 7×13 预览显示 1 门／1 段／1–16 周 → 确认导入 → 立即查看课表，Week 显示周一 1 课、每周 100 分钟。HTTP 服务为本机假后端，解析调用现有后端真实 `timetable_import.parse_body`；这证明该导入主链，不代表真实学校读取、CSV／手动录入及全部失败分支均已通过。

视觉基线位于 `tmp/gpu-navigation-qa/js-mobile-baseline/` 和 `js-mobile-baseline-light/`，由同目录 `capture-js-baseline.mjs` 生成。它们是本机 ignored QA 产物；`5192` 临时 Vite 和截图浏览器已关闭，未操作用户 `5180` 服务。视觉对照已补回大数字校历双卡、作业色点、通知卡、图标入口、考试倒计时、统计板与 block 按钮宽度；仍须以最终安装包复核布局。

Domain 可复现样本：`node web/tests/generate-native-academic-fixture.cjs`；正式测试源在 `app/src/test/java/com/stuassistant/app/nativeapp/NativeAcademicDomainTest.kt`，期望值在 `app/src/test/resources/native-academic-js-parity.json`。设备测试源位于对应 `app/src/androidTest/`。

## 构建与交付

从仓库根使用 [make-native-release.ps1](../deploy/make-native-release.ps1)，不要用网页打包流程代替原生构建：

```powershell
pwsh -File deploy/make-native-release.ps1 -Variant Haptics
# 缓存不全时加 -Online；覆盖默认服务地址时显式传 HTTPS base。
pwsh -File deploy/make-native-release.ps1 -Variant Release -Server https://your-server.example -Online
```

脚本通过临时 ASCII 盘符执行 Gradle，默认离线，结束后恢复服务配置并移除映射。工具链使用 JDK 21、Gradle 9.3.1、AGP 9.1.1、Kotlin／Compose compiler 2.4.21；Java time 通过 desugaring 兼容最低 API。

`Haptics` 为 debug 证书的 `.haptics` 包、非 debuggable，用于并存验证；`Release` 使用 `android-signing/keystore.properties` 指定的正式签名，缺文件会中止。产物、构建日志和 JSON 回执写入 `release/`；回执含版本、字节数、SHA-256、HEAD 与资源检查结果。发包需对应已保存的源码提交；回执的 HEAD 不能代表未提交改动。

脚本实现 `assets/public/*`／`assets/*.js` 防夹带与配置存在性检查。对 1.0.32-native.1 Haptics 产物独立检查：旧网页资源 0、测试证书 0、native-config 与许可证存在；入口为 `com.stuassistant.app.NativeActivity`，versionCode 33，非 debuggable，签名校验通过。该产物已在 API 37 模拟器上覆盖安装并冷启动成功（本次 3460ms，不能外推为实体机性能）。正式交付路径为 `release/STU-assistant-1.0.32-native.1.apk`，相邻 `.apk.json` 记录实际字节数、SHA-256 和源码提交。

## 补充设备验证与覆盖边界

- Import：粘贴导入主链已通过上述限定验证；补测发现 Android DocumentsProvider 把 CSV 标为 `text/comma-separated-values`，已加入该类型及 `text/x-csv`，避免文件可见但不可选择。CSV／手动录入后续设备验证见 `release/` 验收回执；全部警告、覆盖及失败分支仍需对应场景。
- Medical 已走通：Week 入口 → 切换本地口令 → 读取图片验证码 → 输入测试账号及 `1234` → 连接与同步 → 显示 1 门／1 段 → 解除绑定。此为本机假后端，未据此宣称真实医学院登录成功；SSO、验证码过期和全部失败分支仍需对应环境。
- 事项中考试、成绩分段已设备切换核对：考试含未知时间条目，成绩保留等级成绩以及独立均分／绩点统计。个人计划与学校作业仍在同一事项入口下按来源筛选。
- 最终资源排除通过 Android asset merger 的 `ignoreAssetsPattern` 执行，旧 `public/` 文件保留在工作目录供回退，但不打入原生 APK。普通 Gradle assets SourceSet 的 exclude 在本工具链中没有达到该目的，已移除，避免只根据配置声称资源已排除。
- 54 项逐项设备验收、其他 API／机型及实体手机触感不在本轮已通过清单内。校园生活当前只验证无数据分支，实时服务数据分支仍需相应环境。

## 回退

迁移前提交为 `09c02c1d4c20a9cf42e19a136ce7b08a9a5237f4`，标签为 `rollback/pre-compose-native-20261030`；旧 APK、签名和哈希见 [回退基线](Compose原生迁移回退基线.md)。

先保存当前工作，再从标签创建独立检查分支；不要对当前未保存目录强制 reset。APK 回退另受同包签名与版本号限制，需要保留设备数据。此处仅记录回退依据，没有执行回退，也没有发布或替换服务器产物。
