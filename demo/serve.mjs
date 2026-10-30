/**
 * Zero-dependency static server for the demo shell.
 * Only used when Python is not available; python -m http.server is preferred.
 *
 *   node demo/serve.mjs 8123
 */
import { createServer } from "node:http";
import { createReadStream, existsSync, statSync } from "node:fs";
import { extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(fileURLToPath(new URL("..", import.meta.url)));
const PORT = Number(process.argv[2] ?? process.env.PORT ?? 8123);

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".webmanifest": "application/manifest+json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".webp": "image/webp",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
  ".map": "application/json; charset=utf-8",
};

createServer((req, res) => {
  try {
    const url = decodeURIComponent((req.url ?? "/").split("?")[0]);
    let filePath = join(ROOT, normalize(url).replace(/^(\.\.[/\\])+/, ""));

    // never escape the project root
    if (!filePath.startsWith(ROOT)) {
      res.writeHead(403).end("forbidden");
      return;
    }
    if (existsSync(filePath) && statSync(filePath).isDirectory()) {
      filePath = join(filePath, "index.html");
    }
    if (!existsSync(filePath)) {
      res.writeHead(404, { "content-type": "text/plain; charset=utf-8" }).end("404 找不到 " + url);
      return;
    }

    const type = TYPES[extname(filePath).toLowerCase()] ?? "application/octet-stream";
    res.writeHead(200, {
      "content-type": type,
      "cache-control": "no-store",
      "service-worker-allowed": "/",
    });
    createReadStream(filePath).pipe(res);
  } catch (err) {
    res.writeHead(500, { "content-type": "text/plain; charset=utf-8" }).end(String(err));
  }
}).listen(PORT, "0.0.0.0", () => {
  console.log(`\n  STU 事项助手 · 演示服务已启动`);
  console.log(`  本机：  http://localhost:${PORT}/demo/`);
  console.log(`  手机：  http://<本机局域网IP>:${PORT}/demo/`);
  console.log(`\n  按 Ctrl+C 停止\n`);
});
