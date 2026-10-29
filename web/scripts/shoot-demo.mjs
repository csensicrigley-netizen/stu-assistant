/**
 * Screenshot the demo shell (the phone showroom).
 *
 *   node scripts/shoot-demo.mjs            # needs the demo server on :8123
 */
import { mkdir } from "node:fs/promises";
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import puppeteer from "puppeteer-core";

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));

const URL = process.env.DEMO_URL ?? "http://127.0.0.1:8123/demo/";
const OUT = path.resolve("shots");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!CHROME) throw new Error("no chromium");
  await mkdir(OUT, { recursive: true });

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), "stu-demo-profile"),
    args: ["--no-sandbox", "--disable-gpu", "--font-render-hinting=none", "--hide-scrollbars"],
  });
  const page = await browser.newPage();

  // desktop showroom
  await page.setViewport({ width: 1560, height: 1180, deviceScaleFactor: 1 });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 45000 });
  // wait for all four iframes to finish loading the app
  await sleep(2200);
  await page.screenshot({ path: path.join(OUT, "demo-desktop.png"), fullPage: true });
  console.log("✓ demo-desktop.png");

  // a single phone, closer up
  const shot = await page.$(".phone");
  if (shot) {
    await shot.screenshot({ path: path.join(OUT, "demo-phone.png") });
    console.log("✓ demo-phone.png");
  }

  // what a real phone sees
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  await page.goto(URL, { waitUntil: "networkidle2" });
  await sleep(1600);
  await page.screenshot({ path: path.join(OUT, "demo-on-phone.png") });
  console.log("✓ demo-on-phone.png");

  await browser.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
