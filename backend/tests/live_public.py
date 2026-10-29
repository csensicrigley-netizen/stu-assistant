"""One live read of the two anonymous sources (no login, no school account):
one charging device, and the school's public notice listings.

    python tests/live_public.py

Prints counts and shapes only.
"""
from __future__ import annotations

import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main() -> int:
    from app import charging
    from app.collectors import collect_notices

    t = time.time()
    try:
        state = charging.ports("4GR00008491", 20)
        c = state.get("counts") or {}
        print(f"charging 图书馆 TSG-3: ports={len(state.get('ports') or [])} counts={c} "
              f"complete={state.get('ports_complete')} ({time.time() - t:.1f}s)")
    except Exception as exc:  # noqa: BLE001
        print(f"charging FAILED: {getattr(exc, 'code', type(exc).__name__)}")

    t = time.time()
    with tempfile.TemporaryDirectory():
        result = collect_notices(None, limit=5, include_oa=True)
    notices = result.kind_records.get("notice", [])
    print(f"notices: code={result.code} detail={result.detail!r} count={len(notices)} ({time.time() - t:.1f}s)")
    for n in notices[:4]:
        print(f"  - [{n.get('category') or n.get('department') or n.get('source')}] "
              f"{(n.get('title') or '')[:36]} | {n.get('published_at')}")
    keys = sorted(notices[0].keys()) if notices else []
    print(f"  fields: {keys}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
