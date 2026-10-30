/** Real browser input with a mocked Capacitor bridge; does not test motor output.
 * Start Vite, then HAPTICS_URL=http://localhost:5187 node scripts/check-haptics.mjs
 */
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import { mkdir } from "node:fs/promises";
import puppeteer from "puppeteer-core";

const executablePath = process.env.CHROME_PATH ?? [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/chromium", "/usr/bin/google-chrome",
].find(existsSync);
const browser = await puppeteer.launch({ executablePath, headless: true });
const page = await browser.newPage();
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
await page.evaluateOnNewDocument(() => {
  window.androidBridge = {};
  window.feedbackCalls = [];
  window.feedbackCancelled = 0;
  window.Capacitor = {
    PluginHeaders: [
      { name: "InteractionHaptics", methods: ['play', 'cancel'].map(name => ({ name, rtype: 'promise' })) },
      { name: "LiveWindow", methods: ['setChrome', 'update', 'stop', 'start', 'isSupported', 'requestPermissions', 'checkPermissions'].map(name => ({ name, rtype: 'promise' })) },
    ],
    nativePromise: async (plugin, method, options) => {
      if (plugin !== "InteractionHaptics") return;
      if (method === "cancel") { window.feedbackCancelled++; return; }
      window.feedbackCalls.push({ ...options, at: performance.now() });
      if (window.rejectHaptics) throw new Error("Motor unavailable");
    },
  };
});
const pause = (ms = 100) => new Promise(resolve => setTimeout(resolve, ms));
const reset = async () => { await pause(); await page.evaluate(() => { window.feedbackCalls = []; }); };
const kinds = () => page.evaluate(() => window.feedbackCalls.map(call => call.kind));
const url = `${process.env.HAPTICS_URL ?? "http://localhost:5187"}/?preview=1&tab=me`;
try {
  await page.goto(url, { waitUntil: "networkidle0" });
  await page.waitForSelector('.feedback__range');
  assert.equal(await page.$$eval('.feedback input', els => els.length), 1);
  assert.equal(await page.$$eval('.feedback button,.feedback select,textarea', els => els.length), 0);
  assert.equal(await page.$eval('.feedback__range', el => el.value), '100');
  await page.$eval('.feedback__range', el => el.scrollIntoView({ block: 'center' }));
  await pause(300);
  await mkdir('tmp/haptics', { recursive: true });
  await page.screenshot({ path: 'tmp/haptics/default-strength.png' });

  // Keyboard changes are accessible, preview the new gain, and persist.
  await reset();
  await page.focus('.feedback__range');
  await page.keyboard.press('ArrowLeft');
  assert.equal(await page.$eval('.feedback__range', el => el.value), '99');
  assert.deepEqual(await kinds(), ['selection']);
  assert.equal(await page.evaluate(() => window.feedbackCalls[0].strength), 99);
  await page.reload({ waitUntil: 'networkidle0' });
  assert.equal(await page.$eval('.feedback__range', el => el.value), '99');

  // Zero cancels pending native work and stays silent, including ordinary buttons.
  await page.focus('.feedback__range'); await reset();
  await page.keyboard.press('Home');
  assert.equal(await page.$eval('.feedback__range', el => el.value), '0');
  assert.deepEqual(await kinds(), []);
  assert.ok(await page.evaluate(() => window.feedbackCancelled > 0));
  // Isolated controls exercise trusted event routing without changing app data.
  await page.evaluate(() => {
    const box = document.createElement('div');
    box.style.cssText = 'position:fixed;top:10px;left:10px;z-index:99999;background:white;';
    box.innerHTML = '<button id="test-key" style="width:150px;height:48px"><span>Test</span></button><button id="test-disabled" disabled>Disabled</button><input id="test-check" type="checkbox">';
    document.body.append(box);
  });
  await page.click('#test-key');
  assert.deepEqual(await kinds(), []);
  await page.focus('.feedback__range'); await page.keyboard.press('End');
  await reset(); await page.click('#test-key span');
  assert.deepEqual(await kinds(), ['press', 'release']);
  assert.ok(await page.evaluate(() => window.feedbackCalls.every(call => call.strength === 100)));
  await reset(); await page.click('#test-disabled');
  assert.deepEqual(await kinds(), []);
  await reset(); await page.click('#test-check');
  assert.deepEqual(await kinds(), ['selection']);
  await reset(); await page.$eval('#test-key', el => el.click());
  assert.deepEqual(await kinds(), []);

  // Moving out and back must not produce a release detent, even if click commits.
  const point = await page.$eval('#test-key', el => { const r = el.getBoundingClientRect(); return { x: r.x + 20, y: r.y + 20 }; });
  await reset(); await page.mouse.move(point.x, point.y); await page.mouse.down();
  await page.mouse.move(point.x + 50, point.y); await page.mouse.move(point.x, point.y); await page.mouse.up();
  assert.deepEqual(await kinds(), ['press']);
  await reset(); await page.focus('#test-key'); await page.keyboard.press('Space');
  assert.deepEqual(await kinds(), ['press', 'release']);
  await reset(); await page.keyboard.down('Enter'); await page.keyboard.down('Enter'); await page.keyboard.up('Enter');
  assert.deepEqual(await kinds(), ['press', 'release']);

  // A broken optional plugin never blocks a normal button action.
  await page.evaluate(() => { window.rejectHaptics = true; document.querySelector('#test-key').onclick = () => { window.activated = true; }; });
  await reset(); await page.click('#test-key'); await pause();
  assert.equal(await page.evaluate(() => window.activated), true);
  await page.evaluate(() => { window.rejectHaptics = false; });

  // A real dragged slider is rate-limited and has no delayed final buzz.
  await page.$eval('.feedback__range', el => el.scrollIntoView({ block: 'center' })); await pause(300);
  const range = await page.$eval('.feedback__range', el => { const r = el.getBoundingClientRect(); return { x: r.x, y: r.y + r.height / 2, width: r.width }; });
  await reset(); await page.mouse.move(range.x + range.width - 12, range.y); await page.mouse.down();
  await page.mouse.move(range.x + 45, range.y, { steps: 45 }); await page.mouse.up();
  let calls = await page.evaluate(() => window.feedbackCalls);
  assert.ok(calls.length > 0 && calls.every(call => call.kind === 'selection'));
  for (let i = 1; i < calls.length; i++) assert.ok(calls[i].at - calls[i - 1].at >= 55);
  await pause(200);
  assert.equal((await kinds()).length, calls.length);
  assert.deepEqual(errors, []);

  // The website stays silent and does not show an Android-only motor setting.
  const web = await browser.newPage();
  await web.goto(url, { waitUntil: 'networkidle0' });
  assert.equal(await web.$('.feedback'), null);
  console.log('PASS: single slider, defaults, persistence, zero, pointer/keyboard cancellation, disabled/synthetic clicks, plugin failure, slider throttle, web fallback');
} finally { await browser.close(); }
