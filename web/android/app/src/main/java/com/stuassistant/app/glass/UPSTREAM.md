# AndroidLiquidGlass official bottom navigation source port

Upstream repository: [Kyant0 AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass).  
Fixed revision: `65ab177e90e5c1d8c62e70cf7755841982da65f6` on `kmp`.  
License: Apache 2.0, reproduced in `LICENSE-AndroidLiquidGlass.txt` and packaged with the APK at `src/main/assets/licenses/android-liquid-glass.txt`.

The six files below are source ports of the official catalog, not independent recreations of its appearance. Original class names and method structure are retained. The Android app consumes the published Backdrop `2.0.1` and Shapes `1.2.1`; the renderer API was checked against the published `backdrop-android-2.0.1-sources.jar`.

| Local file | Fixed upstream source |
| --- | --- |
| `LiquidBottomTabs.kt` | [components LiquidBottomTabs.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt) |
| `LiquidBottomTab.kt` | [components LiquidBottomTab.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt) |
| `DampedDragAnimation.kt` | [utils DampedDragAnimation.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt) |
| `InteractiveHighlight.kt` | [utils InteractiveHighlight.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt) |
| `DragGestureInspector.kt` | [utils DragGestureInspector.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt) |
| `Coroutines.kt` | [Android utils Coroutines.kt](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/androidMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt) |

## Rendering and physics retained

- `LiquidBottomTabs` keeps the normal backdrop and separate tab-content backdrop, combined by `rememberCombinedBackdrop` for the moving lens.
- The container keeps upstream `vibrancy()`, `blur(8.dp)`, `lens(24.dp, 24.dp)` and the original 0.4-alpha light/dark surface fills.
- The pressed indicator keeps upstream `lens(10.dp * progress, 14.dp * progress)`, chromatic aberration, highlights, shadows and inner shadow.
- The standard motion path keeps `pressedScale = 78f / 56f`, original spring parameters, velocity-based X/Y deformation, panel displacement and release snapping.
- `InteractiveHighlight` keeps the original shader and blend behavior. No new material recipe or replacement shader has been introduced.
- `LiquidBottomTab` retains the composition local used by the duplicate tab-content capture, allowing the lens to magnify the captured icon and label content.

## Deliberate adaptations

1. All six files use `com.stuassistant.app.glass`. Catalog-only imports between the copied files are removed because they now share one package. Each file carries its fixed source URL and license attribution.
2. `AppAppearance.kt` supplies optional dark-theme and accent overrides from the App. Without overrides, `LiquidBottomTabs` retains the upstream system-theme and blue-accent defaults. The original glass opacity, blur, lens, highlight and shadow recipes remain unchanged.
3. A reduced-motion flag selects instant animation specs and removes scale, velocity deformation, panel displacement and captured-content enlargement. With the flag off, upstream numeric parameters are unchanged.
4. `DampedDragAnimation` exposes `onDragCancelled` separately. Upstream calls `onDragStopped` for both completion and cancellation; the port restores the committed index on cancellation and emits no route or selection haptic. This preserves the App's cancel semantics.
5. The navigation callback is emitted from the user's successful drag/tap confirmation, rather than from the observer that mirrors externally supplied selection. The observer still animates the indicator. This avoids a native click being echoed a second time when the Web route returns its new selected index.
6. Geometry-dependent `remember` keys include tab width, direction and tab count so rotation or resizing uses current drag geometry. Appearance changes recreate the helpers needed by reduced-motion behavior.
7. Android-only `Coroutines.kt` removes the multiplatform `actual` keyword and aliases the imported function as `awaitAndroidFrame`, keeping the frame wait without a same-name self-call.

## App adapter boundary

`../LiquidGlassWebViewSurface.kt` hosts the existing WebView instance in a full-screen `AndroidView`. Its visible drawing is recorded by the official `layerBackdrop` modifier and shared with the glass controls through the hardware-backed Compose/HWUI rendering tree. The WebView is neither recreated nor loaded again. Hiding navigation removes the backdrop modifier and glass controls while keeping the same WebView visible, so hidden navigation adds no backdrop recording.

`../LiquidGlassBottomBar.kt` receives that `Backdrop` and supplies the six current route labels, App-native glyphs, badges, selected semantics and appearance. The CPU screenshot bridge, Bitmap background and hidden Image source have been removed. Neither adapter implements a custom shader, blur, refraction, drag physics or highlight recipe. Labels are 14 sp and the upstream 64 dp container and 56 dp internal geometry are preserved. The Android host reserves 20 dp above the normal 84 dp navigation footprint, with matching top padding, so the original pressed/velocity deformation has room without moving the resting bar or changing Web content spacing.

The GPU route was exercised in an API 37 Android emulator using OpenGL and a debug build. During 16.26 seconds of continuous CSS animation, the background recording callback increased by 905 without refresh RPCs. A separate 10-second screen recording contained 354 frames; in its analyzed later segment, all 267 changing reference frames had corresponding glass changes, with median color correlation 0.946. These are recording-callback and captured-video observations in that environment, not a guarantee of 120 Hz display output or performance on every device. GPU rendering still performs pixel shading and texture sampling; removing CPU screenshots does not make that GPU work zero.

The host owns haptic playback. Native selection only emits a single tick when the committed destination changes; ordinary Web buttons retain their existing press/release pair. A current-tab click still reaches the host so a course subpage can return to the course overview without additional vibration. Physical haptic feel still requires handset validation.
