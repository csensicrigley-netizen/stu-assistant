/** Reopen the approved editable project with Liquid Composer's WebGL renderer. */
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../icon-source/truth-bell');
const composer = process.argv[2];
if (!composer) throw Error('Usage: node scripts/export-icon-source.mjs <liquid-composer checkout>');
const { openSession } = await import(pathToFileURL(resolve(composer, 'scripts/agent/session.mjs')).href);
const session = await openSession();
const save = async (name, options) => writeFile(join(root, name), Buffer.from(await session.call('render', options), 'base64'));
try {
  const state = await session.call('load', {
    icon: (await readFile(join(root, 'arctic-chrome-prism.icon.zip'))).toString('base64'),
    name: 'Arctic Chrome Prism.icon',
  });
  await save('reopened-512.png', { size: 512 });
  await save('artwork-1024.png', { size: 1024, bare: true });
  await writeFile(join(root, 'renderer.json'), JSON.stringify({
    upstream: 'https://github.com/frontedu/liquid-composer',
    commit: '4edfb44c27bab264f4ab2d762aaaed78938de44e',
    renderer: session.renderer,
    background: state.background,
    layers: state.layers,
  }, null, 2) + '\n');
  // A clean alpha silhouette for Android's user-selected monochrome theme.
  // This export never changes the color artwork or its refraction settings.
  for (const group of state.layers) {
    for (const layer of group.layers ?? [group]) {
      if (layer.name.startsWith('星空')) await session.call('update', layer.id, { visible: false });
      else await session.call('update', layer.id, {
        liquidGlass: { enabled: false, shadow: { enabled: false } },
      });
    }
  }
  await save('silhouette-1024.png', { size: 1024, bare: true });
  console.log('Exported approved source with', session.renderer);
  console.log('Warnings:', session.drainWarnings());
} finally { await session.close(); }
