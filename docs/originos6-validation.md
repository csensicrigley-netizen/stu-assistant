# OriginOS 6 原子岛：自有包名验证

## 已有实测和不能推出的结论

用户的 vivo V2509A / Android 16 / API 36 / `PD2509E_A_16.0.30.15.W10` 在旧通用验证包 1.0.55 中收到通知，但只出现在下拉通知栏。诊断为 `promotionAllowed=false`、`eligible(last built)=true`、`active=true`、`FLAG_PROMOTED_ONGOING=false`，实际用了 API36 的 `colorized=true` 兼容构造。这个结果说明该次通知没有获得标准提升，尚不能确定具体系统策略，也不能把它解释为漏掉通知权限声明或提升请求。

2026-10-31 核对的 GitHub 一手资料：

- [IslandCheck 实测说明](https://github.com/tigerduck-app/android-dynamic-island-test/blob/2dd30fb8b6e04675282a0f1e801bcfa87ad29cf2/README.md#originos-6-needs-nothing)：V60 Lite / V2529 / 国际版 `PD2512F_EX_A_16.1.13.4.W20` 有可见原子岛和提升标志。其[通知构造](https://github.com/tigerduck-app/android-dynamic-island-test/blob/2dd30fb8b6e04675282a0f1e801bcfa87ad29cf2/app/src/main/java/com/test/island/dynamic/android/MainActivity.kt#L512)使用标准 ProgressStyle、ongoing、提升请求，没有开启 colorized。这不是相同国内机型或相同固件的验证。
- [Origin Isle PR #8](https://github.com/fvhde/origin-isle/pull/8) 有 X100 Ultra / OriginOS 6 的实机图，[PR #1](https://github.com/fvhde/origin-isle/pull/1) 有 X200U 的使用报告。但其[开发文档](https://github.com/fvhde/origin-isle/blob/main/docs/DEV.md)依赖另一应用的包名与系统白名单；不能据此推定 STU 自有包名可用。本项目没有移植该应用源码或替换应用身份。
- [CunnyPlayground 的 SuperX 调用](https://github.com/theVakhovskeIsTaken/CunnyPlayground/blob/9e9f2459a202cb730a975be110ea68c849b65170/app/src/main/java/com/thevakhovske/cunnyplayground/PlaygroundService.kt#L570)和 [OriginOS Toolkit 协议说明](https://github.com/cameleonnbss/originos-toolkit/blob/b72f9b9319893a1cc039f07ea91448fe2da31dc2/docs/ORIGINISLAND.md)提供自有包名的实验实现，但没有找到能证明精确 V2509A 固件可用的实机记录。我们独立构造协议 Bundle，不复制没有再分发许可的实现。
- [vivo-service 反编译代码](https://github.com/imwangwang/vivo-service/blob/14edba76f19c60a2091b8207ee4f33576a08b27d/com/android/server/notification/VivoSuperXNotificationManager.java#L1168)的 setter 替换整个场景的包名单，并非给本包增量授权；同仓库服务实现只转发系统/电话身份的写入。该 dump 未核验对应的固件，不能直接套用到用户机型，但足以说明不能照搬名单写入或把反射无异常当成已授权。
- [Android 官方 Live Updates 要求](https://developer.android.com/develop/ui/views/notifications/live-update)将通知特征、应用提升许可、厂商额外规则分开。当前规则与部分 Android 16.0 ROM 的 colorized 谓词不同，因此显式 A/B，避免自动回退掩盖差异。
- [Sleepy issue #164](https://github.com/lingion/sleepy/issues/164)有 X300 Pro 自有包名原子岛显示报告和维护者实机修复记录，但没有完整固件号。[固定实现](https://github.com/lingion/sleepy/blob/8b0729cf106e0dd167cabb66537989778639a50e/app/src/main/java/com/lingion/sleepy/widget/notification/VendorLiveCardRenderer.kt)同时设置标准提升与 SuperX NAVIGATION 扩展，并由前台服务投递、定时更新；还尝试写场景名单但仅以调用无异常判定。不能证明纯 SuperX、名单写入或标准字段中的某一个单独带来成功。E 对照参考其协议数据，独立实现，不执行名单写入、不创建前台服务，不声称完整复制其执行环境。

## 新包实验

包名 `com.stuassistant.app.vivoprobe`，与旧通用包和正式应用共存。五项使用同一 HIGH 通道（通道音震关闭，不设置通知级 setSilent）、同一通知 ID、相同进度、同样的十分钟上限；切换前等待上一项实际移除。C 按上游实机协议使用专用 `VIVO_SUPERX_TAG`，其余使用独立测试 tag；结束只清理本包自己的两种 tag。旧通用与华为引擎保持原实现。

| 模式 | 发送内容 | 要区分的问题 |
| --- | --- | --- |
| A | 标准 ProgressStyle、ongoing、提升请求，强制 colorized=false | 不使用旧包的自动兼容回退 |
| B | 与 A 相同，强制 colorized=true | API36 兼容谓词差异，不能单凭此解释应用级 allowed=false |
| C | 普通通知附 SuperX 文字胶囊，不带标准提升请求 | 自有包名能否被 vivo 私有渲染器接受 |
| D | 普通进度通知，不带标准提升请求或 SuperX | 通知投递的对照 |
| E | 标准提升请求与 ProgressStyle + SuperX NAVIGATION 进度/完整展开模板 | 参考 Sleepy 自有包名实机实现的混合 payload；投递方式仍不同 |

C 采用上游实验的 `TRAIN` 场景标签与 template 1 / rightTemplate 4，表示协议实验，未声明实际车票或厂商授权。它不穷举所有场景/模板，失败不能推出其他所有私有路径都失败。只读取本包 `getSceneStatus` / `isSupportCustomFun`；不执行 `setSuperXInfosSceneList`，该写入在公开反编译代码中替换场景名单，且不是普通应用的授权路径。也不豁免隐藏 API 限制、不写 AppOps、不申请悬浮窗或通知读取权限。

C 还参考 [Origin Isle 作者记录的接收条件](https://github.com/fvhde/origin-isle/blob/080f02785c81f8934014133802756495d6838a95/docs/PROTOCOL.md#4-what-makes-vivo-actually-accept-the-post)，附 `displays=65809` 与 `island.superx.forceShow=true`。这些协议事实独立实现；该作者的成功发生在另一应用身份下，不是自有包名的成功保证。

## 真机步骤与诊断

1. 允许通知，在系统中检查可用的提升设置。
2. 依次启动 A/B/C/D/E，每次回到桌面观察顶部与下拉通知栏 10–20 秒，然后回应用记录肉眼结果。每次只测试一种模式，可以推进进度后再次观察。
3. 点击“复制全部诊断与结果”，连同原子岛/通知栏截图反馈。

诊断保存采集 epoch 时间、elapsed realtime、开始与截止时间、首次/最近读到通知的时刻、每模式结果及实际读回字段。权限是否被 ROM 认识、manifest 声明、授予位和本 UID AppOps 原始值分别记录；不存在或无法查询输出 unknown，DEFAULT 不等于拒绝。`notify()` 无异常、extras 保留、eligible、allowed、promoted 标志均不替代肉眼可见结果。

此包无联网、前台服务或后台重发。API26+ 由系统 timeout 撤除，API24/25 需手动结束；A/B/C/E UI 面向 API36+。模拟器验证只能确认构造、投递与清理流程，无法证明 vivo 原子岛渲染成功。
