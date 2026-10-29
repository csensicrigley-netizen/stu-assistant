"""Tiny in-process sliding-window limiter.

The service runs as a single process (bind jobs live in memory), so a dict of
timestamps is enough. Keys are client IPs for sign-in / sign-up and user ids
for campus binding.
"""
from __future__ import annotations

import threading
import time
from collections import deque


class SlidingLimiter:
    def __init__(self, limit: int, window_seconds: float):
        self.limit = limit
        self.window = window_seconds
        self._hits: dict[str, deque[float]] = {}
        self._guard = threading.Lock()

    def allow(self, key: str) -> bool:
        """Record one attempt; False when the key is over its limit."""
        now = time.monotonic()
        with self._guard:
            q = self._hits.setdefault(key, deque())
            while q and now - q[0] > self.window:
                q.popleft()
            if len(q) >= self.limit:
                return False
            q.append(now)
            if len(self._hits) > 50_000:  # bound memory under abuse
                for k in [k for k, v in self._hits.items() if not v][:10_000]:
                    self._hits.pop(k, None)
            return True


# Campus Wi-Fi puts hundreds of students behind a few public addresses, so
# per-IP limits are loose; guessing one account's passphrase is limited per
# (IP, email) instead.
login_account_limiter = SlidingLimiter(limit=8, window_seconds=300)
login_ip_limiter = SlidingLimiter(limit=120, window_seconds=300)
register_limiter = SlidingLimiter(limit=600, window_seconds=3600)
bind_limiter = SlidingLimiter(limit=6, window_seconds=3600)
