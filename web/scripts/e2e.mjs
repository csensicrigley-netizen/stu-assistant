/**
 * End-to-end check of the browser version against a running backend (demo mode).
 *
 *   node scripts/e2e.mjs           (backend on :8100 serving web/dist, STU_APP_DEMO=1)
 *
 * Walks: welcome → register → connect campus account → first sync → live data,
 * then sign-out → sign-in again, and screenshots each step to shots/e2e-*.png.
 */
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import puppeteer from "puppeteer-core";

const BASE = process.env.E2E_URL ?? "http://127.0.0.1:8100";
const NOW = "2026-10-28T10:20";
const OUT = path.resolve("shots");
const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const steps = [];
let failed = 0;
const check = (ok, what) => {
  steps.push(`${ok ? "ok  " : "FAIL"} ${what}`);
  console.log(`${ok ? "ok  " : "FAIL"} ${what}`);
  if (!ok) failed++;
};

async function main() {
  await mkdir(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), `stu-e2e-${Date.now()}`),
    args: ["--no-sandbox", "--disable-gpu", "--lang=zh-CN"],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 2 });
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));

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

  // health + headers
  const health = await page.goto(`${BASE}/v1/health`);
  check(health.status() === 200, "GET /v1/health → 200");
  const api404 = await page.goto(`${BASE}/v1/does-not-exist`);
  check(api404.status() === 404 && (await text()).includes("not_found"), "unknown API path → JSON 404, not index.html");

  // the Android App is offered by the website itself
  const apk = await fetch(`${BASE}/download/stu-assistant.apk`, { method: "HEAD" });
  const apkSize = Number(apk.headers.get("content-length") || 0);
  check(
    apk.status === 200 && apk.headers.get("content-type") === "application/vnd.android.package-archive" && apkSize > 1_000_000,
    `APK download served (${apk.status}, ${apk.headers.get("content-type")}, ${(apkSize / 1048576).toFixed(1)} MB)`,
  );
  const missing = await fetch(`${BASE}/download/nope.apk`);
  check(missing.status === 404, "missing download → 404, not the app shell");

  // 1 welcome
  const res = await page.goto(`${BASE}/?now=${NOW}&theme=light`, { waitUntil: "networkidle2" });
  check(res.status() === 200, "GET / → 200 (browser version served by the backend)");
  check(res.headers()["x-content-type-options"] === "nosniff", "security headers present");
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.reload({ waitUntil: "networkidle2" });
  await sleep(600);
  check((await text()).includes("开始使用"), "welcome screen shows");
  await page.screenshot({ path: path.join(OUT, "e2e-1-welcome.png") });

  // 2 register
  await clickText("button", "开始使用");
  const email = `e2e${Date.now()}@example.com`;
  await type('input[type="email"]', email);
  await type('input[type="password"]', "e2e-passphrase-123");
  await page.screenshot({ path: path.join(OUT, "e2e-2-register.png") });
  await clickText("button", "创建并继续");
  check(await waitFor(() => document.body.innerText.includes("连接校园账号")), "register → connect campus account step");
  check(await page.evaluate(() => Boolean(localStorage.getItem("stu.token"))), "token stored after register");

  // 3 bind (demo: no school system is contacted)
  await type('input[autocomplete="username"]', "24demo01");
  await type('input[autocomplete="current-password"]', "not-a-real-password");
  await page.screenshot({ path: path.join(OUT, "e2e-3-bind.png") });
  await clickText("button", "连接");
  check(await waitFor(() => document.querySelector(".page") !== null, 15000), "bind ok → app shell");

  // 4 first sync → live data
  const live = await waitFor(
    () => document.querySelector(".pill--live") !== null && document.body.innerText.includes("生物统计学"),
    30000,
  );
  check(live, "first sync done: live pill + server timetable on 今天");
  const body = await text();
  check(!body.includes("示例数据"), "no sample label once connected");
  check(body.includes("快到期") && body.includes("序列比对"), "server tasks shown under 快到期");
  await page.screenshot({ path: path.join(OUT, "e2e-4-today-live.png") });

  // 5 学业 tabs come from the server
  await clickText(".tabbar__btn", "学业");
  check((await text()).includes("第二次作业"), "学业 · 作业 from server");
  await clickText(".seg__btn", "考试");
  check((await text()).includes("生物信息学") && (await text()).includes("2027-01-12"), "学业 · 考试 from server");
  await clickText(".seg__btn", "成绩");
  check((await text()).includes("加权均分"), "学业 · 成绩 from server");

  // 6 我的 shows the account
  await clickText(".tabbar__btn", "我的");
  const me = await text();
  check(me.includes(email), "我的 shows the signed-in email");
  check(me.includes("已连接"), "我的 shows the connected campus account");
  await page.screenshot({ path: path.join(OUT, "e2e-5-me.png") });

  // 7 sign out → welcome; sign in again
  await clickText("button", "退出登录");
  check(await waitFor(() => document.body.innerText.includes("开始使用") || document.body.innerText.includes("登录助手账号")), "sign out → welcome");
  check(await page.evaluate(() => !localStorage.getItem("stu.token")), "token removed on sign out");
  if ((await text()).includes("开始使用")) await clickText("button", "开始使用");
  await clickText(".seg__btn", "登录");
  await type('input[type="email"]', email);
  await type('input[type="password"]', "e2e-passphrase-123");
  await clickText("button.btn--primary", "登录");
  check(await waitFor(() => document.body.innerText.includes("连接校园账号")), "sign in → bind step offered");
  await clickText("button", "稍后再连");
  check(await waitFor(() => document.body.innerText.includes("生物统计学"), 20000), "after sign-in, server data back (already connected)");

  // 8 an expired token sends the user back to sign-in
  await page.evaluate(() => localStorage.setItem("stu.token", "x.eyJzdWIiOiJub2JvZHkifQ.y"));
  await page.reload({ waitUntil: "networkidle2" });
  check(await waitFor(() => document.body.innerText.includes("登录已过期")), "invalid token → back to sign-in with a message");

  // 9 desktop: split welcome + sidebar shell
  await page.setViewport({ width: 1366, height: 860, deviceScaleFactor: 1 });
  await page.evaluate(() => localStorage.clear());
  await page.goto(`${BASE}/?now=${NOW}&theme=light`, { waitUntil: "networkidle2" });
  await sleep(700);
  await page.screenshot({ path: path.join(OUT, "e2e-6-desktop-welcome.png") });
  check((await text()).includes("创建助手账号"), "desktop welcome opens straight on the form");

  check(errors.length === 0, `no uncaught page errors${errors.length ? `: ${errors.slice(0, 3).join(" | ")}` : ""}`);
  await browser.close();
  console.log(`\n${failed === 0 ? "E2E PASS" : `${failed} step(s) failed`}`);
  process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
