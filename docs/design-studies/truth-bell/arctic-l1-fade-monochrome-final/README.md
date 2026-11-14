# 真理钟 L1 · 双定稿设计资产

本目录收录黑曜霜晶与霜瓷浅灰两套最终设计。图片用于图标交付与 Logo 展示，Composer 工程及解包后的 SVG/JSON 用于后续编辑。

| 定稿 | Badge（完整圆角图标） | Bare（透明底 Logo） | 可编辑工程 |
| --- | --- | --- | --- |
| 黑曜霜晶 · 丝滑柔雾 | [512px](renders/truth-bell-l1-dark-obsidian-badge-512.png) | [512px](renders/truth-bell-l1-dark-obsidian-bare-512.png) | [Composer 工程包](packages/truth-bell-l1-dark-obsidian.icon.zip) |
| 霜瓷浅灰 · 丝滑版 | [512px](renders/truth-bell-l1-light-porcelain-badge-512.png) | [512px](renders/truth-bell-l1-light-porcelain-bare-512.png) | [Composer 工程包](packages/truth-bell-l1-light-porcelain.icon.zip) |

| 黑曜霜晶 | 霜瓷浅灰 |
| --- | --- |
| ![黑曜霜晶](renders/truth-bell-l1-dark-obsidian-badge-256.png) | ![霜瓷浅灰](renders/truth-bell-l1-light-porcelain-badge-256.png) |

## 文件用途

- `renders/`：38 张原始定稿 PNG。Badge 每套 10 张：16、24、32、48、64、128、192、256、512、1024px；Bare 每套 9 张：16、24、32、48、64、128、256、512、1024px。Bare 保留透明通道，可按背景明暗选择对应版本。
- `packages/`：2 个原始 `.icon.zip` 工程，可在 Liquid Composer 中导入，包含完整 Badge 的图层、渐变、遮罩和玻璃参数。
- `sources/`：上述两个工程的原样解包内容。`icon.json` 保存图层与材质，`Assets/*.svg` 保存造型、96 段平滑渐变及渐隐遮罩。可编辑后重建工程包。
- `scripts/build_l1_fade_final.py`：仅用 Python 标准库，从 `sources/` 重建两个 Composer 工程包。
- `scripts/render_l1_fade_final.mjs`：用 Liquid Composer 的 WebGL 渲染器重导 Badge，并原样复制已定稿的 Bare 图片到输出目录。

**工程与透明图的区别：** 两个原始 Composer 工程对应 Badge。Bare 是独立定稿的透明导出，直接使用工程的 `bare` 渲染模式并不能还原其渐隐透明度；此处将原始 Bare PNG 作为交付母版保留，脚本不重新解释其透明效果。

## 设计参数

| 参数 | 黑曜霜晶 | 霜瓷浅灰 |
| --- | --- | --- |
| 背景 | `#050505 → #161617` | `#EAEAEA → #D4D4D4` |
| Refraction / Bevel | `-6 / 2.8` | `-6 / 2.8` |
| Translucency / Blur | `52 / 14` | `52 / 14` |
| Shadow | `28` | `22` |
| 渐隐范围 | 石柱 `y: 348→494`；曲线左下 `x: 195→24` | 同左 |

浅色版柔光为 `#F4F4F4`，暗角为 `#8A8A8A`，边缘高光为 `#FFFFFF → #949494 → #F2F2F2`。曲线采用 96 段弧长平滑渐变及柔雾过渡。

## 重建与输出

需要 Python 3.9+、Node.js 22+、Google Chrome，以及 [Liquid Composer](https://github.com/frontedu/liquid-composer) 的 `4edfb44c27bab264f4ab2d762aaaed78938de44e` 检出。先在 Composer 检出内运行 `npm ci` 和 `npm run build`。所有源文件均在本目录内；以下命令从本目录执行：

```powershell
python scripts/build_l1_fade_final.py --output ./generated/packages
node scripts/render_l1_fade_final.mjs --composer C:/path/to/liquid-composer --packages ./generated/packages --output ./generated/renders
```

快速核对 512px：在 Node 命令末尾加 `--sizes 512`。渲染器必须支持 WebGL2；脚本不会降级为丢失折射效果的平面渲染。不同 Chrome/GPU 实现可能带来细微的边缘像素差异。

重建包已与原包逐文件校验，内容字节一致；512px Badge 重渲染的平均通道差小于 `0.001/255`，Bare 输出与定稿文件完全一致。
