/**
 * The app mark, and every raster the platforms need, from one definition.
 *
 *   node scripts/icons.mjs            web icons (public/)
 *   node scripts/icons.mjs --android  also Android launcher + splash resources
 *   node scripts/icons.mjs --sheet    a review sheet in shots/icon-sheet.png
 *
 * The mark: an S drawn as one continuous route (three runs, two turns) — the
 * day's path between classrooms, and the S of STU — with a coral bead on the
 * middle run: where you are right now. Paper on sea blue, one warm accent,
 * the same three colours as the interface.
 */
import { existsSync } from "node:fs";
import { mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PUBLIC = path.join(ROOT, "public");
const ANDROID_RES = path.join(ROOT, "android", "app", "src", "main", "res");

const C = {
  hi: "#9d8bf2",
  lo: "#6a55b0",
  paper: "#fff7f4",
  coral: "#ff9d7e",
};

/* ------------------------------------------------------------- geometry */

// All numbers live on a 1024 grid, glyph centred at (512, 512).
const R = 124; // turn radius (centre line)
const W = 100; // stroke
const HALF = 106; // half the straight run between turn centres
const TAIL = 44; // how far the terminals run past the turn centres
const BEAD = 62; // bead radius
const GAP = 17; // clear space around the bead

function glyph({ scale = 1, paper = C.paper, coral = C.coral, id = "g" } = {}) {
  const cx = 512;
  const cy = 512;
  const y1 = cy - 2 * R;
  const y3 = cy + 2 * R;
  const xl = cx - HALF;
  const xr = cx + HALF;
  const d = [
    `M${xr + TAIL} ${y1}`,
    `H${xl}`,
    `A${R} ${R} 0 0 0 ${xl} ${cy}`,
    `H${xr}`,
    `A${R} ${R} 0 0 1 ${xr} ${y3}`,
    `H${xl - TAIL}`,
  ].join("");
  const t = `translate(512 512) scale(${scale}) translate(-512 -512)`;
  return `
    <mask id="${id}-cut" maskUnits="userSpaceOnUse" x="0" y="0" width="1024" height="1024">
      <rect width="1024" height="1024" fill="#fff"/>
      <circle cx="${cx}" cy="${cy}" r="${BEAD + GAP}" fill="#000"/>
    </mask>
    <g transform="${t}">
      <path d="${d}" fill="none" stroke="${paper}" stroke-width="${W}" stroke-linecap="round" stroke-linejoin="round" mask="url(#${id}-cut)"/>
      <circle cx="${cx}" cy="${cy}" r="${BEAD}" fill="${coral}"/>
    </g>`;
}

const defsBg = (id = "bg") => `
  <linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1">
    <stop offset="0" stop-color="${C.hi}"/><stop offset="1" stop-color="${C.lo}"/>
  </linearGradient>
  <radialGradient id="${id}-hl" cx="0.2" cy="0.1" r="0.8">
    <stop offset="0" stop-color="#fff" stop-opacity="0.18"/><stop offset="1" stop-color="#fff" stop-opacity="0"/>
  </radialGradient>`;

/** shape: "rounded" (any-purpose), "full" (maskable / iOS), "circle" (legacy round) */
function iconSvg({ shape = "rounded", scale = 1 } = {}) {
  const bg =
    shape === "rounded"
      ? `<rect width="1024" height="1024" rx="232" fill="url(#bg)"/><rect width="1024" height="1024" rx="232" fill="url(#bg-hl)"/>`
      : shape === "circle"
        ? `<circle cx="512" cy="512" r="512" fill="url(#bg)"/><circle cx="512" cy="512" r="512" fill="url(#bg-hl)"/>`
        : `<rect width="1024" height="1024" fill="url(#bg)"/><rect width="1024" height="1024" fill="url(#bg-hl)"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><defs>${defsBg()}</defs>${bg}${glyph({ scale })}</svg>`;
}

/** Android adaptive layers live on a 108dp canvas; only the central 66dp is safe. */
const ADAPTIVE_SCALE = 0.7;
const foregroundSvg = () =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">${glyph({ scale: ADAPTIVE_SCALE, id: "fg" })}</svg>`;
const backgroundSvg = () =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024"><defs>${defsBg()}</defs><rect width="1024" height="1024" fill="url(#bg)"/><rect width="1024" height="1024" fill="url(#bg-hl)"/></svg>`;
const monochromeSvg = () =>
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">${glyph({ scale: ADAPTIVE_SCALE, paper: "#fff", coral: "#fff", id: "mo" })}</svg>`;

/* -------------------------------------------------------------- render */

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));

async function withBrowser(fn) {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), "stu-icons-profile"),
    args: ["--no-sandbox", "--disable-gpu"],
  });
  try {
    const page = await browser.newPage();
    await fn(page);
  } finally {
    await browser.close();
  }
}

async function png(page, svg, w, h, file) {
  await page.setViewport({ width: w, height: h, deviceScaleFactor: 1 });
  await page.setContent(
    `<!doctype html><html><body style="margin:0;background:transparent">${svg.replace("<svg ", `<svg width="${w}" height="${h}" `)}</body></html>`,
  );
  await mkdir(path.dirname(file), { recursive: true });
  await page.screenshot({ path: file, omitBackground: true, clip: { x: 0, y: 0, width: w, height: h } });
}

const DENSITIES = [
  ["mdpi", 1],
  ["hdpi", 1.5],
  ["xhdpi", 2],
  ["xxhdpi", 3],
  ["xxxhdpi", 4],
];

async function web(page) {
  await writeFile(path.join(PUBLIC, "icon.svg"), iconSvg({ shape: "rounded" }));
  await png(page, iconSvg({ shape: "rounded" }), 192, 192, path.join(PUBLIC, "icon-192.png"));
  await png(page, iconSvg({ shape: "rounded" }), 512, 512, path.join(PUBLIC, "icon-512.png"));
  await png(page, iconSvg({ shape: "rounded" }), 32, 32, path.join(PUBLIC, "favicon-32.png"));
  // maskable: the platform crops to a circle of 80% diameter at worst
  await png(page, iconSvg({ shape: "full", scale: 0.8 }), 512, 512, path.join(PUBLIC, "icon-maskable-512.png"));
  await png(page, iconSvg({ shape: "full", scale: 0.92 }), 180, 180, path.join(PUBLIC, "apple-touch-icon.png"));
  console.log("web icons → public/");
}

async function android(page) {
  if (!existsSync(ANDROID_RES)) throw new Error("android/ project not found; run `npx cap add android` first");
  for (const [name, k] of DENSITIES) {
    const dir = path.join(ANDROID_RES, `mipmap-${name}`);
    const legacy = Math.round(48 * k);
    const layer = Math.round(108 * k);
    await png(page, iconSvg({ shape: "rounded", scale: 0.94 }), legacy, legacy, path.join(dir, "ic_launcher.png"));
    await png(page, iconSvg({ shape: "circle", scale: 0.86 }), legacy, legacy, path.join(dir, "ic_launcher_round.png"));
    await png(page, foregroundSvg(), layer, layer, path.join(dir, "ic_launcher_foreground.png"));
    await png(page, backgroundSvg(), layer, layer, path.join(dir, "ic_launcher_background.png"));
    await png(page, monochromeSvg(), layer, layer, path.join(dir, "ic_launcher_monochrome.png"));
  }
  const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
`;
  const any = path.join(ANDROID_RES, "mipmap-anydpi-v26");
  await mkdir(any, { recursive: true });
  await writeFile(path.join(any, "ic_launcher.xml"), adaptive);
  await writeFile(path.join(any, "ic_launcher_round.xml"), adaptive);

  // pre-Android-12 launch screen: drawable/splash.xml centres this mark on
  // the theme's paper colour, so one bitmap serves every screen and theme
  await png(page, iconSvg({ shape: "rounded" }), 448, 448, path.join(ANDROID_RES, "drawable-nodpi", "splash_mark.png"));
  console.log("android launcher + splash mark → android/app/src/main/res/");
}

async function sheet(page) {
  const masks = [
    ["圆形", "50%"],
    ["圆角方", "22%"],
    ["水滴", "50% 50% 50% 18%"],
  ];
  const tile = (inner, size, radius) =>
    `<div style="width:${size}px;height:${size}px;border-radius:${radius};overflow:hidden;position:relative;box-shadow:0 2px 8px rgba(0,0,0,.25)">${inner}</div>`;
  const layered = (size) =>
    `<div style="position:absolute;inset:0">${backgroundSvg().replace("<svg ", `<svg width="${size}" height="${size}" `)}</div><div style="position:absolute;inset:0">${foregroundSvg().replace("<svg ", `<svg width="${size}" height="${size}" `)}</div>`;
  const html = `<!doctype html><html><body style="margin:0;padding:28px;background:#f4ecec;font:13px system-ui;color:#333">
    <div style="display:flex;gap:28px;align-items:flex-end">
      ${iconSvg().replace("<svg ", '<svg width="240" height="240" ')}
      ${[96, 64, 48, 32, 20, 16].map((s) => iconSvg().replace("<svg ", `<svg width="${s}" height="${s}" `)).join("")}
    </div>
    <p style="margin:22px 0 10px">Android 自适应图标（同一前景，不同启动器遮罩）</p>
    <div style="display:flex;gap:28px;align-items:center">
      ${masks.map(([name, r]) => `<div style="display:grid;gap:6px;justify-items:center">${tile(layered(132), 132, r)}<span>${name}</span></div>`).join("")}
      <div style="display:grid;gap:6px;justify-items:center">${tile(`<div style="position:absolute;inset:0;background:#dfe7d6"></div><div style="position:absolute;inset:0;filter:brightness(0) saturate(100%) invert(26%) sepia(16%) saturate(800%) hue-rotate(60deg)">${monochromeSvg().replace("<svg ", '<svg width="132" height="132" ')}</div>`, 132, "50%")}<span>主题图标</span></div>
    </div>
    <div style="display:flex;gap:24px;margin-top:24px;padding:22px;border-radius:18px;background:#16181d;align-items:center">
      ${[64, 48, 32].map((s) => iconSvg().replace("<svg ", `<svg width="${s}" height="${s}" `)).join("")}
      <span style="color:#ccc">深色桌面</span>
    </div>
  </body></html>`;
  await page.setViewport({ width: 900, height: 600, deviceScaleFactor: 1 });
  await page.setContent(html);
  const out = path.join(ROOT, "shots", "icon-sheet.png");
  await mkdir(path.dirname(out), { recursive: true });
  await page.screenshot({ path: out, fullPage: true });
  console.log(`review sheet → ${out}`);
}

const args = new Set(process.argv.slice(2));
await withBrowser(async (page) => {
  if (args.has("--sheet")) return sheet(page);
  await web(page);
  if (args.has("--android")) await android(page);
});
