"""Generate web/server version numbers from Android's versionName/versionCode."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent


def sync(root: Path, write: bool = False) -> tuple[str, int]:
    gradle = (root / "web/android/app/build.gradle").read_text(encoding="utf-8-sig")
    name_match = re.search(r'\bversionName\s+"([\d.]+)"', gradle)
    code_match = re.search(r'\bversionCode\s+(\d+)', gradle)
    if not name_match or not code_match:
        raise ValueError("Android versionName/versionCode is missing")
    name, code = name_match[1], int(code_match[1])
    if code < 1:
        raise ValueError("versionCode must be positive")
    target = root / "web/src/lib/version.ts"
    current = target.read_text(encoding="utf-8-sig")
    updated, names = re.subn(r'APP_VERSION_NAME\s*=\s*"[^"]+"', f'APP_VERSION_NAME = "{name}"', current)
    updated, codes = re.subn(r'APP_VERSION_CODE\s*=\s*\d+', f'APP_VERSION_CODE = {code}', updated)
    if names != 1 or codes != 1:
        raise ValueError("Expected exactly one web version constant pair")
    metadata = root / "backend/app/latest_version.json"
    data = json.loads(metadata.read_text(encoding="utf-8-sig"))
    mismatch = updated != current or (data.get("versionName"), data.get("versionCode")) != (name, code)
    if mismatch and not write:
        raise ValueError("Version numbers differ; run python deploy/sync-version.py --write")
    if write:
        target.write_text(updated, encoding="utf-8", newline="\n")
        data.update(versionName=name, versionCode=code)
        metadata.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return name, code


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="Generate constants; otherwise check without editing")
    args = parser.parse_args()
    name, code = sync(ROOT, args.write)
    print(f"Version consistent: {name} ({code})")
