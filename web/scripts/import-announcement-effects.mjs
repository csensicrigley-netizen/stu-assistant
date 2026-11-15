// Import the five exact variants selected from ilyhalight's Telegram effect gallery.
// Native and Web consume the same vector data; no animation is fetched at runtime.
import { gunzipSync } from "node:zlib";
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const revision = "7b68fd20784d297a812f1345db48f28e3934b6e1";
const selections = [
  ["star-struck", "U+1F929", 0],
  ["heart-on-fire", "U+2764_U+200D_U+1F525", 0],
  ["folded-hands", "U+1F64F", 0],
  ["party-popper", "U+1F389", 0],
  ["keycap-one-6", "U+31_U+20E3", 6],
];
const entries = await Promise.all(selections.map(async ([id, code, variant]) => {
  const source = `https://raw.githubusercontent.com/ilyhalight/telegram-emoji-effects/${revision}/tgs/${code}/${variant}.tgs`;
  const response = await fetch(source, { signal: AbortSignal.timeout(30000) });
  if (!response.ok) throw new Error(`${id}: HTTP ${response.status}`);
  const compressed = Buffer.from(await response.arrayBuffer());
  const animation = JSON.parse(gunzipSync(compressed));
  if (!animation.layers?.length || !(animation.fr > 0) || !(animation.op > animation.ip)) throw new Error(`Invalid animation: ${id}`);
  if (animation.assets?.some((asset) => asset.p && !asset.p.startsWith("data:"))) throw new Error(`External asset: ${id}`);
  return { id, code, variant, source, sha256: createHash("sha256").update(compressed).digest("hex"),
    durationMs: Math.round((animation.op - animation.ip) / animation.fr * 1000), json: JSON.stringify(animation) };
}));
for (const directory of [path.join(root, "public/announcement-effects"), path.join(root, "android/app/src/main/assets/announcement-effects")]) {
  await mkdir(directory, { recursive: true });
  for (const entry of entries) await writeFile(path.join(directory, `${entry.id}.json`), entry.json);
}
await mkdir(path.join(root, "../docs/third-party"), { recursive: true });
await writeFile(path.join(root, "../docs/third-party/announcement-effects.json"), JSON.stringify({
  gallery: "https://ilyhalight.github.io/telegram-emoji-effects/", revision,
  notice: "Original animations are created by Telegram. The source repository requests compliance with Telegram's terms; it does not grant a separate license to the original artwork.",
  effects: entries.map(({ json, ...entry }) => ({ ...entry, bytes: Buffer.byteLength(json) })),
}, null, 2) + "\n");
for (const { id, durationMs, json } of entries) console.log(`${id}: ${durationMs} ms, ${Buffer.byteLength(json)} bytes`);
