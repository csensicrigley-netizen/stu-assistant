# Truth Bell L1 — HyperFrames Motion Design Review

## 1. Deliverable Scope & Architecture
- **Project**: `truth-bell-logo-motion` (`1920 x 1080`, `60 fps`, `5.5 s` brisk deterministic timeline `f(t)`).
- **Primary Composition**: `index.html` mounting `compositions/truth-bell-hero.html` (`truth-bell-hero`).
- **Focused Scope**: Single **Obsidian Dark S1** emblem hero motion video (`theme="obsidian"`, `vessel="badge"`, `wordmark="none"`).
- **Source Separation**: Built strictly inside `docs/design-studies/truth-bell/truth-bell-logo-motion/`, keeping `arctic-l1-fade-monochrome-final` and app source assets untouched.

---

## 2. Motion Choreography & Snappier 5.5s Timing Breakdown

Upgraded with pitch-dark opening contour trace, progressively layered multi-hue studio lighting, vibrant yet razor-fine flowing RGB dispersion, and tightened 5.5s speed-graph cadence:

| Beat | Window (s) | Kinetic Curve & Visual Behavior |
| --- | --- | --- |
| **1. Pitch-Black Void & Multi-Hue Contour Sketch** | `0.00 – 0.58` | Opens from abyssal darkness (`#010102` void at `t = 0.00s`). `contourReveal` (`0.01–0.56s`) draws a `1.75px` architectural silhouette outline around the left slope (Sapphire/Cyan -> Violet), top crest (Ice-Cyan), right slope (Warm Amber -> Coral), and ridge spine (`0.03–0.61s`) while the squircle rim sparks softly awake from black (`0.08–1.80s`). |
| **2. Multi-Hue Light Flood Sculpting Monolith Volume** | `0.12 – 2.50` | Sequenced studio light beams wash the unlit obsidian facets out of darkness from contour to full form: **Cobalt/Electric-Cyan** key (`0.01–1.16s`), **Champagne-Gold/Coral-Rose** warm face sweep (`0.45–1.80s`), and **Mint-Teal/Amethyst-Violet** lower prism bounce (`0.95–2.50s`), while the squircle dome fades cleanly out of shadow (`0.15–1.15s`). |
| **3. Calligraphic Liquid-Glass Sweep & Luminous Fine RGB Flow** | `0.24 – 2.90` | Compact `2.66s` `sampleRibbonCurve` arc: rapid upper-spire pass -> taut hairpin anticipation -> explosive foreground whip -> velvety mist settle. Led by vivid `1.5px` prism trajectory filaments (`uLeadHead`, `+0.195` max lead occluded cleanly behind upper spire) and sustained luminous Cyan/Coral/Violet/Gold edge flow (`fineRim` + `innerCaustic` + `sharpFringe`, `chromaEnv: 0.16–3.42s`). |
| **4. Camera Macro Pullback** | `0.00 – 3.32` | `1.50x -> 1.00x` centered pullback via two-beat integrated LUT curve (`sampleCamCurve`) locked to opening strike and foreground whip velocity. |
| **5. Daylight White Sweep, Softbox Sheen & Master Lock** | `2.32 – 3.88` | Diagonal Daylight White wash (`2.38–3.40s`) and overhead studio softbox sheen (`2.32–3.50s`) sweep away every residual hue back to neutral silver-gray, resolving via `settleBlend` (`3.32–3.88s`) onto `truth-bell-l1-dark-obsidian-badge-1024.png`. |
| **6. Crisp Static Hold** | `3.88 – 5.50` | `1.62s` rock-solid pure B&W Obsidian S1 lock (`< 2.2s` `maxStaticSec`). |

---

## 3. Verification & Inspected Snapshots

- **Automated Check (`check --no-browser-gpu --json`)**: `ok: true`
  - Report: `.hyperframes/artifacts/checks/c10ae29e-47fc-47b1-acde-b221cf220bd1.json` (`revision: 563a72c9-bba7-4915-bd83-49ee26340a66`, `duration: 5.5s`)
  - **Lint**: `0` errors, `1` warning (`composition_file_too_large` — single self-contained WebGL2 sub-composition at `1,434` lines).
  - **Runtime / Layout / Craft**: `runtime: ok (0 errors)`, `layout: ok (9/9 samples, 0 issues)`, `craft: ok (44/44 samples, 0.125s step, 0 issues)`, `overlap: 6/6 ok`, `audio: info audio_none`.
- **9-Frame Progression Inspection (`0s, 0.15s, 0.40s, 0.85s, 1.35s, 1.70s, 2.25s, 3.15s, 4.05s`)**:
  `.hyperframes/artifacts/snapshots/d7faaa7a-f345-45e4-88c6-af28dd0d0d94/` (`contact-sheet.jpg` + PNGs) — verified pure black `0s` start -> crisp multi-color contour outline at `0.15s` -> colored facet illumination (`0.40s–1.35s`) -> vibrant hairline RGB filaments & flowing edge dispersion (`1.35s–2.25s`) -> neutral monochrome cleanup (`3.15s`) -> clean Obsidian S1 lock (`4.05s`).
- **Review Beat & Phone Sheets (`snapshot --review`)**:
  `.hyperframes/artifacts/snapshots/31ae44ac-bfe2-4008-a5a1-5d608b502081/` (`review-beats.jpg`, `review-phone.jpg`, beats `0.55s, 1.65s, 2.75s, 3.85s, 4.95s`).
