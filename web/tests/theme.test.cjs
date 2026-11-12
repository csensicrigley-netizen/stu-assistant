const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const ts = require('typescript');

const webRoot = path.resolve(__dirname, '..');

function loadUseTheme() {
  const filename = path.join(webRoot, 'src/lib/useTheme.ts');
  const source = fs.readFileSync(filename, 'utf8');
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    fileName: filename,
  }).outputText;
  const context = vm.createContext({
    URLSearchParams,
    location: { search: '' },
    localStorage: { getItem: () => null, setItem: () => {} },
    document: { documentElement: { dataset: {} }, querySelector: () => null },
    window: { matchMedia: () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }) },
  });
  const module = { exports: {} };
  const localRequire = spec => {
    if (spec === 'react') return { useState: init => [typeof init === 'function' ? init() : init, () => {}], useEffect: () => {}, useCallback: fn => fn };
    if (spec === './native') return { setNativeChrome: () => {}, setStatusBarDark: () => {} };
    throw new Error(`Unexpected import: ${spec}`);
  };
  new vm.Script(`(function(require, module, exports) {\n${output}\n})`, { filename })
    .runInContext(context)(localRequire, module, module.exports);
  return module.exports;
}

test('all 6 campus palettes are normalized and synchronized across tokens.css and index.html', () => {
  const { PALETTE_OPTIONS, normalizePalette } = loadUseTheme();
  const keys = Array.from(PALETTE_OPTIONS, p => p.key);
  assert.deepEqual(keys, ['sweet', 'classic', 'phoenix', 'pine', 'lilac', 'latte']);

  for (const key of keys) {
    assert.equal(normalizePalette(key), key);
  }
  assert.equal(normalizePalette('unknown'), 'sweet');
  assert.equal(normalizePalette(null), 'sweet');
  assert.equal(normalizePalette(''), 'sweet');

  const tokensCss = fs.readFileSync(path.join(webRoot, 'src/styles/tokens.css'), 'utf8');
  const indexHtml = fs.readFileSync(path.join(webRoot, 'index.html'), 'utf8');
  for (const key of keys.filter(k => k !== 'sweet')) {
    assert.ok(tokensCss.includes(`[data-palette="${key}"]`), `tokens.css missing light ${key}`);
    assert.ok(tokensCss.includes(`[data-theme="dark"][data-palette="${key}"]`), `tokens.css missing dark ${key}`);
    assert.ok(indexHtml.includes(`data-palette="${key}"`), `index.html missing pre-paint ${key}`);
  }
});
