# vivo 包身份对照验证

## 目的和边界

用户的 V2509A / OriginOS 6 / Android16 / PD2509E_A_16.0.30.15.W10 在普通进度、标准 Live Updates 以及自有包名的 SuperX 实验中均未出现原子岛。标准着色通知实际 eligible=true 但 promotionAllowed=false；纯扩展和混合扩展未读到严格会话匹配对象。

本实验采用同一份 STU 代码、版本、证书、SDK、UI、通道、NAVIGATION 协议与投放方式，仅改变 applicationId，检验本机是否按已存在的包身份名单准入。应用名均为“STU 包名对照验证”，不使用地图应用图标、业务、账号或数据。

| 构建类型 | applicationId | 安装关系 |
| --- | --- | --- |
| vivoidentity | com.stuassistant.app.vivoprobe | 更新此前STU原子岛验证包 |
| vivomap | com.autonavi.minimap | 独立实验身份，与真实高德包名冲突 |

两包版本名均为`1.0.60-vivoidentity.1`，versionCode61，使用同一调试签名。namespace仍为com.stuassistant.app；FileProvider authority由最终applicationId展开。

## 实验内容

- 单一纯SuperX文字胶囊：NAVIGATION场景、VIVO_SUPERX_TAG、template1/rightTemplate4、相同内容与图标、手动创建和推进进度。
- 新通知通道`vivo_island_identity_v2`请求HIGH，音震关闭；同时记录系统实际重要性和是否被用户设置。
- 首次notify前记录本包NAVIGATION的只读场景查询，诊断复制时再读一次，不能将customFun=false等同于基础模板无资格。
- 不调用setSuperXInfosSceneList、不写AppOps、不使用FGS、后台重发、悬浮窗或通知读取服务，不读取其他应用通知。
- 用户启动后前台读取状态；离开页面停止轮询，不在后台补发。
- 原始活动通知列表与严格id/tag/session匹配分别输出。ROM改写自定义字段时，原始对象仍可见；严格匹配继续保护更新和旧会话回调。
- 用户结束时只取消本包当前用户的保留测试ID及实际tag，不清除其他ID通知。

## 手机验证

1. 安装自有身份对照包，允许通知，点击开始。回桌面观察10–20秒，回来记录实际结果并复制诊断，最后结束。
2. 在没有真实高德应用的测试设备上安装高德包名实验包，重复相同操作。若系统报告包冲突/签名不一致，停止安装；不要卸载真实高德来自动满足实验。
3. 分别保存两包诊断及桌面/通知栏截图。比较首次投递前sceneStatus、原始通知、渠道实际级别和肉眼结果。

若只有实验身份获得sceneStatus=true并显示岛，可证明该固件此次包身份准入差异；若失败，不能据此断言其他固件、签名配置或正式接入均不可用。模拟器只能确认构造、生命周期、两包共存与诊断完整性，不能验证vivo系统岛。

## 依据

- [Origin Isle 开发说明](https://github.com/fvhde/origin-isle/blob/080f02785c81f8934014133802756495d6838a95/docs/DEV.md)报告高德applicationId与debug签名路径。这是作者设备观察，未验证本机固件的完整签名判断。
- [协议观察](https://github.com/fvhde/origin-isle/blob/080f02785c81f8934014133802756495d6838a95/docs/PROTOCOL.md)报告NAVIGATION可靠、需要明确通道和展示参数。
- [X100 Ultra实机PR](https://github.com/fvhde/origin-isle/pull/8)提供另一OriginOS6/Android16机器的岛截图，不是对本机或仅包名差异的独立结论。

代码在现有STU验证引擎上独立实现，仅参考协议事实，没有移植该项目的受限分发源码。

## 打包

先提交源码并同步主线，再运行`deploy/make-native-release.ps1 -IdentityPair`。脚本只检查一次递增版本下限，同时构建和校验两种身份，全部通过后输出两个APK及各自源码SHA、包名、版本和文件哈希收据。APK留在本地release目录，GitHub仅提交源码。
