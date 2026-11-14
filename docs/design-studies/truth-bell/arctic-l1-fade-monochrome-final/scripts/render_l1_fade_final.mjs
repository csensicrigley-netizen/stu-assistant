/** Render the final packages with Liquid Composer; requires its WebGL renderer. */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const { values } = parseArgs({ options: {
  composer: { type: 'string' },
  packages: { type: 'string', default: join(root, 'packages') },
  output: { type: 'string', default: join(root, 'renders') },
  sizes: { type: 'string' },
} });
if (!values.composer) throw Error('Pass --composer <Liquid Composer checkout>');
const requested = values.sizes?.split(',').map(Number);
const supportedBadge = [16, 24, 32, 48, 64, 128, 192, 256, 512, 1024];
const supportedBare = [16, 24, 32, 48, 64, 128, 256, 512, 1024];
if (requested?.some(n => !supportedBadge.includes(n))) throw Error('Unsupported --sizes');
const badgeSizes = requested ?? supportedBadge;
const bareSizes = requested?.filter(n => supportedBare.includes(n)) ?? supportedBare;
const { openSession } = await import(pathToFileURL(resolve(values.composer, 'scripts/agent/session.mjs')).href);
const session = await openSession();
await mkdir(values.output, { recursive: true });
try {
  for (const name of ['truth-bell-l1-dark-obsidian', 'truth-bell-l1-light-porcelain']) {
    const icon = (await readFile(join(values.packages, name + '.icon.zip'))).toString('base64');
    await session.call('load', { icon, name: name + '.icon' });
    for (const size of badgeSizes) {
      const png = await session.call('render', { size });
      await writeFile(join(values.output, `${name}-badge-${size}.png`), Buffer.from(png, 'base64'));
    }
    // The supplied Composer projects describe Badge, not the independently
    // approved Bare export. Preserve those original alpha images byte for byte.
    for (const size of bareSizes) {
      const filename = `${name}-bare-${size}.png`;
      const png = await readFile(join(root, 'renders', filename));
      await writeFile(join(values.output, filename), png);
    }
    console.log(name, 'rendered');
  }
  console.log('Renderer:', session.renderer);
} finally { await session.close(); }
