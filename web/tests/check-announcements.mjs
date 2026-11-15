// Local browser integration check. Start npm run dev, then run this file.
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdir, readFile } from "node:fs/promises";
import path from "node:path";
import puppeteer from "puppeteer-core";

const executablePath = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/chromium", "/usr/bin/google-chrome",
].find(existsSync);
if (!executablePath) throw new Error("Install Chrome/Chromium to run the browser check");
const base = process.env.ANNOUNCEMENT_TEST_URL || "http://127.0.0.1:5180";
const output = path.resolve("../output/playwright");
await mkdir(output, { recursive: true });
const axeSource = await readFile(new URL("../node_modules/axe-core/axe.min.js", import.meta.url), "utf8");
const message = JSON.parse(await readFile(new URL("../../backend/app/announcements.example.json", import.meta.url), "utf8")).announcements[0];
const browser = await puppeteer.launch({ executablePath, headless: true, args: ["--no-sandbox", "--lang=zh-CN"] });
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const contexts = [];

async function pageFor({ theme = "light", palette = "sweet", width = 390, item = message, tab = "today", reduced = false } = {}) {
  const context = await browser.createBrowserContext();
  contexts.push(context);
  const page = await context.newPage();
  await page.setViewport({ width, height: 844, deviceScaleFactor: 1 });
  await page.evaluateOnNewDocument(() => {
    window.announcementFrames = [];
    const observe = () => {
      const element = document.querySelector('dialog.announcement[open]');
      if (element && window.announcementFrames.length < 150) {
        const style = getComputedStyle(element);
        window.announcementFrames.push({ opacity: Number(style.opacity), transform: style.transform });
      }
      requestAnimationFrame(observe);
    };
    requestAnimationFrame(observe);
  });
  if (reduced) await page.emulateMediaFeatures([{ name: "prefers-reduced-motion", value: "reduce" }]);
  await page.setRequestInterception(true);
  page.on("request", (request) => {
    if (request.url().includes("/v1/announcements?")) {
      void request.respond({ status: 200, contentType: "application/json", body: JSON.stringify({ announcements: [item] }) });
    } else if (request.url().startsWith("https://images.invalid/")) void request.respond({ status: 404 });
    else void request.continue();
  });
  await page.goto(`${base}/?preview=1&theme=${theme}&palette=${palette}&tab=${tab}`, { waitUntil: "networkidle2" });
  return page;
}

try {
  for (const variant of [
    { theme: "light", width: 390 }, { theme: "dark", width: 390 },
    { theme: "light", palette: "classic", width: 320 }, { theme: "dark", palette: "classic", width: 430, reduced: true },
  ]) {
    const page = await pageFor(variant);
    await page.waitForSelector("dialog.announcement[open]", { timeout: 8_000 });
    await page.waitForSelector(".announcement__effect", { hidden: true, timeout: 8_000 });
    await delay(650);
    if (!variant.reduced) {
      const frames = await page.evaluate(() => window.announcementFrames);
      assert(frames.some((frame) => frame.opacity > 0 && frame.opacity < 1), 'entrance draws intermediate opacity rather than flashing');
      assert(new Set(frames.map((frame) => frame.transform)).size > 8, 'entrance interpolates position and scale');
    }
    const measurements = await page.evaluate(() => {
      const dialog = document.querySelector("dialog.announcement");
      const box = dialog.getBoundingClientRect();
      return { left: box.left, right: box.right, width: innerWidth, focused: dialog.contains(document.activeElement), targets: [...dialog.querySelectorAll("button,a")].map((el) => el.getBoundingClientRect().height) };
    });
    assert(measurements.left >= 0 && measurements.right <= measurements.width);
    assert(measurements.focused);
    assert(measurements.targets.every((height) => height >= 44));
    await page.addScriptTag({ content: axeSource });
    const violations = await page.evaluate(async () => (await window.axe.run("dialog.announcement", {
      runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
    })).violations.map((v) => ({ id: v.id, description: v.description, nodes: v.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })) })));
    assert.deepEqual(violations, []);
    await page.screenshot({ path: path.join(output, `announcement-web-${variant.theme}-${variant.palette || "sakura"}-${variant.width}.png`) });
    await page.keyboard.press("Escape");
    await delay(60);
    assert(await page.$('dialog.announcement[open][data-closing]'), 'exit retains the modal and its focus barrier');
    const exitOpacity = await page.$eval('dialog.announcement', (element) => Number(getComputedStyle(element).opacity));
    assert(exitOpacity > 0 && exitOpacity < 1, 'exit is a fade rather than a removal');
    await page.waitForSelector("dialog.announcement", { hidden: true });
    await page.reload({ waitUntil: "networkidle2" });
    await delay(1000);
    assert.equal(await page.$("dialog.announcement[open]"), null, "once receipt survives reopening");
    console.log(`PASS theme=${variant.theme} palette=${variant.palette || "sakura"} width=${variant.width}: layout, focus, targets, axe, Escape, once`);
  }

  const nonHome = await pageFor({ tab: "week" });
  await delay(1100);
  assert.equal(await nonHome.$("dialog.announcement[open]"), null, "non-home page remains undisturbed");
  await nonHome.evaluate(() => [...document.querySelectorAll(".tabbar button")].find((b) => b.textContent.includes("今天")).click());
  await nonHome.waitForSelector("dialog.announcement[open]");
  await nonHome.click(".announcement__close");
  await nonHome.waitForSelector("dialog.announcement", { hidden: true });
  await nonHome.evaluate(() => [...document.querySelectorAll(".tabbar button")].find((b) => b.textContent.includes("课表")).click());
  await delay(400);
  await nonHome.evaluate(() => [...document.querySelectorAll(".tabbar button")].find((b) => b.textContent.includes("今天")).click());
  await delay(1000);
  assert.equal(await nonHome.$("dialog.announcement[open]"), null, "tab return never repeats a popup");
  console.log("PASS homepage only; returning to homepage does not repeat");

  const brokenImage = await pageFor({ item: { ...message, image_url: "https://images.invalid/hero.png", image_alt: "项目海报" } });
  await brokenImage.waitForSelector("dialog.announcement[open] .announcement__emblem");
  assert.equal(await brokenImage.$(".announcement__image"), null);
  console.log("PASS broken image gracefully falls back to emblem and text");

  const animated = await pageFor({ item: { ...message, effects: ["star-struck"] } });
  await animated.waitForSelector('.announcement__effect[data-effect="star-struck"] svg');
  const before = await animated.$eval('.announcement__effect', (el) => el.innerHTML);
  await delay(650);
  assert.notEqual(await animated.$eval('.announcement__effect', (el) => el.innerHTML), before, "effect frames advance");
  assert.equal(await animated.$eval('.announcement__effect', (el) => getComputedStyle(el).pointerEvents), 'none');
  await animated.screenshot({ path: path.join(output, 'announcement-web-effect-star-struck.png') });
  await animated.waitForSelector('.announcement__effect', { hidden: true, timeout: 6_000 });
  await delay(500);
  assert.equal(await animated.$('.announcement__effect'), null, "one-shot effect does not restart");

  const reduced = await pageFor({ reduced: true, item: { ...message, effects: ["star-struck"] } });
  await reduced.waitForSelector('dialog.announcement[open]');
  assert.equal(await reduced.$('.announcement__effect'), null, "reduced motion skips decorative animation");
  const early = await pageFor({ item: { ...message, effects: ["party-popper"] } });
  await early.waitForSelector('.announcement__effect svg');
  await early.click('.announcement__close');
  await early.waitForSelector('dialog.announcement', { hidden: true });
  assert.equal(await early.$('.announcement__effect'), null, "closing disposes playback");
  console.log("PASS real vector playback advances, completes once, stays pointer-transparent, and respects reduced motion / dismissal");

  const hotMessage = { ...message, effects: [], id: 'hot-config-check' };
  const hot = await pageFor({ item: hotMessage });
  await hot.waitForSelector('dialog.announcement[open]');
  await hot.click('.announcement__close');
  await hot.waitForSelector('dialog.announcement', { hidden: true });
  hotMessage.revision += 1;
  hotMessage.title = '服务器刚刚更新的公告';
  await hot.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await hot.waitForFunction(() => document.querySelector('.announcement__title')?.textContent === '服务器刚刚更新的公告');
  await hot.click('.announcement__close');
  await hot.waitForSelector('dialog.announcement', { hidden: true });
  await hot.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await delay(1200);
  assert.equal(await hot.$('dialog.announcement[open]'), null, 'same revision stays read on a later foreground return');
  console.log('PASS hot configuration: foreground return receives a new revision without reload; same revision never repeats');
  for (const palette of ['sweet', 'classic', 'phoenix', 'pine', 'lilac', 'latte']) {
    for (const theme of ['light', 'dark']) {
      const page = await pageFor({ palette, theme, item: { ...message, effects: [] }, reduced: true });
      await page.waitForSelector('dialog.announcement[open]');
      await delay(200);
      await page.addScriptTag({ content: axeSource });
      const errors = await page.evaluate(async () => (await window.axe.run('dialog.announcement', {
        runOnly: ['color-contrast'],
      })).violations.map((v) => v.nodes.map((n) => ({ target: n.target, summary: n.failureSummary }))));
      assert.deepEqual(errors, [], `${theme}/${palette} text must remain readable`);
    }
  }
  console.log('PASS readable announcement text and actions in all six palettes, light and dark');
} finally {
  await Promise.all(contexts.map((context) => context.close()));
  await browser.close();
}
