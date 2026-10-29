import puppeteer from "puppeteer-core";
import { existsSync } from "node:fs";

const CANDIDATES = [
  process.env.CHROME,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].filter(Boolean);

const exe = CANDIDATES.find((p) => existsSync(p));
if (!exe) {
  console.error("no chrome found");
  process.exit(1);
}

const url = process.argv[2] ?? "http://localhost:5180/?preview=1&tab=me";
const out = process.argv[3] ?? "shots/me-live.png";

const browser = await puppeteer.launch({
  executablePath: exe,
  headless: "new",
  args: ["--no-sandbox", "--hide-scrollbars", "--force-color-profile=srgb"],
});
const page = await browser.newPage();
await page.setViewport({ width: 390, height: 900, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
await page.goto(url, { waitUntil: "networkidle2", timeout: 60000 });
await new Promise((r) => setTimeout(r, 1200));
const dims = await page.evaluate(() => ({
  h: document.querySelector(".page")?.scrollHeight ?? document.body.scrollHeight,
  over: [...document.querySelectorAll(".stack-3 > *")].some((el) => el.getBoundingClientRect().height === 0),
}));
console.log("page metrics:", JSON.stringify(dims));
await page.screenshot({ path: out, fullPage: true });
console.log("saved", out);
await browser.close();
