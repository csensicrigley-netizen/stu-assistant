/**
 * Capture every screen at phone width with the installed Chrome.
 *
 *   node scripts/shoot.mjs            (dev server on :5180)
 *
 * The clock is pinned with ?now= so each state is reproducible:
 *   Wed 2026-10-28 10:20  — in class (生物统计学), evening class still ahead
 *   Wed 2026-10-28 13:10  — between classes, room change ahead
 *   Thu 2026-10-01 10:00  — National Day, no classes
 */
import { mkdir, readdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import puppeteer from "puppeteer-core";

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));
if (!CHROME) throw new Error("no chromium binary found");

const BASE = process.env.SHOOT_URL ?? "http://localhost:5180";
const OUT = path.resolve("shots");
/** optional name prefix, so successive review rounds never overwrite each other */
const PREFIX = process.env.SHOT_PREFIX ?? "";
const PHONE = { width: 390, height: 844, deviceScaleFactor: 2 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** optional palette override: SHOT_PALETTE=classic shoots the sea-blue scheme */
const PALETTE = process.env.SHOT_PALETTE ?? "";
const withPalette = (q) => `${BASE}/?${q}${PALETTE ? `&palette=${PALETTE}` : ""}`;
const url = (q) => withPalette(`preview=1&${q}`);

async function main() {
  await mkdir(OUT, { recursive: true });
  // a fresh run replaces the old images; the folder itself may be held open
  // by an editor or Explorer, so only its files are removed
  if (!PREFIX) {
    for (const name of await readdir(OUT)) {
      if (name.endsWith(".png")) await rm(path.join(OUT, name), { force: true });
    }
  }

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), "stu-shoot-profile-v2"),
    args: ["--no-sandbox", "--disable-gpu", "--font-render-hinting=none", "--hide-scrollbars", "--lang=zh-CN"],
  });
  const page = await browser.newPage();
  await page.setViewport(PHONE);

  const open = async (q, wait = 700) => {
    await page.goto(url(q), { waitUntil: "networkidle2", timeout: 30000 });
    await sleep(wait);
  };
  // a fixed tab bar would be painted mid-page in a full-page capture
  const shot = async (name, full = false) => {
    if (full) await page.addStyleTag({ content: ".tabbar{display:none!important}" });
    await page.screenshot({ path: path.join(OUT, `${PREFIX}${name}.png`), fullPage: full });
    if (full) await page.evaluate(() => document.querySelectorAll("style").forEach((s) => s.textContent?.startsWith(".tabbar{display:none") && s.remove()));
    console.log(`ok ${PREFIX}${name}.png`);
  };
  const click = async (selector, text) => {
    const ok = await page.evaluate(
      (sel, t) => {
        const el = [...document.querySelectorAll(sel)].find((b) => !t || b.textContent?.includes(t));
        if (!el) return false;
        el.click();
        return true;
      },
      selector,
      text ?? "",
    );
    if (!ok) throw new Error(`not found: ${selector} ${text ?? ""}`);
    await sleep(600);
  };

  for (const theme of ["light", "dark"]) {
    const t = `theme=${theme}`;
    await open(`${t}&now=2026-10-28T10:20`);
    await shot(`${theme}-01-today-live`);
    await shot(`${theme}-01-today-live-full`, true);

    await open(`${t}&now=2026-10-28T13:10`);
    await shot(`${theme}-02-today-between`);

    await open(`${t}&now=2026-10-28T10:20&tab=week`);
    await shot(`${theme}-03-week`);
    await shot(`${theme}-03-week-full`, true);

    await open(`${t}&now=2026-10-28T10:20&tab=study`);
    await shot(`${theme}-04-study-tasks`, true);
    await click(".seg__btn", "考试");
    await shot(`${theme}-05-study-exams`, true);
    await click(".seg__btn", "成绩");
    await shot(`${theme}-06-study-grades`, true);

    await open(`${t}&now=2026-10-28T10:20&tab=campus`);
    await shot(`${theme}-07-campus-notices`, true);
    await click(".seg__btn", "生活");
    await sleep(600);
    await shot(`${theme}-08-campus-life`, true);
    await click(".seg__btn", "校历");
    await shot(`${theme}-09-campus-calendar`, true);

    await open(`${t}&now=2026-10-28T10:20&tab=me`);
    await shot(`${theme}-10-me`, true);
  }

  // detail sheet
  await open("theme=light&now=2026-10-28T10:20&tab=week");
  await click(".lesson");
  await sleep(500);
  await shot("light-11-sheet");

  // holiday
  await open("theme=light&now=2026-10-01T10:00");
  await shot("light-12-today-holiday");

  // first run (no preview flag)
  await page.goto(withPalette("theme=light"), { waitUntil: "networkidle2" });
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.goto(withPalette("theme=light"), { waitUntil: "networkidle2" });
  await sleep(600);
  await shot("light-00-welcome");

  // narrow + tablet
  await page.setViewport({ width: 360, height: 780, deviceScaleFactor: 2 });
  await open("theme=light&now=2026-10-28T10:20");
  await shot("light-13-today-360");
  await page.setViewport({ width: 820, height: 1180, deviceScaleFactor: 1 });
  await open("theme=light&now=2026-10-28T10:20&tab=week");
  await shot("light-14-week-tablet");

  // desktop browser: sidebar + two columns
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 1 });
  for (const [tab, name] of [
    ["today", "15-desktop-today"],
    ["week", "16-desktop-week"],
    ["study", "17-desktop-study"],
    ["campus", "18-desktop-campus"],
  ]) {
    await open(`theme=light&now=2026-10-28T10:20&tab=${tab}`);
    await shot(`light-${name}`);
  }
  await open("theme=dark&now=2026-10-28T10:20&tab=today");
  await shot("dark-15-desktop-today");

  await browser.close();
  console.log(`\nall shots in ${OUT}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
