/** Real Web routes with a mocked native navigation bridge, not a Compose/device test. */
import assert from "node:assert/strict";
import { existsSync } from "node:fs";
import puppeteer from "puppeteer-core";

const executablePath = process.env.CHROME_PATH ?? [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
  "/usr/bin/chromium", "/usr/bin/google-chrome",
].find(existsSync);
const browser = await puppeteer.launch({ executablePath, headless: true });
const pause = (ms = 150) => new Promise(resolve => setTimeout(resolve, ms));
const base = process.env.HAPTICS_URL ?? "http://localhost:5187";
let inspectedPage;

async function mockedPage(mounted = true) {
  const page = await browser.newPage();
  await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
  await page.evaluateOnNewDocument((ready) => {
    window.androidBridge = {};
    window.navStates = [];
    window.navHides = 0;
    window.navigationCalls = [];
    window.feedbackCalls = [];
    const callbacks = new Map();
    let nextCallback = 0;
    window.emitNavigation = key => {
      for (const listener of callbacks.values()) if (listener.event === 'select') listener.callback({ key });
    };
    window.Capacitor = {
      PluginHeaders: [
        { name: 'InteractionHaptics', methods: ['play', 'cancel'].map(name => ({ name, rtype: 'promise' })) },
        { name: 'LiveWindow', methods: ['setChrome', 'update', 'stop', 'start', 'isSupported', 'requestPermissions', 'checkPermissions'].map(name => ({ name, rtype: 'promise' })) },
        { name: 'LiquidGlassNavigation', methods: [
          ...['setState', 'hide', 'removeListener'].map(name => ({ name, rtype: 'promise' })),
          { name: 'addListener', rtype: 'callback' },
        ] },
      ],
      nativeCallback: (_plugin, method, options, callback) => {
        if (method !== 'addListener') throw new Error(`unexpected callback method: ${method}`);
        const id = `callback-${nextCallback++}`;
        callbacks.set(id, { event: options.eventName, callback });
        return id;
      },
      nativePromise: async (plugin, method, options) => {
        if (plugin === 'InteractionHaptics') {
          if (method === 'play') window.feedbackCalls.push(options);
          return;
        }
        if (plugin !== 'LiquidGlassNavigation') return;
        window.navigationCalls.push(method);
        if (method === 'setState') {
          if (window.rejectNavigationState) throw new Error('Native navigation unavailable');
          window.navStates.push(options);
          return { mounted: ready };
        }
        if (method === 'hide') { window.navHides++; return; }
        if (method === 'removeListener') { callbacks.delete(options.callbackId); return; }
        throw new Error(`unexpected native method: ${method}`);
      },
    };
  }, mounted);
  await page.goto(`${base}/?preview=1&tab=today&now=2026-10-28T10:20`, { waitUntil: 'networkidle0' });
  return page;
}

try {
  const page = await mockedPage();
  inspectedPage = page;
  await page.waitForFunction(() => document.documentElement.dataset.nativeNavigation === 'true');
  await page.waitForFunction(() => document.querySelector('.tabbar') === null);
  assert.equal(await page.$$eval('.tabbar,.tabbar__btn', els => els.length), 0, 'acknowledged Compose navigation removes the old bottom bar and its controls');
  let state = await page.evaluate(() => window.navStates.at(-1));
  assert.equal(state.current, 'today');
  assert.equal(state.tabs.length, 6);
  assert.equal(state.strength, 100);
  assert.equal(state.visible, true);
  assert.equal(state.sheetOpen, false);

  // Both selections arrive before React can commit the first transition.
  await page.evaluate(async () => {
    window.feedbackCalls = [];
    window.emitNavigation('week');
    window.emitNavigation('today');
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  await pause(200);
  await page.waitForFunction(() => document.querySelectorAll('.page').length === 1);
  assert.equal(await page.$eval('.page', el => el.className), 'page page--today', 'rapid native week → today keeps the final selection');
  assert.ok(await page.$('.page--today'));
  assert.deepEqual(await page.evaluate(() => window.feedbackCalls), [], 'rapid native events do not replay Web haptics');

  await page.evaluate(() => { window.feedbackCalls = []; window.emitNavigation('week'); });
  await page.waitForSelector('.page--week');
  assert.deepEqual(await page.evaluate(() => window.feedbackCalls), [], 'native event must not replay Web haptics');
  await page.evaluate(() => { window.emitNavigation('week'); window.emitNavigation('unknown'); });
  assert.ok(await page.$('.page--week'));

  // The real course sheet blocks the native layer until its exit has finished.
  await page.waitForSelector('.page--week .lesson');
  await page.click('.page--week .lesson');
  await page.waitForFunction(() => window.navStates.at(-1)?.sheetOpen === true);
  await page.evaluate(() => window.emitNavigation('campus'));
  assert.ok(await page.$('.page--week'));
  await page.$eval('.sheet [aria-label="关闭"]', el => el.click());
  await page.waitForFunction(() => window.navStates.at(-1)?.sheetOpen === false);

  // Current-parent taps still return from both timetable subpages without extra feedback.
  for (const label of ['手动导入课表', '用医学院教务账号自动同步']) {
    await page.$$eval('.cta', (buttons, text) => buttons.find(el => el.textContent.includes(text)).click(), label);
    await pause(400);
    assert.equal(await page.evaluate(() => window.navStates.at(-1).current), 'week');
    await page.evaluate(() => { window.feedbackCalls = []; window.emitNavigation('week'); });
    await page.waitForSelector('.page--week');
    assert.deepEqual(await page.evaluate(() => window.feedbackCalls), []);
  }

  // Use the real setting, so Vite's versioned module URLs cannot create a
  // second haptics singleton just for the test.
  await page.evaluate(() => window.emitNavigation('me'));
  await page.waitForSelector('.feedback__range');
  await page.focus('.feedback__range');
  await page.keyboard.press('Home');
  for (let i = 0; i < 37; i++) await page.keyboard.press('ArrowRight');
  await page.waitForFunction(() => window.navStates.at(-1)?.strength === 37);
  await page.evaluate(() => window.emitNavigation('week'));
  await page.waitForSelector('.page--week');
  await page.evaluate(() => {
    document.documentElement.dataset.theme = 'dark';
    document.documentElement.dataset.palette = 'classic';
  });
  await page.waitForFunction(() => {
    const state = window.navStates.at(-1);
    return state.theme === 'dark' && state.palette === 'classic' && state.strength === 37;
  });
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
  await page.waitForFunction(() => window.navStates.at(-1)?.reducedMotion === true);

  await page.evaluate(() => {
    const field = document.createElement('textarea');
    field.id = 'keyboard-fixture';
    document.body.append(field);
    field.focus();
    Object.defineProperty(window.visualViewport, 'height', { configurable: true, value: 550 });
    window.visualViewport.dispatchEvent(new Event('resize'));
  });
  await page.waitForFunction(() => window.navStates.at(-1)?.keyboardOpen === true);
  await page.evaluate(() => window.emitNavigation('campus'));
  assert.ok(await page.$('.page--week'));
  // Keep focus while rotating with the keyboard closed. The old portrait
  // baseline must not hide navigation permanently in a shorter landscape.
  await page.evaluate(() => { delete window.visualViewport.height; });
  await page.setViewport({ width: 915, height: 412, isMobile: true, hasTouch: true });
  await page.waitForFunction(() => window.navStates.at(-1)?.keyboardOpen === false);
  assert.equal(await page.evaluate(() => document.activeElement?.id), 'keyboard-fixture');
  // A real viewport reduction still counts as an IME after that reset.
  await page.evaluate(() => {
    Object.defineProperty(window.visualViewport, 'height', { configurable: true, value: 180 });
    window.visualViewport.dispatchEvent(new Event('resize'));
    window.dispatchEvent(new Event('orientationchange'));
  });
  await page.waitForFunction(() => window.navStates.at(-1)?.keyboardOpen === true);
  await page.evaluate(() => window.emitNavigation('campus'));
  assert.ok(await page.$('.page--week'));
  await page.evaluate(() => {
    delete window.visualViewport.height;
    document.querySelector('#keyboard-fixture').remove();
    window.visualViewport.dispatchEvent(new Event('resize'));
  });
  await page.waitForFunction(() => window.navStates.at(-1)?.keyboardOpen === false);

  await page.setViewport({ width: 1200, height: 915, isMobile: true, hasTouch: true });
  await page.waitForFunction(() => window.navStates.at(-1)?.visible === false);
  await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
  await page.waitForFunction(() => window.navStates.at(-1)?.visible === true);

  // A test-only DOM fixture runs on the CSS compositor with no rAF/JS loop.
  // The mocked bridge proves RPC silence, not native GPU pixel synchronisation.
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'no-preference' }]);
  await page.waitForFunction(() => window.navStates.at(-1)?.reducedMotion === false);
  await page.evaluate(() => {
    const fixture = document.createElement('div');
    fixture.id = 'gpu-motion-fixture';
    fixture.style.cssText = 'position:fixed;bottom:0;left:0;right:0;height:180px;overflow:hidden;pointer-events:none;z-index:2147483000';
    fixture.innerHTML = '<style>@keyframes qa-gpu-motion{from{transform:translateX(-80px)}to{transform:translateX(80px)}}#gpu-motion-fixture>div{height:100%;width:calc(100% + 160px);background:repeating-linear-gradient(90deg,#ef4444 0 60px,#10b981 60px 120px,#3b82f6 120px 180px,#fff 180px 188px);animation:qa-gpu-motion 1s linear infinite;will-change:transform}</style><div></div>';
    document.body.append(fixture);
  });
  await pause(200);
  const beforeMotion = await page.evaluate(() => {
    window.navigationCalls = [];
    return getComputedStyle(document.querySelector('#gpu-motion-fixture>div')).transform;
  });
  await pause(250);
  const duringMotion = await page.$eval('#gpu-motion-fixture>div', el => getComputedStyle(el).transform);
  assert.notEqual(duringMotion, beforeMotion, 'the CSS animation is actually advancing');
  await pause(950);
  assert.deepEqual(await page.evaluate(() => window.navigationCalls), [], 'continuous CSS animation makes no rendering RPC or per-frame setState');
  await page.evaluate(() => {
    for (let i = 0; i < 30; i++) document.dispatchEvent(new Event('scroll'));
    window.dispatchEvent(new Event('load'));
    document.dispatchEvent(new Event('animationend'));
    document.dispatchEvent(new Event('transitionend'));
    document.querySelector('#gpu-motion-fixture style').firstChild.textContent += '\n/* character mutation */';
  });
  await pause(200);
  assert.deepEqual(await page.evaluate(() => window.navigationCalls), [], 'pixel and paint notifications do not cross the logical state bridge');
  assert.equal(await page.evaluate(() => document.documentElement.dataset.nativeNavigation), 'true');
  await page.evaluate(() => document.querySelector('#gpu-motion-fixture').remove());

  await page.evaluate(() => {
    window.rejectNavigationState = true;
    document.documentElement.dataset.palette = document.documentElement.dataset.palette === 'sweet' ? 'classic' : 'sweet';
  });
  await page.waitForFunction(() => !document.documentElement.dataset.nativeNavigation);
  await page.waitForSelector('.tabbar');
  assert.equal(await page.$$eval('.tabbar', els => els.length), 1, 'native failure remounts a single usable Web bottom bar');
  assert.notEqual(await page.$eval('.tabbar', el => getComputedStyle(el).display), 'none');
  assert.ok(await page.evaluate(() => window.navHides > 0));

  const denied = await mockedPage(false);
  await denied.waitForFunction(() => window.navStates.length > 0);
  await pause();
  assert.equal(await denied.evaluate(() => document.documentElement.dataset.nativeNavigation), undefined);
  assert.equal(await denied.$$eval('.tabbar', els => els.length), 1);
  assert.notEqual(await denied.$eval('.tabbar', el => getComputedStyle(el).display), 'none');
  console.log('PASS: native mount acknowledgement, six destinations, rapid route selection, route events without duplicate haptics, current-parent return, modal/IME/rotation/wide blocking, live theme/strength/reduced motion, CSS animation without rendering RPC, native failure fallback');
} catch (error) {
  console.error(error);
  if (inspectedPage) console.error(await inspectedPage.evaluate(() => ({
    route: document.querySelector('.page')?.className,
    latestState: window.navStates.at(-1),
    visibleText: document.body.innerText.slice(0, 900),
  })).catch(() => 'page unavailable'));
  throw error;
} finally { await browser.close(); }
