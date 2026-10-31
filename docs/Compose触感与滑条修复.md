# Compose 触感与滑条修复

版本：1.0.33-native.1（versionCode 34）。范围为原生 Compose，保留五个主导航及现有页面布局。

## 修复原因

旧滑条从动画 target 加每个拖动 delta，再由宿主立即取整。小于半个强度单位的输入会反复丢失；动画的异步回流也不应承担输入累计。宿主还会在每个整数变化时更新全局设置、最多约每 60ms 试听一次。

现改为在手势入口同步累计 Float；动画只负责显示。宿主保留本地连续草稿，仅完成时取整、保存一次、试听一次，取消恢复已保存值。保留 Kyant 原本的 spring、玻璃材质和按压形变；源码适配另记于 `glass/UPSTREAM.md`。

## 触感分配

| 入口 | 行为 |
|---|---|
| 浏览、详情、返回、关闭、展开、筛选、日期／周次、外链 | 静默 |
| 主导航真正换项 | 极轻单次 selection；重复选中静默 |
| 勾选、主题／色系、长按进入操作模式 | 稍明确的单次 toggle；长按关闭框架默认触感，统一受 0 关闭控制 |
| 新增计划的最终保存、课表最终确认导入 | 原机械 press／release 两段式，成功不叠加 confirm |
| 登录、校园绑定、医学院同步等明确完成 | 完成后一次柔和 confirm |
| 用户主动提交被拒 | 一次包含两个轻短脉冲的 reject，非后台错误播报 |
| 强度滑条 | 拖动静默；成功松手统一 preview 一次；0 立即停止；取消不保存、不试听 |

新语义不使用延迟尾响；拒绝反馈用单个播放效果编码双脉冲，避免 Handler 排第二个动作。机械预设保留原有 RichTap 优先链路、100% 相对增益、按下／释放锐度与 110ms 最小起振间隔。关键按钮的 release 在业务回调前确认并去重，避免立即禁用或关闭表单漏掉尾段。

普通按钮默认静默，机械手感须 `mechanical=true` 显式开启。异步反馈 helper 使用最新强度且随页面销毁失效；引擎在窗口无焦点、Activity 暂停及关闭时不继续播放。取消一个普通控件不全局打断其他操作。

## 验证

| 检查 | 结果与范围 |
|---|---|
| `:app:testDebugUnitTest` | 33 项通过：触感 23、微位移 3、学业领域 6、基础 1 |
| `:app:assembleDebug :app:assembleDebugAndroidTest :app:assembleHaptics` | 成功；`lintVitalHaptics` 通过 |
| API 37 `AndroidJUnitRunner` | 12 项通过，26.838 秒：Store 9、真实指针滑条 2、已安装 APK 入口／资源 1 |
| 滑条设备回归 | 连续 1px MOVE 累计；按住时 Store／偏好不变；松手提交；CANCEL 恢复原值。微移动间隔 16ms，屏幕坐标容差限定为 2.1 个物理像素；纯输入测试另验证精确 Float 累计 |
| 实际 Haptics APK | 覆盖安装、原生入口启动成功；包名 `com.stuassistant.app.haptics`，versionCode 34，非 debuggable；旧网页 JS／public 与测试 p12 均未打包；APK 签名校验通过并沿用上一测试版证书 |
| 页面动效 | 实际 APK 录屏检查五主导航、左右方向、10 次切换含连续操作、最终正确落页；可见淡入／模糊中间帧，底栏独立。动画缩放为 0 后另验证立即落页，检查后恢复系统设置 |

首轮设备检查发现历史模板仍断言 Capacitor 默认包名，已替换成实际原生入口与资源检查。指针测试首轮失败时遗留 DOWN，输入日志明确后续 DOWN 被系统拒绝；加入 finally 手势清理、按帧发送微位移并清理旧注入状态后，完整 12 项通过。历史失败日志保留于本机 QA 目录。

设备测试不统计马达实际播放次数，录屏也不能用来外推实体手机帧率。设备马达的具体手感、疲劳与区分度仍需实体手机评估。APK 与 QA 录像留在本机，不提交到 GitHub。

## 同批补充：主导航切页

按原 `web/src/lib/motion.ts` 的 `pageVariants` 恢复方向、缩放和透明度过渡：新页从所选方向 20dp、0.994 倍、透明进入；旧页反向 14dp、0.996 倍，在 100ms 内退场。Compose spring 以单位质量换算原 `stiffness=380 / damping=32 / mass=.85`，使用 `stiffness=447.05882 / dampingRatio=.8902638`。额外增加新页 6→0dp 短暂模糊；原 JS 的该变体没有显式 blur。

`NativePageTransition` 只组合当前业务页面；退场保留 GPU display list，最多两个绘制层，不读回像素、不生成 Bitmap，也不保留旧页触摸、读屏节点或业务协程。连切会保留当前更可见的层并从其中间视觉状态退场，避免先丢掉清晰画面造成空白闪烁。五主导航变化才触发；课表内 Import／Medical 和校园子栏不重复启动整页动效。

底部玻璃导航位于动效外，页面的 sibling backdrop 仍记录过渡中的实际绘制。API 31+ 使用 GPU blur，旧 API 保留淡入、位移和缩放；系统动画缩放为 0 时直接替换，前台恢复会重新读取此设置。

## 依据

- [Android haptics design principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles)：频率、重要程度、一致性与清晰短促效果。
- [Apple Playing haptics](https://developer.apple.com/design/human-interface-guidelines/playing-haptics)：避免过量、保持因果含义、配合视觉并允许关闭。
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6)：保留固定来源版本的材质与弹性实现，修正产品输入适配。
