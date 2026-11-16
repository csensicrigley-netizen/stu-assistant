# SPEC — Truth Bell L1 Motion Identity

```text
GOAL:    Open from pure black with a crisp multi-colored contour rim, progressively illuminate monolith facets under colored studio sweeps, animate a snappy liquid-glass ribbon with luminous razor-fine flowing RGB dispersion, and converge cleanly back to monochrome Obsidian S1.
OUTPUT:  HyperFrames, 1920 x 1080, 60 fps
TIME:    5.5 s x 60 = 330 frames (continuous monotonic f(t); brisk kinetic pacing)
LOOK:
  ground (obsidian):   opens from pitch dark #010102 → radial studio cyclorama #121419 centered behind emblem → #050507 edge vignette (stageReveal 0.01→0.68 s); deterministic 1.2% micro-dither; soft floor shadow
  hero scale:          settled squircle vessel 596 x 596 px (55.2% of frame height), dead-centered at (960, 540)
  medium:              beveled 2-plane monolith with crisp 1.75 px outer silhouette contour + 1.2 px chamfer spine + base dissolve + live WebGL2 optical liquid-glass ribbon (2D blur=14, 3-wavelength spatial chromatic dispersion, swept blade tip, twin 1.5 px luminous RGB leading trajectory rails & sustained bright jewel edge flow)
  light:               staged multi-hue lighting rising from pitch black (contour trace 0.01→0.56 s → Cobalt/Cyan 0.01→1.16 s → Champagne/Coral 0.45→1.80 s → Mint/Violet 0.95→2.50 s) washed clean by diagonal Daylight White key (2.38→3.40 s) + softbox shoulder sheen back to pure B&W
OBJECTS:
  1. Squircle Vessel:  512-space SVG squircle dome waking smoothly out of blackness (0.15→1.15 s) + 1.0 px perimeter bevel rim tracing out of black with vivid prism lead spark (0.08→1.80 s, settled gain 0.96)
  2. Obelisk Monolith: exact P_SIDE & P_MAIN geometry emerging from 0.0 black: colored edge contour outlines form first, followed by sequential colored studio plane floods converging back to pure Obsidian grayscale
  3. Liquid-Glass Arc: c1(u), b1(u) under continuous RGBA16F analytic SDF + vibrant hairline Cyan/Coral/Violet/Gold edge spectrum flowing smoothly along bevel rim & leading prism rails + seamless settle onto official 1024 px Composer master
MOTION:
  camera pull:  1.50x (macro on crest (258, 198)) → 1.00x ((256, 256)) over 0.00 s → 3.32 s via two-beat integrated LUT speed curve (sampleCamCurve)
  contour/core: 0.00 s pitch black → contourReveal (0.01→0.56 s) + spine draw (0.03→0.61 s) outline monolith silhouettes in multi-color rim light → pillarReveal (0.12→1.17 s) brings full sculptural planes out of darkness
  glass orbit:  sampleRibbonCurve sweeps uHead -0.025 → 1.10 over 0.24 s → 2.90 s (2.66 s crisp arc) led by +0.195 bright fine RGB filaments + glowing edge flow across entire active sweep
  sheen & lock: Daylight White sweep (2.38→3.40 s) & softbox sheen (2.32→3.50 s) precede settleBlend (3.32→3.88 s) locking 1:1 onto official 1024 px Obsidian badge PNG
  final hold:   3.88 s → 5.50 s (1.62 s crisp hero hold)
QA:
  hyperframes check --no-browser-gpu --json; hyperframes snapshot --review --no-browser-gpu; inspect 0s, 0.15s, 0.40s, 0.85s, 1.35s, 1.70s, 2.25s, 3.15s, 4.05s
```
