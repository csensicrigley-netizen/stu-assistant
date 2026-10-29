"""厂商无关的「实时活动」推送载荷。"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class LiveUpdate:
    """一条实时活动：标题、说明、起止时刻（epoch 毫秒）、阶段。"""

    title: str
    detail: str
    starts_at: int
    ends_at: int
    phase: str = "before"  # before | during

    def as_dict(self) -> dict:
        return {
            "title": self.title,
            "detail": self.detail,
            "starts_at": int(self.starts_at),
            "ends_at": int(self.ends_at),
            "phase": self.phase if self.phase in {"before", "during"} else "before",
        }
