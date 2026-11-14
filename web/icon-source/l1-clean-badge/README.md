# L1 Clean Badge · 当前应用图标

2026-11-14 用户指定 `docs/design-studies/truth-bell/arctic-simplified-logos/png/L1-clean-badge-badge-512.png` 用于打包。

- `approved-512.png`：原样复制用户指定的 PNG。
- `master.svg`：同目录方案的 `svg/L1-clean-badge-badge.svg` 原始可编辑矢量工程，未经重绘。

生成器先将 SVG 按 512px 渲染并与选定 PNG 比对，通过后再导出资源：

```powershell
node web/scripts/icons.mjs --android
node web/scripts/icons.mjs --sheet
```

图标包括五种 Android 密度、圆形、自适应、系统单色主题、启动图，以及网页和 PWA 图标。自适应版本只解除最外层方形蒙版，保持造型、渐变、透视和内部遮挡；108dp 图层中央 72dp 对应完整定稿，外围延展边缘像素。单色主题从同一造型导出，仅去掉背景和颜色。

保留旧版源文件 `../truth-bell/` 供追溯；当前生成入口使用本目录。APK 沿用本地验收渠道 `com.stuassistant.app.haptics` 及此前签名。
