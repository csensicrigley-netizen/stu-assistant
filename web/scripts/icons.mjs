/**
 * Rasterize the editable desktop icon in public/icon.svg.
 *
 *   node scripts/icons.mjs            web, PWA and Apple touch icons
 *   node scripts/icons.mjs --android  also Android launcher and splash resources
 *   node scripts/icons.mjs --sheet    desktop sizes and launcher masks for review
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PUBLIC = path.join(ROOT, "public");
const ANDROID_RES = path.join(ROOT, "android", "app", "src", "main", "res");
const DENSITIES = [["mdpi", 1], ["hdpi", 1.5], ["xhdpi", 2], ["xxhdpi", 3], ["xxxhdpi", 4]];

const CHROME = [
  process.env.CHROME,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
].find((candidate) => candidate && existsSync(candidate));

async function loadArtwork(page) {
  const source = await readFile(path.join(PUBLIC, "icon.svg"), "utf8");
  return page.evaluate((source) => {
    const master = new DOMParser().parseFromString(source, "image/svg+xml").documentElement;
    if (master.localName !== "svg") throw new Error("public/icon.svg is not valid SVG");
    const box = master.viewBox.baseVal;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;

    function variant({ shape, scale = 1, layer }) {
      const svg = master.cloneNode(true);
      const tile = svg.querySelector("#tile");
      const mark = svg.querySelector("#mark");
      if (shape === "full") tile.setAttribute("rx", "0");
      if (shape === "circle") tile.setAttribute("rx", String(box.width / 2));
      if (layer === "foreground" || layer === "themed") {
        tile.remove();
        mark.parentElement.removeAttribute("clip-path");
      }
      if (layer === "background") mark.remove();
      if (layer === "themed") mark.setAttribute("href", "#themed-bell");
      if (scale !== 1) {
        mark.setAttribute("transform", `translate(${cx} ${cy}) scale(${scale}) translate(${-cx} ${-cy})`);
      }
      return new XMLSerializer().serializeToString(svg);
    }

    // The full adaptive layers are 108dp. The landmark fits the central 66dp circle.
    const adaptiveScale = .62;
    return {
      desktop: source,
      touch: variant({ shape: "full", scale: .96 }),
      maskable: variant({ shape: "full", scale: .79 }),
      legacy: variant({ scale: .96 }),
      round: variant({ shape: "circle", scale: .89 }),
      foreground: variant({ layer: "foreground", scale: adaptiveScale }),
      themed: variant({ layer: "themed", scale: adaptiveScale }),
      background: variant({ layer: "background", shape: "full" }),
    };
  }, source);
}

function imageUrl(svg) {
  return "data:image/svg+xml;base64," + Buffer.from(svg).toString("base64");
}

function svgImage(svg, size) {
  return `<img src="${imageUrl(svg)}" width="${size}" height="${size}" alt="">`;
}

async function png(page, svg, size, destination) {
  await page.setViewport({ width: size, height: size, deviceScaleFactor: 1 });
  await page.setContent(`<style>body{margin:0}img{display:block}</style>${svgImage(svg, size)}`);
  await page.$eval("img", (img) => img.decode());
  await mkdir(path.dirname(destination), { recursive: true });
  await page.screenshot({ path: destination, omitBackground: true });
}

async function web(page, art) {
  await png(page, art.desktop, 192, path.join(PUBLIC, "icon-192.png"));
  await png(page, art.desktop, 512, path.join(PUBLIC, "icon-512.png"));
  await png(page, art.desktop, 32, path.join(PUBLIC, "favicon-32.png"));
  await png(page, art.maskable, 512, path.join(PUBLIC, "icon-maskable-512.png"));
  await png(page, art.touch, 180, path.join(PUBLIC, "apple-touch-icon.png"));
  console.log("Web icons → public/");
}

async function android(page, art) {
  if (!existsSync(ANDROID_RES)) throw new Error("Android resources directory is missing");
  for (const [name, density] of DENSITIES) {
    const dir = path.join(ANDROID_RES, `mipmap-${name}`);
    const legacy = 48 * density;
    const layer = 108 * density;
    await png(page, art.legacy, legacy, path.join(dir, "ic_launcher.png"));
    await png(page, art.round, legacy, path.join(dir, "ic_launcher_round.png"));
    await png(page, art.foreground, layer, path.join(dir, "ic_launcher_foreground.png"));
    await png(page, art.background, layer, path.join(dir, "ic_launcher_background.png"));
    await png(page, art.themed, layer, path.join(dir, "ic_launcher_monochrome.png"));
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
  await png(page, art.desktop, 448, path.join(ANDROID_RES, "drawable-nodpi", "splash_mark.png"));
  console.log("Android launcher and splash icons → android/app/src/main/res/");
}

async function sheet(page, art) {
  const sizes = [96, 64, 48, 32, 24, 16];
  const masks = [["圆形", "50%"], ["圆角方形", "22%"], ["水滴形", "50% 50% 50% 18%"]];
  const sample = (image, label) => `<figure>${image}<figcaption>${label}</figcaption></figure>`;
  // Launchers mask the middle 72dp of a 108dp layer at rest.
  const masked = (radius, themed = false) => {
    const layers = themed
      ? `<i style="background:#514366;mask:center / contain url('${imageUrl(art.themed)}') no-repeat"></i>`
      : svgImage(art.background, 162) + svgImage(art.foreground, 162);
    return `<div class="mask" style="border-radius:${radius}"><div class="layers">${layers}</div></div>`;
  };
  const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><style>
    *{box-sizing:border-box}body{margin:0;background:#f6f4f8;color:#352e43;font:16px "Segoe UI","Microsoft YaHei",sans-serif}
    main{width:860px;padding:36px;display:grid;gap:28px}h1,h2,p,figure{margin:0}
    header,section,.sizes,.masks{display:flex}header{gap:40px;align-items:center}
    h1{font-size:36px;font-weight:650;letter-spacing:.04em}h2{font-weight:500;font-size:16px}
    header p,figcaption{color:#665e74}header p{margin:3px 0 18px}
    .sizes{align-items:flex-end;gap:16px}figure{display:grid;justify-items:center;gap:8px}img{display:block}
    figcaption{font-size:13px}.sizes figcaption{font-family:ui-monospace,Consolas,monospace}
    section{padding:22px 30px;background:#272332;color:#efeaf7;border-radius:22px;align-items:center;justify-content:space-between}
    .masks{align-items:flex-start;gap:24px}.masks figcaption{color:#d3cadf}
    .mask{width:108px;height:108px;background:#e0d8ec;overflow:hidden;position:relative}
    .layers{position:absolute;width:150%;height:150%;left:-25%;top:-25%}
    .layers img,.layers i{position:absolute;inset:0;width:100%;height:100%}
  </style><main><header>${svgImage(art.desktop, 208)}<div><h1>真理钟</h1>
    <p>STU 事项助手 · 桌面图标</p><div class="sizes">
    ${sizes.map((size) => sample(svgImage(art.desktop, size), `${size}px`)).join("")}</div></div></header>
    <section><h2>Android 桌面</h2><div class="masks">
    ${masks.map(([label, radius]) => sample(masked(radius), label)).join("")}
    ${sample(masked("50%", true), "系统主题色")}</div></section></main></html>`;
  await page.setViewport({ width: 860, height: 460, deviceScaleFactor: 1 });
  await page.setContent(html);
  await page.evaluate(async () => {
    await Promise.all(Array.from(document.images, (image) => image.decode()));
    await document.fonts.ready;
  });
  const out = path.join(ROOT, "shots", "icon-sheet.png");
  await mkdir(path.dirname(out), { recursive: true });
  await (await page.$("main")).screenshot({ path: out });
  console.log(`Icon preview → ${out}`);
}

const args = new Set(process.argv.slice(2));
if (!CHROME) throw new Error("Set CHROME to the path of a Chrome or Chromium executable.");
const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  args: ["--no-sandbox", "--disable-gpu"],
});
try {
  const page = await browser.newPage();
  const art = await loadArtwork(page);
  if (args.has("--sheet")) {
    await sheet(page, art);
  } else {
    await web(page, art);
    if (args.has("--android")) await android(page, art);
  }
} finally {
  await browser.close();
}
