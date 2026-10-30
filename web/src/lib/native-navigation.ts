import { Capacitor, registerPlugin, type PluginListenerHandle } from "@capacitor/core";
import { useEffect, useRef, useState } from "react";
import { getHapticStrength, HAPTIC_STRENGTH_CHANGED } from "./haptics";

export interface NativeNavigationItem {
  key: string;
  label: string;
  icon: string;
  badge?: number;
}

/** Full snapshots keep the Compose surface and Web routes in one state. */
export interface NativeNavigationState {
  current: string;
  tabs: NativeNavigationItem[];
  theme: "light" | "dark";
  palette: "sweet" | "classic";
  strength: number;
  visible: boolean;
  sheetOpen: boolean;
  keyboardOpen: boolean;
  reducedMotion: boolean;
}

interface NavigationPlugin {
  setState(state: NativeNavigationState): Promise<{ mounted: boolean }>;
  hide(): Promise<void>;
  addListener(event: "select", listener: (event: { key: string }) => void): Promise<PluginListenerHandle>;
}

const navigation = registerPlugin<NavigationPlugin>("LiquidGlassNavigation");
let generation = 0;

/** Mount only after a real native acknowledgement; missing/failed plugins keep Web navigation. */
export function useNativeNavigation(
  current: string,
  tabs: NativeNavigationItem[],
  onSelect: (key: string) => void,
): boolean {
  const [mounted, setMounted] = useState(false);
  const route = useRef({ current, tabs, onSelect });
  route.current = { current, tabs, onSelect };
  const send = useRef<() => void>(() => {});

  useEffect(() => {
    if (Capacitor.getPlatform() !== "android" || !Capacitor.isPluginAvailable("LiquidGlassNavigation")) return;
    const ownGeneration = ++generation;
    let disposed = false;
    let failed = false;
    let pending = false;
    let sending = false;
    let lastState = "";
    let listener: PluginListenerHandle | undefined;
    const wide = window.matchMedia("(min-width: 1080px)");
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)");
    let fullHeight = Math.max(window.innerHeight, window.visualViewport?.height ?? 0);
    let layoutWidth = window.innerWidth;
    let visualWidth = window.visualViewport?.width ?? layoutWidth;
    const resetViewport = () => {
      fullHeight = Math.max(window.innerHeight, window.visualViewport?.height ?? 0);
      layoutWidth = window.innerWidth;
      visualWidth = window.visualViewport?.width ?? layoutWidth;
    };

    const snapshot = (): NativeNavigationState => {
      const root = document.documentElement;
      const editable = document.activeElement?.matches('textarea,[contenteditable="true"],input:not([type="range"]):not([type="checkbox"]):not([type="radio"]):not([type="button"]):not([type="submit"])') ?? false;
      const viewportHeight = window.visualViewport?.height ?? window.innerHeight;
      // Rotation/resizing starts a new geometry baseline even while an input
      // remains focused. A portrait height must not look like a permanent IME.
      if (!editable || Math.abs(window.innerWidth - layoutWidth) > 1 ||
          Math.abs((window.visualViewport?.width ?? window.innerWidth) - visualWidth) > 1) resetViewport();
      return {
        current: route.current.current,
        tabs: route.current.tabs,
        theme: root.dataset.theme === "dark" ? "dark" : "light",
        palette: root.dataset.palette === "classic" ? "classic" : "sweet",
        strength: getHapticStrength(),
        visible: document.visibilityState === "visible" && !wide.matches,
        sheetOpen: Boolean(document.querySelector('.sheet-layer,[aria-modal="true"]')),
        // Android also checks WindowInsets.ime; this covers Web viewport changes.
        keyboardOpen: editable && Math.max(fullHeight, window.innerHeight) - viewportHeight > 120,
        reducedMotion: reduced.matches,
      };
    };

    const acknowledge = (ready: boolean) => {
      if (disposed || ownGeneration !== generation) return;
      setMounted(ready);
      if (ready) document.documentElement.dataset.nativeNavigation = "true";
      else delete document.documentElement.dataset.nativeNavigation;
    };
    const fallback = () => {
      if (disposed) return;
      failed = true;
      acknowledge(false);
      void navigation.hide().catch(() => {});
    };
    const drain = async () => {
      if (sending || failed || disposed || !listener) return;
      sending = true;
      try {
        while (pending && !disposed && !failed) {
          pending = false;
          const state = snapshot();
          const encoded = JSON.stringify(state);
          if (encoded === lastState) continue;
          try {
            const result = await navigation.setState(state);
            if (disposed) return;
            if (result?.mounted !== true) { fallback(); return; }
            lastState = encoded;
            acknowledge(true);
          } catch { fallback(); }
        }
      } finally { sending = false; }
    };
    const request = () => { pending = true; void drain(); };
    send.current = request;
    const orientationChanged = () => { resetViewport(); request(); };

    void navigation.addListener("select", (event) => {
      if (disposed || failed || typeof event.key !== "string") return;
      const state = snapshot();
      if (!state.visible || state.sheetOpen || state.keyboardOpen || !state.tabs.some((tab) => tab.key === event.key)) return;
      // Compose owns selection feedback. Replaying it here would vibrate twice.
      route.current.onSelect(event.key);
    }).then((handle) => {
      if (disposed) { void handle.remove().catch(() => {}); return; }
      listener = handle;
      request();
    }).catch(fallback);

    const themeObserver = new MutationObserver(request);
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme", "data-palette"] });
    const modalObserver = new MutationObserver(request);
    // Portalled sheets affect navigation. Pixel changes belong entirely to the
    // native GPU path, so neither text nor animated styles are observed here.
    modalObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ["aria-modal"] });
    wide.addEventListener("change", request);
    reduced.addEventListener("change", request);
    window.addEventListener("resize", request);
    window.addEventListener("orientationchange", orientationChanged);
    window.screen.orientation?.addEventListener("change", orientationChanged);
    window.visualViewport?.addEventListener("resize", request);
    window.addEventListener(HAPTIC_STRENGTH_CHANGED, request);
    document.addEventListener("visibilitychange", request);
    document.addEventListener("focusin", request);
    document.addEventListener("focusout", request);

    return () => {
      disposed = true;
      send.current = () => {};
      themeObserver.disconnect();
      modalObserver.disconnect();
      wide.removeEventListener("change", request);
      reduced.removeEventListener("change", request);
      window.removeEventListener("resize", request);
      window.removeEventListener("orientationchange", orientationChanged);
      window.screen.orientation?.removeEventListener("change", orientationChanged);
      window.visualViewport?.removeEventListener("resize", request);
      window.removeEventListener(HAPTIC_STRENGTH_CHANGED, request);
      document.removeEventListener("visibilitychange", request);
      document.removeEventListener("focusin", request);
      document.removeEventListener("focusout", request);
      if (listener) void listener.remove().catch(() => {});
      void navigation.hide().catch(() => {});
      if (ownGeneration === generation) delete document.documentElement.dataset.nativeNavigation;
    };
  }, []);

  useEffect(() => { send.current(); }, [current, tabs]);
  return mounted;
}
