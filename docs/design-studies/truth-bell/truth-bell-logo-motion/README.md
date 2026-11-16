# 真理钟（Truth Bell）Obsidian S1 · 品牌动效源码与双端预渲染资产

本目录归档 **S1 黑曜霜晶（Obsidian）真理钟** 的 HyperFrames WebGL2 品牌动效工程，以及为 **Android（Jetpack Compose）** 与 **Web / iOS Safari / Capacitor PWA（React 19 + Vite）** 预渲染的成片资产包。

> 当前仅作设计与媒体资产归档，尚未修改任何业务代码；确定具体嵌入页面或组件位置后，可直接按场景选用下方对应成品。

## 预渲染成片与用途（`renders/`）

全部 `4.00s` 端侧资产与 `5.50s` 展示片均运行同一套连续时间函数：全黑起手 → 多色光影轮廓与柱体逐层照亮 → 带细锐 RGB 色散边缘的高光液态玻璃飘带扫掠 → 终段纯白键光洗净并 **1:1 收敛锁定至 Obsidian S1 静态定稿**。

| 文件 | 实测规格 | 体积 | 用途与选型建议 |
| --- | --- | --- | --- |
| [`renders/truth-bell-obsidian-s1-badge-512-alpha.webp`](renders/truth-bell-obsidian-s1-badge-512-alpha.webp) | `512×512` RGBA Animated WebP、`232` 动画帧（尾帧去重）、`3999 ms`（`60fps` 时基）、RIFF `ANIM loop=1` | `2,903,040` 字节（约 `2.77 MiB`） | **透明背景 UI 叠加层**：圆角外侧全透明无杂边，适合叠放在浅色或深色页面、弹窗、卡片或品牌徽章位置；播放 1 次后停在定稿最后一帧。 |
| [`renders/truth-bell-obsidian-s1-square-badge-1024-60fps.mp4`](renders/truth-bell-obsidian-s1-square-badge-1024-60fps.mp4) | `1024×1024`、`H.264 High@L4.0 (avc1)`、`yuv420p`、`bt709`、`has_b_frames=0`、`60fps`、`240` 帧、`4.00s`、`moov` 已前置 | `2,417,833` 字节（约 `2.31 MiB`） | **紧致满画幅高清角标**：图标铺满整个 `1024×1024` 画幅，适合由前端 CSS 或 Compose `clip(...)` 自行控制外层圆角的深色卡片与启动视效。 |
| [`renders/truth-bell-obsidian-s1-square-stage-1024-60fps.mp4`](renders/truth-bell-obsidian-s1-square-stage-1024-60fps.mp4) | `1024×1024`、`H.264 High@L4.0 (avc1)`、`yuv420p`、`bt709`、`has_b_frames=0`、`60fps`、`240` 帧、`4.00s`、`moov` 已前置 | `2,081,510` 字节（约 `1.99 MiB`） | **暗黑工作室舞台版**：图标最终居中收敛为 `784×784`，四周平滑融入 `#121419 → #050507` 径向暗场，适合开屏（Splash）、引导页与关于页独立展示。 |
| [`renders/truth-bell-obsidian-s1-60fps.mp4`](renders/truth-bell-obsidian-s1-60fps.mp4) | `1920×1080`、`H.264 High@L4.2 (avc1)`、`yuv420p`、`bt709`、`has_b_frames=0`、`60fps`、`330` 帧、`5.50s` | `1,898,730` 字节（约 `1.81 MiB`） | **16:9 宽屏展示母片**：用于大屏演示、发布介绍视频与设计评审，包含 `1.62s` 末帧静置停留。 |

## 工程目录与源码用途

- [`index.html`](index.html)：`1920×1080`、`5.5s` 主工程入口，挂载核心合成 [`compositions/truth-bell-hero.html`](compositions/truth-bell-hero.html)。
- [`compositions/truth-bell-hero.html`](compositions/truth-bell-hero.html)：核心 WebGL2 多通道着色器与动画曲线实现；支持通过变量切换 `canvasWidth`、`canvasHeight`、`settledScale`、`clipDuration`、`alphaBg`、`lensClip`，并在结尾 `settleBlend` 段平滑过渡到定稿母图。
- [`compositions/truth-bell-hero.motion.json`](compositions/truth-bell-hero.motion.json)：HyperFrames 运动质量校验断言（`appearsBy`、`staysInFrame`、`keepsMoving`）。
- [`presets/`](presets/)：端侧预渲染专用尺寸预设（置于子目录中以避免触发根目录多主合成告警）：
  - [`presets/embed-square-alpha-512.html`](presets/embed-square-alpha-512.html)：`512×512` 透明圆角徽标参数（用于导出 PNG 序列再转 Animated WebP）。
  - [`presets/embed-square-badge-1024.html`](presets/embed-square-badge-1024.html)：`1024×1024` 紧致满画幅参数。
  - [`presets/embed-square-stage-1024.html`](presets/embed-square-stage-1024.html)：`1024×1024` 暗黑舞台参数（`settledScale = 784`）。
- [`assets/`](assets/)：终帧 1:1 锁定使用的官方 `1024px` 定稿参考图（源自 [`../arctic-l1-fade-monochrome-final/`](../arctic-l1-fade-monochrome-final/)）及字体文件。
- [`SPEC.md`](SPEC.md) / [`REVIEW.md`](REVIEW.md)：动效规格说明与 `1920×1080` 主版本视觉/检查记录。
- [`hyperframes.json`](hyperframes.json) / [`package.json`](package.json) / [`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md)：HyperFrames CLI 工程配置与标准脚手架说明。

## 双端嵌入注意事项（待定位置后参考）

- **Android（Jetpack Compose · `minSdkVersion = 24`）**
  - 透明组件优先选用 `renders/truth-bell-obsidian-s1-badge-512-alpha.webp`：`API 28+` 可直接用系统 `ImageDecoder` + `AnimatedImageDrawable`（零额外依赖）解码并依 `ANIM loop=1` 停在终帧；若需兼容 `API 24–27`，嵌入时可按需引入动图解码库或在低版本直接回退静态 PNG。
  - 开屏/独立舞台优先选用 `MP4`：放入 `res/raw/`，可用系统 `MediaPlayer` 配合 Compose `AndroidExternalSurface` / `TextureView`（零新增依赖）或 Media3 硬件解码，播完衔接静态高清 `1024px` 定稿图。
- **Web / iOS Safari / Capacitor PWA（React 19 + Vite）**
  - `Animated WebP` 直接用 `<img src="..." alt="..." />` 即可单次播放并定格于终帧。
  - `MP4` 使用 `<video muted playsInline autoPlay preload="auto" poster="..." />`（iOS Safari 自动播放要求必须带 `muted` 与 `playsInline`；文件 `moov` box 已前置，可立即起播）。
  - 上线时仅把实际选中的 **1~2 个文件** 复制到应用 `public/`（或 Android `res/raw/`）中；若未来重新启用 `vite-plugin-pwa` 离线预缓存，需注意单个资产体积在 `2–2.8 MiB` 区间，超过 Workbox 默认 `2 MiB` 单文件阈值，届时按需调大配置或改用按需缓存。

## 校验与重新渲染命令

在本目录执行：

```powershell
# 1. 运行主合成静态检查与逐帧校验
npx hyperframes check --no-browser-gpu --json

# 2. 重新渲染 1920x1080 主展示片
npx hyperframes render --format mp4 -f 60 --crf 13 --no-browser-gpu -o renders/truth-bell-obsidian-s1-60fps.mp4

# 3. 通过临时根文件重渲染预设（保持 presets/ 中的相对路径引用）
Copy-Item presets/embed-square-stage-1024.html .hf-render-tmp.html -Force
npx hyperframes render -c .hf-render-tmp.html --format mp4 -f 60 --crf 13 --no-browser-gpu -o renders/truth-bell-obsidian-s1-square-stage-1024-60fps.mp4
Remove-Item .hf-render-tmp.html -Force

# 4. 512px 透明 Animated WebP 生成方式（先用 embed-square-alpha-512.html 导出 240 张 RGBA PNG 帧，再用 FFmpeg 编码）
ffmpeg -y -framerate 60 -i frame-%04d.png -vcodec libwebp_anim -lossless 0 -q:v 92 -compression_level 6 -loop 1 renders/truth-bell-obsidian-s1-badge-512-alpha.webp
```
