/**
 * Rehearse the Android App's first run in Chrome: the App's own bundle
 * (dist-android, no server address baked in), with Capacitor's Android
 * detection faked. Checks the server-address step, sign-up, campus connect
 * (backend in demo mode) and that data then comes from that server.
 *
 *   npx vite preview --outDir dist-android --port 5180   (serves the bundle)
 *   node scripts/e2e-native.mjs                          (backend on :8100, demo)
 *
 * This is not a device test: the Android WebView, status bar and back button
 * are not exercised here.
 */
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import puppeteer from "puppeteer-core";

const APP = process.env.APP_URL ?? "http://localhost:5180";
const SERVER = process.env.SERVER_URL ?? "http://127.0.0.1:8100";
const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
].find((p) => existsSync(p));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let failed = 0;
const check = (ok, what) => {
  console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
  if (!ok) failed++;
};

const browser = await puppeteer.launch({
  executablePath: CHROME,
  headless: true,
  userDataDir: path.join(os.tmpdir(), `stu-native-${Date.now()}`),
  args: ["--no-sandbox", "--disable-gpu", "--lang=zh-CN"],
});
const page = await browser.newPage();
await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
// what Capacitor looks for to decide it runs inside the Android App
await page.evaluateOnNewDocument(() => {
  window.androidBridge = { postMessage() {} };
});

const text = () => page.evaluate(() => document.body.innerText);
const clickText = async (selector, label) => {
  const ok = await page.evaluate(
    (sel, t) => {
      const el = [...document.querySelectorAll(sel)].find((b) => b.textContent?.trim().includes(t));
      if (!el) return false;
      el.click();
      return true;
    },
    selector,
    label,
  );
  if (!ok) throw new Error(`not found: ${selector} "${label}"`);
  await sleep(650);
};
const type = async (selector, value) => {
  await page.click(selector);
  await page.keyboard.down("Control");
  await page.keyboard.press("KeyA");
  await page.keyboard.up("Control");
  await page.keyboard.press("Backspace");
  await page.type(selector, value);
};
const waitFor = async (fn, ms = 20000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await page.evaluate(fn)) return true;
    await sleep(300);
  }
  return false;
};

await page.goto(`${APP}/?now=2026-10-28T10:20&theme=light`, { waitUntil: "networkidle2" });
check(await waitFor(() => document.body.innerText.includes("开始使用"), 15000), "first run shows the welcome screen");
check(await page.evaluate(() => window.Capacitor?.isNativePlatform?.() === true), "bundle detects it runs as the Android App");
check(!(await text()).includes("下载安卓 App"), "no 'download the App' link inside the App");
check(!(await page.evaluate(() => "serviceWorker" in navigator && navigator.serviceWorker.controller)), "no service worker in the App bundle");

await clickText("button", "开始使用");
check((await text()).includes("服务器地址"), "sign-up asks for the server address (none baked in)");
await page.screenshot({ path: path.resolve("shots", "native-1-server.png") });

// a wrong address is caught before sign-up
await type('input[type="url"]', "https://nonexistent.invalid");
await type('input[type="email"]', `app${Date.now()}@example.com`);
await type('input[type="password"]', "native-passphrase-1");
await clickText("button.btn--primary", "创建并继续");
check(await waitFor(() => document.body.innerText.includes("服务器地址连不上"), 15000), "an unreachable server address is reported");

await type('input[type="url"]', SERVER);
await clickText("button.btn--primary", "创建并继续");
const reached = await waitFor(() => document.body.innerText.includes("连接校园账号"), 15000);
if (!reached) {
  const shown = await page.evaluate(() => ({
    error: document.querySelector(".field__error")?.textContent,
    server: document.querySelector('input[type="url"]')?.value,
    stored: localStorage.getItem("stu.api"),
  }));
  console.log("   form says:", JSON.stringify(shown));
}
check(reached, "valid address → account created → campus connect step");
check(await page.evaluate(() => localStorage.getItem("stu.api")) === SERVER, "server address remembered on the device");

await type('input[autocomplete="username"]', "24demo02");
await type('input[autocomplete="current-password"]', "not-a-real-password");
await clickText("button", "连接");
check(
  await waitFor(() => document.querySelector(".pill--live") !== null && document.body.innerText.includes("生物统计学"), 30000),
  "after connecting, the App shows live data from that server",
);
await page.screenshot({ path: path.resolve("shots", "native-2-today.png") });

await clickText(".tabbar__btn", "我的");
check((await text()).includes("127.0.0.1:8100"), "我的 shows which server the App is connected to");

check(errors.length === 0, `no uncaught page errors${errors.length ? `: ${errors.slice(0, 2).join(" | ")}` : ""}`);
await browser.close();
console.log(`\n${failed === 0 ? "NATIVE E2E PASS" : `${failed} step(s) failed`}`);
process.exit(failed ? 1 : 0);
