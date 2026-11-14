/**
 * Package the approved L1 Clean Badge in icon-source/l1-clean-badge.
 * master.svg is the editable source; approved-512.png is the selected reference.
 *
 *   node scripts/icons.mjs            web, PWA and Apple touch icons
 *   node scripts/icons.mjs --android  also Android launcher and splash resources
 *   node scripts/icons.mjs --sheet    desktop sizes and launcher masks for review
 */
import { existsSync } from "node:fs";
import { mkdir, readFile, writeFile, copyFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const PUBLIC = path.join(ROOT, "public");
const SOURCE = path.join(ROOT, "icon-source", "l1-clean-badge");
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
  const source = await readFile(path.join(SOURCE, "master.svg"), "utf8");
  const approved = "data:image/png;base64," + (await readFile(path.join(SOURCE, "approved-512.png"))).toString("base64");
  return page.evaluate(async ({ source, approved }) => {
    const canvas = size => Object.assign(document.createElement("canvas"), { width: size, height: size });
    const decode = async src => { const img = new Image(); img.src = src; await img.decode(); return img; };
    const svg = (body, size = 1024) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}">${body}</svg>`;
    const image = (href, size = 1024) => `<image href="${href}" width="${size}" height="${size}"/>`;
    const serialize = node => new XMLSerializer().serializeToString(node);
    const url = text => "data:image/svg+xml;charset=utf-8," + encodeURIComponent(text);
    const master = new DOMParser().parseFromString(source, "image/svg+xml").documentElement;
    if (master.localName !== "svg" || master.querySelector("parsererror")) throw Error("Invalid icon SVG");

    // Check the selected PNG against the original vector, before writing assets.
    const compare = canvas(512), cc = compare.getContext("2d", { willReadFrequently: true });
    cc.drawImage(await decode(approved), 0, 0, 512, 512);
    const expected = cc.getImageData(0, 0, 512, 512).data;
    cc.clearRect(0, 0, 512, 512);
    cc.drawImage(await decode(url(source)), 0, 0, 512, 512);
    const actual = cc.getImageData(0, 0, 512, 512).data;
    let difference = 0;
    for (let i = 0; i < expected.length; i++) difference += Math.abs(expected[i] - actual[i]);
    const meanDifference = difference / expected.length;
    if (meanDifference > 1) throw Error(`Vector source differs from the approved image: ${meanDifference}`);

    // Android/iOS own the final mask. Remove only the outer tile clip, leaving
    // the original paths, composition, colors, rim and internal occlusion intact.
    const fullRoot = master.cloneNode(true);
    const tile = fullRoot.querySelector('g[clip-path="url(#l1-badge-sqClip)"]');
    if (!tile) throw Error("L1 tile clip is missing");
    tile.removeAttribute("clip-path");
    const fullSvg = serialize(fullRoot);
    const full = canvas(1024), fc = full.getContext("2d");
    fc.drawImage(await decode(url(fullSvg)), 0, 0, 1024, 1024);

    // Android shows the central 72dp of a 108dp layer. Edge-clamped overscan
    // preserves the approved framing without transparent corners during motion.
    // The color composition stays together to preserve the translucent overlap.
    function overscan(source) {
      const c = canvas(1536), ctx = c.getContext("2d");
      const segments = [[0, 1, 0, 256], [0, 1024, 256, 1024], [1023, 1, 1280, 256]];
      for (const [sx, sw, dx, dw] of segments) for (const [sy, sh, dy, dh] of segments)
        ctx.drawImage(source, sx, sy, sw, sh, dx, dy, dw, dh);
      return svg(image(c.toDataURL(), 1536), 1536);
    }
    const monoRoot = fullRoot.cloneNode(true);
    for (const suffix of ["sky", "atmo", "vig", "rim"]) {
      monoRoot.querySelectorAll(`[fill="url(#l1-badge-${suffix})"], [stroke="url(#l1-badge-${suffix})"]`).forEach(node => node.remove());
    }
    for (const node of monoRoot.querySelectorAll("path")) {
      if (node.closest("defs")) continue;
      if (node.getAttribute("fill") !== "none") node.setAttribute("fill", "#ffffff");
      if (node.hasAttribute("stroke")) node.setAttribute("stroke", "#ffffff");
      node.removeAttribute("fill-opacity"); node.removeAttribute("stroke-opacity");
    }
    const mono = canvas(1024), mc = mono.getContext("2d");
    mc.drawImage(await decode(url(serialize(monoRoot))), 0, 0, 1024, 1024);
    const background = master.querySelector("#l1-badge-sky stop").getAttribute("stop-color");
    const fullImage = image(full.toDataURL());
    return {
      desktop: source, legacy: source, touch: fullSvg, maskable: fullSvg,
      round: svg(`<defs><clipPath id="circle"><circle cx="512" cy="512" r="512"/></clipPath></defs><g clip-path="url(#circle)">${fullImage}</g>`),
      foreground: overscan(full), themed: overscan(mono),
      background: svg(`<rect width="1024" height="1024" fill="${background}"/>`),
      sourceVerification: { meanChannelDifference: meanDifference, scale: "0–255" },
    };
  }, { source, approved });
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
  await copyFile(path.join(SOURCE, "approved-512.png"), path.join(PUBLIC, "icon-512.png"));
  await writeFile(path.join(PUBLIC, "icon.svg"), art.desktop + "\n");
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
  console.log("Editable-source verification:", art.sourceVerification);
  if (args.has("--sheet")) {
    await sheet(page, art);
  } else {
    await web(page, art);
    if (args.has("--android")) await android(page, art);
  }
} finally {
  await browser.close();
}
