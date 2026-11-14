# Arctic Chrome Prism · 定稿图标

2026-11-14 用户选定 `docs/design-studies/truth-bell/a1-color-palettes/variants/05-arctic-chrome-prism-512.png`。

- `arctic-chrome-prism.icon.zip`：该版本的原始可编辑 Liquid Composer 工程，原样复制。
- `approved-512.png`、`approved-1024.png`：用户选定版本的原始导出，原样复制；512 图直接用于网站图标。
- `reopened-512.png`：重开工程后的校验图。与定稿平均通道差为 0.000580 / 255，差异来自抗锯齿舍入。
- `artwork-1024.png`：同一工程的透明图层导出，玻璃折射及下层光学背景不变，用于延展平台裁切区域。
- `silhouette-1024.png`：隐藏星空、关闭材质后的主题色图标轮廓，仅用于 Android monochrome。
- `renderer.json`：Liquid Composer 来源、渲染器和图层参数。

## 重新生成

普通资源生成不需要重新运行玻璃渲染器。在仓库根目录运行：

```powershell
node web/scripts/icons.mjs --android
node web/scripts/icons.mjs --sheet
```

若需从可编辑工程重导中间图，准备 [Liquid Composer](https://github.com/frontedu/liquid-composer) 的 `4edfb44c27bab264f4ab2d762aaaed78938de44e` 检出及其 Node 依赖，然后运行：

```powershell
node web/scripts/export-icon-source.mjs C:/path/to/liquid-composer
```

工程重开校验必须通过，生成器才会写入应用资源。`public/icon.svg` 是包含原始定稿 PNG 的兼容入口，不是可编辑矢量母版。

## Android 适配

五种密度均导出传统、圆形、自适应和单色主题图标。自适应图层为 108dp，原画保持在中央 72dp，外围用边缘像素延展；系统决定最终轮廓，不缩小或重绘主造型。透明角以原工程的背景和图层延展，原图内部及细微边缘反光保留。

玻璃的折射依赖原始天空和石柱，彩色前景因此使用合成图，以免桌面分层视差改变定稿光学效果。`web/shots/icon-sheet.png` 展示桌面尺寸、圆形、圆角方形、水滴形和单色主题裁切。

APK 使用项目既有 Haptics 本地验收渠道（`com.stuassistant.app.haptics`，沿用本机签名）。本次只交付本地 APK，不发布网站或后台。
