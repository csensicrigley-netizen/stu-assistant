/**
 * Native glue for the Android App. Everything here is a no-op in a browser,
 * and a failing plugin call can never stop the App from starting.
 *
 *   - hardware back: closes the top-most sheet, then steps back through the
 *     app, then sends the App to the background (never kills it);
 *   - status bar icons follow the App's light / dark theme.
 */
import { SystemBars, SystemBarsStyle } from "@capacitor/core";
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
