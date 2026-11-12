import { useCallback, useEffect, useState } from "react";
import { setNativeChrome, setStatusBarDark } from "./native";

export type ThemePref = "light" | "dark" | "system";
export type PalettePref = "sweet" | "classic" | "phoenix" | "pine" | "lilac" | "latte";

export interface PaletteOption {
  key: PalettePref;
  label: string;
  subtitle: string;
  swatchPaper: string;
  swatchBrand: string;
  swatchAccent: string;
}

export const PALETTE_OPTIONS: readonly PaletteOption[] = [
  {
    key: "sweet",
    label: "樱粉",
    subtitle: "春日奶纸",
    swatchPaper: "#fff0f1",
    swatchBrand: "#cc5683",
    swatchAccent: "#e07a68",
  },
  {
    key: "classic",
    label: "海蓝",
    subtitle: "桑浦海风",
    swatchPaper: "#f9f6f1",
    swatchBrand: "#235da1",
    swatchAccent: "#d9613f",
  },
  {
    key: "phoenix",
    label: "凤凰",
    subtitle: "盛夏红棉",
    swatchPaper: "#fff3e8",
    swatchBrand: "#cb5433",
    swatchAccent: "#df8b3c",
  },
  {
    key: "pine",
    label: "青松",
    subtitle: "水库林荫",
    swatchPaper: "#eff8f2",
    swatchBrand: "#23735b",
    swatchAccent: "#d46c46",
  },
  {
    key: "lilac",
    label: "暮紫",
    subtitle: "钟楼晚霞",
    swatchPaper: "#f6f2ff",
    swatchBrand: "#6253c7",
    swatchAccent: "#dc6b6c",
  },
  {
    key: "latte",
    label: "燕麦",
    subtitle: "自习暖咖",
    swatchPaper: "#faf4ea",
    swatchBrand: "#8b5c34",
    swatchAccent: "#cb5a3e",
  },
];

const KEY = "stu.theme";
const PALETTE_KEY = "stu.palette";
const PALETTE_SET = new Set<string>(PALETTE_OPTIONS.map((item) => item.key));

export function normalizePalette(value: string | null | undefined): PalettePref {
  return value && PALETTE_SET.has(value) ? (value as PalettePref) : "sweet";
}

/** the browser chrome / status bar colour per palette */
function barColor(palette: PalettePref, dark: boolean): string {
  switch (palette) {
    case "classic":
      return dark ? "#0b0e14" : "#f9f6f1";
    case "phoenix":
      return dark ? "#1e1612" : "#fff3e8";
    case "pine":
      return dark ? "#101a16" : "#eff8f2";
    case "lilac":
      return dark ? "#171422" : "#f6f2ff";
    case "latte":
      return dark ? "#1b1713" : "#faf4ea";
    default:
      // 必须等于 tokens.css 里樱粉的 --bg，也要等于 android 的 res/values/colors.xml::paper。
      return dark ? "#1f161e" : "#fff0f1";
  }
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
  if (asked && PALETTE_SET.has(asked)) return asked as PalettePref;
  try {
    const saved = localStorage.getItem(PALETTE_KEY);
    if (saved && PALETTE_SET.has(saved)) return saved as PalettePref;
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

/** Applied to <html data-palette>; tokens do the rest without altering motion or features. */
export function usePalette() {
  const [palette, setPalette] = useState<PalettePref>(readPalette);

  useEffect(() => {
    currentPalette = palette;
    apply(readPref());
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
