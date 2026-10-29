/**
 * Native glue for the Android App. Everything here is a no-op in a browser,
 * and a failing plugin call can never stop the App from starting.
 *
 *   - hardware back: closes the top-most sheet, then steps back through the
 *     app, then sends the App to the background (never kills it);
 *   - status bar icons follow the App's light / dark theme.
 */
import { registerPlugin, SystemBars, SystemBarsStyle } from "@capacitor/core";
import { App as CapApp } from "@capacitor/app";
import { IS_NATIVE } from "./api";

type Handler = () => boolean;
const stack: Handler[] = [];

/** Register a back handler; the newest one runs first. Returns an unregister. */
export function onBack(handler: Handler): () => void {
  stack.push(handler);
  return () => {
    const i = stack.lastIndexOf(handler);
    if (i >= 0) stack.splice(i, 1);
  };
}

/** Call a plugin method, swallowing both thrown errors and rejected promises. */
function safely(call: () => Promise<unknown> | unknown): void {
  try {
    const result = call();
    if (result && typeof (result as Promise<unknown>).catch === "function") {
      (result as Promise<unknown>).catch(() => undefined);
    }
  } catch {
    /* plugin unavailable on this build or device */
  }
}

let started = false;

export function initNative(): void {
  if (!IS_NATIVE || started) return;
  started = true;
  safely(() =>
    CapApp.addListener("backButton", () => {
      for (let i = stack.length - 1; i >= 0; i--) {
        if (stack[i]()) return;
      }
      safely(() => CapApp.minimizeApp());
    }),
  );
}

export function setStatusBarDark(dark: boolean): void {
  if (!IS_NATIVE) return;
  // "Dark" = light icons for a dark background, and the other way round
  safely(() => SystemBars.setStyle({ style: dark ? SystemBarsStyle.Dark : SystemBarsStyle.Light }));
}

/** 我们的原生插件实例（LiveWindow 里顺带挂了 setChrome）。 */
let liveWindow: { setChrome?: (o: { dark: boolean }) => Promise<unknown> } | null = null;

/**
 * 告诉原生「页面现在实际是深色还是浅色」，让它把系统区涂成同色的纸。
 *
 * 为什么必须由页面来说：主题可以在页内被强制成浅色，而系统是深色。
 * 原生照着系统猜，就会把上下两条系统栏涂成近黑——那就是黑边。
 * 这条调用在浏览器里是空操作，插件没装也不抛错。
 */
export function setNativeChrome(dark: boolean): void {
  if (!IS_NATIVE) return;
  safely(() => {
    liveWindow ??= registerPlugin<{ setChrome?: (o: { dark: boolean }) => Promise<unknown> }>("LiveWindow");
    void liveWindow.setChrome?.({ dark });
  });
}
