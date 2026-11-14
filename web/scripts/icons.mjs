/**
 * Truth Bell L1 — Unified Web & Android (Light Porcelain + Dark Obsidian) icon pipeline.
 *
 *   node scripts/icons.mjs            web icons (public/)
 *   node scripts/icons.mjs --android  also Android launcher (default + -night) + splash + native badge
 *   node scripts/icons.mjs --sheet    a review sheet in shots/icon-sheet.png
 *
 * Master assets live in:
 *   ../docs/design-studies/truth-bell/arctic-l1-fade-monochrome-final/renders/
 */
import { existsSync } from "node:fs";
import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const REPO = path.resolve(ROOT, "..");
const RENDERS = path.join(
  REPO,
  "docs",
  "design-studies",
  "truth-bell",
  "arctic-l1-fade-monochrome-final",
  "renders",
);
const PUBLIC = path.join(ROOT, "public");
const ANDROID_RES = path.join(ROOT, "android", "app", "src", "main", "res");

const LIGHT_PREFIX = "truth-bell-l1-light-porcelain";
const DARK_PREFIX = "truth-bell-l1-dark-obsidian";

/**
 * Android Adaptive Icon safe scaling:
 * Android masks out the central 72dp of the 108dp adaptive layer (66.67%).
 * Scaling the 1024px Bare master to 0.65 places the entire Truth Bell obelisk
 * and sweeping glass ribbon inside every squircle, circle, and teardrop mask
 * with zero clipping while matching the Composer Badge proportions.
 */
const ADAPTIVE_SCALE = 0.65;

const DENSITIES = [
  ["mdpi", 1],
  ["hdpi", 1.5],
  ["xhdpi", 2],
  ["xxhdpi", 3],
  ["xxxhdpi", 4],
];

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));

async function dataUri(filename) {
  const buf = await readFile(path.join(RENDERS, filename));
  return `data:image/png;base64,${buf.toString("base64")}`;
}

function fullBleedBgSvg(mode) {
  if (mode === "dark") {
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
      <defs>
        <linearGradient id="bg-dark" x1="0.5" y1="0" x2="0.5" y2="1">
          <stop offset="0" stop-color="#050505"/>
          <stop offset="1" stop-color="#161617"/>
        </linearGradient>
        <radialGradient id="glow-dark" cx="0.5" cy="0.16" r="0.72">
          <stop offset="0" stop-color="#ffffff" stop-opacity="0.09"/>
          <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
        </radialGradient>
      </defs>
      <rect width="1024" height="1024" fill="url(#bg-dark)"/>
      <rect width="1024" height="1024" fill="url(#glow-dark)"/>
    </svg>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
    <defs>
      <linearGradient id="bg-light" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0" stop-color="#EAEAEA"/>
        <stop offset="1" stop-color="#D4D4D4"/>
      </linearGradient>
      <radialGradient id="glow-light" cx="0.5" cy="0.14" r="0.75">
        <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>
        <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
      </radialGradient>
    </defs>
    <rect width="1024" height="1024" fill="url(#bg-light)"/>
    <rect width="1024" height="1024" fill="url(#glow-light)"/>
  </svg>`;
}

function compositeBareOnFullBleedSvg(mode, bareUri, scale = 0.88, circle = false) {
  const size = Math.round(1024 * scale);
  const offset = ((1024 - size) / 2).toFixed(2);
  const clipOpen = circle
    ? `<clipPath id="circle-clip"><circle cx="512" cy="512" r="512"/></clipPath><g clip-path="url(#circle-clip)">`
    : `<g>`;
  const innerBg = fullBleedBgSvg(mode)
    .replace(/^<svg[^>]*>/, "")
    .replace(/<\/svg>\s*$/, "");
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
    ${clipOpen}
      ${innerBg}
      <image href="${bareUri}" x="${offset}" y="${offset}" width="${size}" height="${size}"/>
    </g>
  </svg>`;
}

function adaptiveForegroundSvg(bareUri, scale = ADAPTIVE_SCALE) {
  const size = (1024 * scale).toFixed(2);
  const offset = ((1024 - 1024 * scale) / 2).toFixed(2);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
    <image href="${bareUri}" x="${offset}" y="${offset}" width="${size}" height="${size}"/>
  </svg>`;
}

function scaledBadgeSvg(badgeUri, scale = 1) {
  const size = (1024 * scale).toFixed(2);
  const offset = ((1024 - 1024 * scale) / 2).toFixed(2);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
    <image href="${badgeUri}" x="${offset}" y="${offset}" width="${size}" height="${size}"/>
  </svg>`;
}

async function dualSchemeFaviconSvg() {
  const light64 = await dataUri(`${LIGHT_PREFIX}-badge-64.png`);
  const dark64 = await dataUri(`${DARK_PREFIX}-badge-64.png`);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <style>
    .dark { display: none; }
    @media (prefers-color-scheme: dark) {
      .light { display: none; }
      .dark { display: inline; }
    }
  </style>
  <image class="light" href="${light64}" width="64" height="64"/>
  <image class="dark" href="${dark64}" width="64" height="64"/>
</svg>
`;
}

async function withBrowser(fn) {
  if (!CHROME) throw new Error("Chrome or Edge executable not found for icon rasterization");
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), `stu-truth-bell-icons-${process.pid}`),
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
    `<!doctype html><html><body style="margin:0;background:transparent;overflow:hidden">${svg.replace(
      "<svg ",
      `<svg width="${w}" height="${h}" style="display:block" `,
    )}</body></html>`,
    { waitUntil: "load" },
  );
  await mkdir(path.dirname(file), { recursive: true });
  await page.screenshot({
    path: file,
    omitBackground: true,
    clip: { x: 0, y: 0, width: w, height: h },
  });
}

async function web(page) {
  await mkdir(PUBLIC, { recursive: true });

  // Exact WebGL master copies for crisp multi-DPI UI & PWA badges
  for (const size of [64, 128, 256]) {
    await copyFile(
      path.join(RENDERS, `${LIGHT_PREFIX}-badge-${size}.png`),
      path.join(PUBLIC, `truth-bell-light-${size}.png`),
    );
    await copyFile(
      path.join(RENDERS, `${DARK_PREFIX}-badge-${size}.png`),
      path.join(PUBLIC, `truth-bell-dark-${size}.png`),
    );
  }

  await copyFile(
    path.join(RENDERS, `${LIGHT_PREFIX}-badge-32.png`),
    path.join(PUBLIC, "favicon-light-32.png"),
  );
  await copyFile(
    path.join(RENDERS, `${DARK_PREFIX}-badge-32.png`),
    path.join(PUBLIC, "favicon-dark-32.png"),
  );
  await copyFile(
    path.join(RENDERS, `${LIGHT_PREFIX}-badge-32.png`),
    path.join(PUBLIC, "favicon-32.png"),
  );
  await copyFile(
    path.join(RENDERS, `${LIGHT_PREFIX}-badge-192.png`),
    path.join(PUBLIC, "icon-192.png"),
  );
  await copyFile(
    path.join(RENDERS, `${DARK_PREFIX}-badge-192.png`),
    path.join(PUBLIC, "icon-192-dark.png"),
  );
  await copyFile(
    path.join(RENDERS, `${LIGHT_PREFIX}-badge-512.png`),
    path.join(PUBLIC, "icon-512.png"),
  );
  await copyFile(
    path.join(RENDERS, `${DARK_PREFIX}-badge-512.png`),
    path.join(PUBLIC, "icon-512-dark.png"),
  );

  await writeFile(path.join(PUBLIC, "icon.svg"), await dualSchemeFaviconSvg());

  // Full-bleed opaque icons for iOS Home Screen (180x180) and PWA maskable (512x512)
  const lightBare1024 = await dataUri(`${LIGHT_PREFIX}-bare-1024.png`);
  await png(
    page,
    compositeBareOnFullBleedSvg("light", lightBare1024, 0.9, false),
    180,
    180,
    path.join(PUBLIC, "apple-touch-icon.png"),
  );
  await png(
    page,
    compositeBareOnFullBleedSvg("light", lightBare1024, 0.78, false),
    512,
    512,
    path.join(PUBLIC, "icon-maskable-512.png"),
  );

  console.log("web Truth Bell icons → public/");
}

async function android(page) {
  if (!existsSync(ANDROID_RES)) {
    throw new Error("android/ project not found; run `npx cap add android` first");
  }

  const lightBadge1024 = await dataUri(`${LIGHT_PREFIX}-badge-1024.png`);
  const darkBadge1024 = await dataUri(`${DARK_PREFIX}-badge-1024.png`);
  const lightBare1024 = await dataUri(`${LIGHT_PREFIX}-bare-1024.png`);
  const darkBare1024 = await dataUri(`${DARK_PREFIX}-bare-1024.png`);

  const variants = [
    { mode: "light", qualifier: "", badgeUri: lightBadge1024, bareUri: lightBare1024 },
    { mode: "dark", qualifier: "-night", badgeUri: darkBadge1024, bareUri: darkBare1024 },
  ];

  for (const v of variants) {
    for (const [name, k] of DENSITIES) {
      const dir = path.join(ANDROID_RES, `mipmap${v.qualifier}-${name}`);
      const legacy = Math.round(48 * k);
      const layer = Math.round(108 * k);
      await png(
        page,
        scaledBadgeSvg(v.badgeUri, 0.94),
        legacy,
        legacy,
        path.join(dir, "ic_launcher.png"),
      );
      await png(
        page,
        compositeBareOnFullBleedSvg(v.mode, v.bareUri, 0.84, true),
        legacy,
        legacy,
        path.join(dir, "ic_launcher_round.png"),
      );
      await png(
        page,
        adaptiveForegroundSvg(v.bareUri, ADAPTIVE_SCALE),
        layer,
        layer,
        path.join(dir, "ic_launcher_foreground.png"),
      );
      await png(
        page,
        fullBleedBgSvg(v.mode),
        layer,
        layer,
        path.join(dir, "ic_launcher_background.png"),
      );
    }
  }

  const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
`;
  const any = path.join(ANDROID_RES, "mipmap-anydpi-v26");
  await mkdir(any, { recursive: true });
  await writeFile(path.join(any, "ic_launcher.xml"), adaptive);
  await writeFile(path.join(any, "ic_launcher_round.xml"), adaptive);

  // Pre-Android-12 splash marks (light default + dark -night)
  const nodpiLight = path.join(ANDROID_RES, "drawable-nodpi");
  const nodpiDark = path.join(ANDROID_RES, "drawable-night-nodpi");
  await mkdir(nodpiLight, { recursive: true });
  await mkdir(nodpiDark, { recursive: true });

  await png(
    page,
    scaledBadgeSvg(lightBadge1024, 1),
    448,
    448,
    path.join(nodpiLight, "splash_mark.png"),
  );
  await png(
    page,
    scaledBadgeSvg(darkBadge1024, 1),
    448,
    448,
    path.join(nodpiDark, "splash_mark.png"),
  );

  // In-App Compose NativeLogo master Badge PNGs (256px crisp for 64dp @3x/4x)
  await copyFile(
    path.join(RENDERS, `${LIGHT_PREFIX}-badge-256.png`),
    path.join(nodpiLight, "truth_bell_badge_light.png"),
  );
  await copyFile(
    path.join(RENDERS, `${DARK_PREFIX}-badge-256.png`),
    path.join(nodpiLight, "truth_bell_badge_dark.png"),
  );

  console.log("android launcher (light + -night) + splash + native badge → android/app/src/main/res/");
}

async function sheet(page) {
  const lightBadge = await dataUri(`${LIGHT_PREFIX}-badge-512.png`);
  const darkBadge = await dataUri(`${DARK_PREFIX}-badge-512.png`);
  const lightBare = await dataUri(`${LIGHT_PREFIX}-bare-512.png`);
  const darkBare = await dataUri(`${DARK_PREFIX}-bare-512.png`);
  const masks = [
    ["圆形", "50%"],
    ["圆角方", "22.5%"],
    ["水滴", "50% 50% 50% 18%"],
  ];
  const tile = (inner, size, radius) =>
    `<div style="width:${size}px;height:${size}px;border-radius:${radius};overflow:hidden;position:relative;box-shadow:0 4px 14px rgba(0,0,0,.18)">${inner}</div>`;
  const layered = (mode, bareUri, size) =>
    `<div style="position:absolute;inset:0">${fullBleedBgSvg(mode).replace("<svg ", `<svg width="${size}" height="${size}" `)}</div><div style="position:absolute;inset:0">${adaptiveForegroundSvg(bareUri).replace("<svg ", `<svg width="${size}" height="${size}" `)}</div>`;

  const html = `<!doctype html><html><body style="margin:0;padding:32px;background:#f7f7f8;font:13px system-ui,sans-serif;color:#111">
    <div style="display:flex;gap:32px;align-items:center;margin-bottom:24px">
      <div><img src="${lightBadge}" width="180" height="180" style="display:block"/><div style="margin-top:8px;text-align:center;font-weight:600">霜瓷浅灰 Badge</div></div>
      <div><img src="${darkBadge}" width="180" height="180" style="display:block"/><div style="margin-top:8px;text-align:center;font-weight:600">黑曜霜晶 Badge</div></div>
      <div style="display:flex;gap:14px;align-items:flex-end">
        ${[64, 48, 32, 24, 16].map((s) => `<img src="${lightBadge}" width="${s}" height="${s}"/>`).join("")}
      </div>
      <div style="display:flex;gap:14px;align-items:flex-end;background:#111317;padding:14px 18px;border-radius:16px">
        ${[64, 48, 32, 24, 16].map((s) => `<img src="${darkBadge}" width="${s}" height="${s}"/>`).join("")}
      </div>
    </div>
    <p style="margin:0 0 10px;font-weight:600">Android 自适应图标（浅色默认 + 深色 -night）</p>
    <div style="display:flex;gap:24px;align-items:center">
      ${masks.map(([n, r]) => `<div style="display:grid;gap:6px;justify-items:center">${tile(layered("light", lightBare, 116), 116, r)}<span>浅色·${n}</span></div>`).join("")}
      ${masks.map(([n, r]) => `<div style="display:grid;gap:6px;justify-items:center">${tile(layered("dark", darkBare, 116), 116, r)}<span>深色·${n}</span></div>`).join("")}
    </div>
  </body></html>`;

  await page.setViewport({ width: 980, height: 470, deviceScaleFactor: 2 });
  await page.setContent(html, { waitUntil: "load" });
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
