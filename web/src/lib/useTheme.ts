import { useCallback, useEffect, useState } from "react";
import { setNativeChrome, setStatusBarDark } from "./native";

export type ThemePref = "light" | "dark" | "system";
/** 樱粉 = 温柔可爱（默认）；经典 = 最早那套海蓝 + 朱 */
export type PalettePref = "sweet" | "classic";

const KEY = "stu.theme";
const PALETTE_KEY = "stu.palette";

/** the browser chrome / status bar colour per palette */
function barColor(palette: PalettePref, dark: boolean): string {
  if (palette === "classic") return dark ? "#0b0e14" : "#f9f6f1";
  // 必须等于 tokens.css 里樱粉的 --bg，也要等于 android 的 res/values/colors.xml::paper。
  // 三处任一处不同，系统栏那一条就会露出来，看着就是"黑边"。
  return dark ? "#1f161e" : "#fff0f1";
}

function fromQuery(name: string): string | null {
  try {
    return new URLSearchParams(location.search).get(name);
  } catch {
    return null;
  }
}

function readPref(): ThemePref {
  // ?theme= wins: design review and screenshot runs stay deterministic
  const asked = fromQuery("theme");
  if (asked === "light" || asked === "dark") return asked;
  try {
    const saved = localStorage.getItem(KEY);
    if (saved === "light" || saved === "dark" || saved === "system") return saved;
  } catch {
    /* storage blocked */
  }
  return "system";
}

function readPalette(): PalettePref {
  // ?palette= wins for the same reason
  const asked = fromQuery("palette");
  if (asked === "classic" || asked === "sweet") return asked;
  try {
    const saved = localStorage.getItem(PALETTE_KEY);
    if (saved === "classic" || saved === "sweet") return saved;
  } catch {
    /* storage blocked */
  }
  return "sweet";
}

const media = () => window.matchMedia("(prefers-color-scheme: dark)");

/** module-level so a palette change can repaint the browser chrome without
    threading the palette through every theme call site */
let currentPalette: PalettePref = readPalette();

function apply(pref: ThemePref) {
  const dark = pref === "dark" || (pref === "system" && media().matches);
  const root = document.documentElement;
  root.dataset.theme = dark ? "dark" : "light";
  root.dataset.palette = currentPalette;
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", barColor(currentPalette, dark));
  setStatusBarDark(dark);
  // 原生那半：系统区的底色也跟着页面的实际深浅色走。少了这一句，用户在页内
  // 强制浅色、系统却是深色时，上下两条系统栏就会被原生涂成近黑——黑边。
  setNativeChrome(dark);
}

/** Light / dark / follow the system, applied to <html data-theme>. */
export function useTheme() {
  const [pref, setPref] = useState<ThemePref>(readPref);

  useEffect(() => {
    apply(pref);
    if (pref !== "system") return;
    const mq = media();
    const onChange = () => apply("system");
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [pref]);

  const choose = useCallback((next: ThemePref) => {
    setPref(next);
    try {
      localStorage.setItem(KEY, next);
    } catch {
      /* ignore */
    }
  }, []);

  return { pref, choose };
}

/** 樱粉 / 经典, applied to <html data-palette>; the tokens do the rest. */
export function usePalette() {
  const [palette, setPalette] = useState<PalettePref>(readPalette);

  useEffect(() => {
    currentPalette = palette;
    apply(readPref()); // refresh the attribute and the browser chrome
  }, [palette]);

  const choose = useCallback((next: PalettePref) => {
    setPalette(next);
    try {
      localStorage.setItem(PALETTE_KEY, next);
    } catch {
      /* ignore */
    }
  }, []);

  return { palette, choose };
}
