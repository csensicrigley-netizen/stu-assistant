# WebView 实时 GPU 液态玻璃验证

记录日期：2026-10-30。范围为 Android 底栏验证 APK；业务页面继续使用原有 WebView，没有迁写为 Compose。

## 当前实现

`LiquidGlassNavigationPlugin` 取出 Capacitor 已持有的同一个 WebView，在原位置挂载全屏 Compose 宿主；`LiquidGlassWebViewSurface` 用 `AndroidView(factory = { webView })` 展示页面。导航可见时，页面使用 `Modifier.fillMaxSize().layerBackdrop(backdrop)`，官方液态玻璃底栏在同一个 Compose `Box` 内作为 sibling 读取该 GPU 图层。

底栏使用 Kyant 官方固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6` 的组件源码，依赖 Backdrop `2.0.1`、Shapes `1.2.1`。归属与适配边界见 `web/android/app/src/main/java/com/stuassistant/app/glass/UPSTREAM.md`。

这一条渲染链已移除 CPU Bitmap 截图桥、半分辨率采样、80 ms 定时采集和 JS `refreshBackdrop` RPC。前端 `setState` 只同步路由、外观与可见性等导航状态；页面像素变化由原生绘制与失效传播处理。隐藏底栏时移除背景记录 modifier，页面仍保留挂载。

WebView 实例、Capacitor Bridge、WebViewClient、WebChromeClient及 WebView ID 保持；重挂载代码不调用 `loadUrl()`、`destroy()`、历史清理或 Cookie 清理。实例仍由 Capacitor/Activity 管理，组合释放不额外销毁它。

RuntimeShader 折射入口限定 Android 13 / API 33 及以上，并要求硬件加速。旧系统或挂载失败时回到 Web 导航，不恢复 CPU 截图方案。输入法、Sheet和后台状态只隐藏原生底栏，不隐藏页面。

## 源码依据

- 已核对当前实际依赖的 [Compose UI Android 1.12.0 官方 sources.jar](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.12.0/ui-android-1.12.0-sources.jar)：`AndroidViewHolder` 将原生子 View 的失效转成 Compose layer 失效；绘制过程中发生的失效通过 `postOnAnimation` 延后处理。`AndroidViewsHandler.drawView()` 调用 `view.draw(canvas)`。
- [HWUI SkiaRecordingCanvas](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/SkiaRecordingCanvas.cpp#188) 将 WebView functor 写进硬件显示列表；[GL](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/GLFunctorDrawable.cpp#61) 和 [Vulkan](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/VkFunctorDrawable.cpp#57) 均有离屏 layer 路径。这支持 GPU 实现方向，但不保证所有 WebView/GPU 组合表现一致。
- [Chromium OnSync 协议](https://chromium.googlesource.com/chromium/src/+/main/android_webview/public/browser/draw_fn.h#188)说明 WebView 帧提交与 HWUI 同步相关；当前方案保留正常 View 绘制链，不直接调用隐藏 functor API，也不自行维护 GL/Vulkan 上下文。

去掉 CPU 像素桥仍可能增加 GPU 中间纹理、离屏合成与重复绘制。视频和 SurfaceControl overlay 还受 [HWUI 离屏条件](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/WebViewFunctorManager.cpp#144)影响，需要独立验证。

## 已完成的模拟器验证

环境：API 37 模拟器 `emulator-5554`，OpenGL。QA 包为 debuggable 的 `com.stuassistant.app.gpuqa`，仅该类调试构建开启 WebView CDP。最终 `.haptics` 包为非 debuggable，已完成安装及签名核验。

测试通过 CDP 临时在网页底部注入持续移动的红/青色条带，在原生底栏后方运动；不把该测试图层写入产品页面。约 10 秒窗口录屏共解码 354 帧。分析排除前 2 秒，避免旧版短时采集 burst 也能通过：

| 项目 | 结果 |
| --- | --- |
| 排除起始阶段后可分析帧 | 279 |
| 参照区域发生移动的帧 | 267 |
| 上述帧中玻璃区域同时发生变化 | 267 / 267 |
| 横向颜色轮廓相关系数中位数 | 0.9458 |
| 独立观察窗记录计数 | 16.26 秒内，`backgroundRecords` 1345 → 2250 |
| CPU 截图计数 | `screenshotCaptures = 0` |
| 观察窗中的导航调用 | 仅记录到 `status`，没有 `setState` 像素刷新调用 |

分析使用参照条与玻璃条的 RGB 帧间平均绝对差判断变化，阈值分别为 0.5、0.25；相关系数取每帧横向 `(R-G)` 颜色轮廓的 Pearson 相关，再求中位数。结果支持“持续动画能更新到玻璃背景”，不等于测得零延迟、100% 显示帧同步或 120 Hz。

`backgroundRecords` 是背景显示列表记录次数，不是屏幕实际呈现帧数；不能用其增量除以时间报告 FPS。暂停测试 CSS 动画后，4 秒内计数 10837 → 10837，增量为 0，说明这一静止样本没有持续背景重录。

证据位于 `tmp/gpu-navigation-qa/`（本地忽略目录，不保证随仓库分发）：

- `animation-frame.png`、`animation.mp4`：截图与窗口录屏。
- `animation-before.json`、`animation-after.json`：状态、计数和导航 RPC 观察。
- `animation-analysis.json`、`analyze-animation.py`：逐帧分析结果与方法。
- `idle-records.json`：暂停动画后的静止样本。

## 性能与交付状态

首次 debug 模拟器 A/B 的 `dumpsys gfxinfo` 结果如下；采样期间主机同时构建，存在干扰，应保留原始数据而非据此宣称性能过关。

| 场景 | 统计帧数 | Janky frames | p50 / p90 / p95 |
| --- | ---: | ---: | --- |
| 同一 WebView，隐藏玻璃底栏 | 581 | 4.30% | 25 / 34 / 42 ms |
| 显示玻璃底栏 | 499 | 20.04% | 36 / 57 / 61 ms |

来源：`gfx-webview-only.txt`、`gfx-with-glass.txt`。该样本显示明显额外开销；debug、模拟器和主机并行负载使其不能直接外推真机表现。GPU 路线移除了 CPU 截图桥，并不自动意味着性能更好。

主机构建结束后又反向测试同一持续 CSS 动画，先开玻璃，再隐藏玻璃；每段预热 2 秒并独立统计约 10 秒，不录屏：

| 场景 | 统计帧数 | Janky frames | p50 / p90 / p95 |
| --- | ---: | ---: | --- |
| 显示玻璃底栏 | 605 | 5 / 605，0.83% | 32 / 36 / 38 ms |
| 同一 WebView，隐藏玻璃底栏 | 610 | 0 / 610，0.00% | 25 / 28 / 29 ms |

来源：`gfx-reverse-glass.txt`、`gfx-reverse-webview.txt`。这些是 `gfxinfo` 的原始帧耗时分位，不能简单取倒数当 FPS。两段均无 slow bitmap uploads；开启玻璃仍有额外渲染成本。本轮到此停止扩大性能测试，不将单台模拟器上的结果外推为手机高刷、功耗或全机型验收。

| 后续项目 | 状态 |
| --- | --- |
| 主机构建结束后的反序 A/B 对照 | 已完成，上表保留额外成本和原始证据。 |
| 最终 `.haptics` APK 路径、SHA-256及包属性核验 | `release/STU-assistant-1.0.31-gpu.1.apk`，8,780,577 字节；版本 `1.0.31-gpu.1` / code 32；包名 `com.stuassistant.app.haptics`；APK v2签名验证通过，本机 Debug 证书，证据 `apk-signature.txt`。 |
| 真机 CPU/GPU、帧时间、功耗及高刷新率验证 | 未完成 |
| 旋转、输入法与后台恢复 | 最终包已通过六入口、拖拽、明暗主题、搜索输入、键盘隐藏/恢复、焦点保持时旋转、Home后恢复、Sheet打开与硬件返回关闭；搜索文字 STU 在旋转和后台恢复后保留。见 `route-*.json`、`03–14` 布局及截图记录；结束时 `crash-final.txt` 为空。 |
| 不同 WebView/GPU、视频或受保护内容 | 未做全机型/视频专项验收。 |
| 实机单次导航触感与普通按钮两段触感 | 待实机验证 |

当前结论限于底栏 GPU 路线与该模拟器上的持续动画证据；业务页面迁移和完整真机性能验收不在本轮已完成范围内。

最终 APK SHA-256：`204210101bcefbf76f9ed9eede21d58a10c9ffc949f10a99c2d12e9b46bda41f`。最终 Web 构建及类型检查通过，原生 9 项单测通过；浏览器桥接回归通过纯 CSS 动画期间无背景刷新 RPC/逐帧 setState，并保留已有导航、IME与回退检查。
