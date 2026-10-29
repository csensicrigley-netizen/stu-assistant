"""The browser version is served from STU_APP_WEB_DIR and nothing else.

    python tests/check_static.py
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

root = Path(tempfile.mkdtemp(prefix="stu-static-"))
web = root / "web"
(web / "assets").mkdir(parents=True)
(web / "download").mkdir()
(web / "index.html").write_text("<!doctype html><title>shell</title>", encoding="utf-8")
(web / "assets" / "app-abc123.js").write_text("console.log(1)", encoding="utf-8")
(web / "download" / "stu-assistant.apk").write_bytes(b"PK\x03\x04" + b"0" * 2048)
(root / "secret.txt").write_text("must never be served", encoding="utf-8")

os.environ["STU_APP_HOME"] = str(root / "data")
os.environ["STU_APP_JWT_SECRET"] = "z" * 48
os.environ["STU_APP_WEB_DIR"] = str(web)
os.environ.pop("STU_APP_DEMO", None)

from fastapi.testclient import TestClient  # noqa: E402

from app.main import app  # noqa: E402

c = TestClient(app)
failures = []


def expect(cond: bool, what: str) -> None:
    print(("ok   " if cond else "FAIL ") + what)
    if not cond:
        failures.append(what)


r = c.get("/")
expect(r.status_code == 200 and "shell" in r.text and r.headers["cache-control"] == "no-cache", "/ → index.html, revalidated")
r = c.get("/some/deep/link")
expect(r.status_code == 200 and "shell" in r.text, "unknown page path → app shell (client routing)")
r = c.get("/assets/app-abc123.js")
expect(r.status_code == 200 and "immutable" in r.headers["cache-control"], "hashed asset → long cache")
r = c.head("/download/stu-assistant.apk")
expect(r.status_code == 200 and r.headers["content-type"] == "application/vnd.android.package-archive", "APK → 200 with APK type")
expect("attachment" in r.headers.get("content-disposition", ""), "APK downloads as a file")
expect(c.get("/download/missing.apk").status_code == 404, "missing download → 404")
for attempt in ("/../secret.txt", "/%2e%2e/secret.txt", "/assets/..%2f..%2fsecret.txt", "/..%5csecret.txt"):
    r = c.get(attempt)
    expect("must never be served" not in r.text, f"traversal {attempt} does not leak files outside the web folder")
r = c.get("/v1/nothing-here")
expect(r.status_code == 404 and r.json().get("detail") == "not_found", "unknown /v1 path → JSON 404")
expect(c.get("/").headers.get("x-content-type-options") == "nosniff", "security headers on pages")

print(f"\n{'ALL OK' if not failures else f'{len(failures)} FAILED'}")
sys.exit(1 if failures else 0)
