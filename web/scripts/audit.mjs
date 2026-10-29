/**
 * Measurable audit. No opinions — numbers only.
 *
 *   node scripts/audit.mjs        (dev server on :5180)
 *
 * Every tab and every sub-view is visited, in light and dark:
 *   1. axe-core (wcag2a / 2aa / 21aa / 22aa), including colour contrast
 *   2. touch targets >= 44x44 CSS px
 *   3. horizontal overflow at 360 / 390 / 430 on every view
 *   4. cumulative layout shift on first load
 *
 * Exit code is non-zero when a hard gate fails.
 */
import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import puppeteer from "puppeteer-core";

const CHROME = [
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
].find((p) => existsSync(p));

const BASE = process.env.SHOOT_URL ?? "http://localhost:5180";
const NOW = "2026-10-28T10:20";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** tab + the segmented option to press inside it */
const VIEWS = [
  ["today", null],
  ["week", null],
  ["study", "作业"],
  ["study", "考试"],
  ["study", "成绩"],
  ["campus", "通知"],
  ["campus", "生活"],
  ["campus", "校历"],
  ["me", null],
];

const TARGETS = () =>
  [...document.querySelectorAll("button,a[href],[role=button],[role=radio],[role=checkbox],input,select,textarea,summary")]
    .filter((el) => {
      const cs = getComputedStyle(el);
      const r = el.getBoundingClientRect();
      // skip-links sit off-screen until focused
      if (el.classList.contains("skip")) return false;
      return cs.display !== "none" && cs.visibility !== "hidden" && r.width > 0 && r.height > 0;
    })
    .map((el) => {
      const r = el.getBoundingClientRect();
      return {
        w: Math.round(r.width),
        h: Math.round(r.height),
        label: (el.getAttribute("aria-label") || el.textContent || el.tagName).trim().slice(0, 24),
      };
    });

const AXE = async () => {
  const mod = await import("/node_modules/axe-core/axe.min.js");
  const axeCore = mod.default ?? window.axe;
  const res = await axeCore.run(document, {
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] },
  });
  const pass = res.passes.find((p) => p.id === "color-contrast");
  const ratios = (pass?.nodes ?? []).map((n) => n.any?.[0]?.data?.contrastRatio).filter((r) => typeof r === "number");
  return {
    violations: res.violations.map((v) => ({
      id: v.id,
      impact: v.impact,
      nodes: v.nodes.length,
      help: v.help,
      samples: v.nodes.slice(0, 4).map((n) => ({
        target: n.target?.join(" ").slice(0, 80),
        summary: (n.failureSummary || "").replace(/\s+/g, " ").slice(0, 200),
      })),
    })),
    contrastChecked: (pass?.nodes.length ?? 0) + (res.violations.find((v) => v.id === "color-contrast")?.nodes.length ?? 0),
    contrastMin: ratios.length ? Math.min(...ratios) : null,
  };
};

async function openView(page, theme, tab, seg, palette = "sweet") {
  await page.goto(`${BASE}/?preview=1&theme=${theme}&palette=${palette}&now=${NOW}&tab=${tab}`, { waitUntil: "networkidle2" });
  await sleep(500);
  if (seg) {
    await page.evaluate((t) => {
      const b = [...document.querySelectorAll(".seg__btn")].find((x) => x.textContent?.includes(t));
      b?.click();
    }, seg);
  }
  // entry animations must finish: a mid-fade colour is not a real colour
  await sleep(1100);
}

async function main() {
  if (!CHROME) throw new Error("no chromium");
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    userDataDir: path.join(os.tmpdir(), "stu-audit-profile-v2"),
    args: ["--no-sandbox", "--disable-gpu", "--hide-scrollbars"],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });

  const report = [];
  let failures = 0;

  for (const palette of ["sweet", "classic"]) {
   for (const theme of ["light", "dark"]) {
    for (const [tab, seg] of VIEWS) {
      await openView(page, theme, tab, seg, palette);
      const axe = await page.evaluate(AXE).catch((e) => ({
        violations: [{ id: "axe_error", impact: "critical", nodes: 0, help: String(e).slice(0, 120), samples: [] }],
        contrastChecked: 0,
        contrastMin: null,
      }));
      const small = (await page.evaluate(TARGETS)).filter((t) => t.w < 44 || t.h < 44);
      report.push({ palette, theme, view: `${tab}${seg ? `/${seg}` : ""}`, axe, small });
    }
   }
  }

  console.log("\n================ AXE + TOUCH (every view) ================");
  for (const r of report) {
    const serious = r.axe.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    const status = serious.length === 0 && r.small.length === 0 ? "✓" : "✗";
    console.log(
      `${status} ${r.palette.padEnd(7)} ${r.theme.padEnd(5)} ${r.view.padEnd(12)} contrast nodes ${String(r.axe.contrastChecked).padStart(3)}` +
        (r.axe.contrastMin != null ? `, min ${r.axe.contrastMin.toFixed(2)}:1` : "") +
        `, violations ${r.axe.violations.length}, small targets ${r.small.length}`,
    );
    for (const v of r.axe.violations) {
      console.log(`    ${v.impact === "serious" || v.impact === "critical" ? "✗" : "·"} ${v.id} (${v.impact}) ×${v.nodes} — ${v.help}`);
      for (const s of v.samples) console.log(`        ${s.target} :: ${s.summary}`);
    }
    for (const s of r.small.slice(0, 6)) console.log(`    ✗ target ${s.w}×${s.h} "${s.label}"`);
    failures += serious.length + r.small.length;
  }

  console.log("\n================ OVERFLOW ================");
  for (const width of [360, 390, 430]) {
    await page.setViewport({ width, height: 844, deviceScaleFactor: 1 });
    const bad = [];
    for (const [tab, seg] of VIEWS) {
      await openView(page, "light", tab, seg);
      const o = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1);
      if (o) bad.push(`${tab}${seg ? `/${seg}` : ""}`);
    }
    console.log(`${width}px: ${bad.length ? `✗ ${bad.join(", ")}` : "✓ no horizontal overflow on any view"}`);
    failures += bad.length;
  }

  console.log("\n================ LAYOUT SHIFT ================");
  await page.setViewport({ width: 390, height: 844, deviceScaleFactor: 1 });
  await page.evaluateOnNewDocument(() => {
    window.__cls = 0;
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) if (!e.hadRecentInput) window.__cls += e.value;
    }).observe({ type: "layout-shift", buffered: true });
  });
  await page.goto(`${BASE}/?preview=1&now=${NOW}`, { waitUntil: "networkidle2" });
  await sleep(1800);
  const cls = await page.evaluate(() => window.__cls ?? 0);
  console.log(`CLS: ${cls.toFixed(4)} ${cls < 0.1 ? "✓" : "✗"}`);
  if (cls >= 0.1) failures++;

  await browser.close();
  console.log(`\n${failures === 0 ? "ALL GATES PASS" : `${failures} issue(s) to fix`}`);
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
