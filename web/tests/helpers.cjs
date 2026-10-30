const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const lib = path.resolve(__dirname, '../src/lib');
const compiled = new Map();

// Execute real TS modules with the installed compiler. Only browser I/O and
// Vite's build-time API base are replaced; no product function is stubbed.
exports.harness = ({ native = false, timeoutMs = 25 } = {}) => {
  const storage = new Map();
  const events = [];
  const state = { fetch: async () => { throw new Error('Unexpected HTTP'); } };
  const cache = new Map();
  const context = vm.createContext({
    Headers, Response, AbortController, CustomEvent, URLSearchParams, atob,
    location: { search: '' },
    localStorage: {
      getItem: key => storage.get(key) ?? null,
      setItem: (key, value) => storage.set(key, String(value)),
      removeItem: key => storage.delete(key),
    },
    window: {
      setTimeout: (fn, ms) => setTimeout(fn, Math.min(ms, timeoutMs)),
      clearTimeout, dispatchEvent: event => { events.push(event.type); return true; },
    },
    fetch: (...args) => state.fetch(...args),
  });
  function load(file) {
    const filename = path.resolve(lib, file);
    if (cache.has(filename)) return cache.get(filename).exports;
    if (!compiled.has(filename)) {
      const source = fs.readFileSync(filename, 'utf8').replaceAll('import.meta.env.VITE_API_BASE', '""');
      compiled.set(filename, ts.transpileModule(source, {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
        fileName: filename,
      }).outputText);
    }
    const module = { exports: {} };
    cache.set(filename, module);
    const localRequire = spec => {
      if (spec === '@capacitor/core') return { Capacitor: { isNativePlatform: () => native } };
      if (spec.startsWith('.')) return load(path.resolve(path.dirname(filename), spec + '.ts'));
      return require(spec);
    };
    new vm.Script(`(function(require, module, exports) {\n${compiled.get(filename)}\n})`, { filename })
      .runInContext(context)(localRequire, module, module.exports);
    return module.exports;
  }
  return { load, state, events, storage };
};
