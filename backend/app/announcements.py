"""Public home messages, edited as JSON on the server without an App release.

Configuration is read for each request. Bad configuration fails closed: it must
never resurrect a message an operator has just disabled or removed.
"""
from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Query, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from .config import settings

log = logging.getLogger("stu_app.announcements")
router = APIRouter()
EXAMPLE_FILE = Path(__file__).with_name("announcements.example.json")
MAX_CONFIG_BYTES = 256 * 1024
ASSET_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\.(?:png|jpe?g|webp|gif)", re.IGNORECASE)
ASSET_PREFIX = "/v1/announcements/assets/"
MEDIA_TYPES = {".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
               ".webp": "image/webp", ".gif": "image/gif"}
AnnouncementEffect = Literal["star-struck", "heart-on-fire", "folded-hands", "party-popper", "keycap-one-6"]


def _https_url(value: str) -> bool:
    if any(ch.isspace() or ord(ch) < 32 for ch in value) or "\\" in value:
        return False
    try:
        url = urlsplit(value)
        return (url.scheme == "https" and bool(url.hostname) and url.username is None
                and url.password is None and url.port != 0)
    except ValueError:
        return False


class Announcement(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    id: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
    revision: int = Field(default=1, ge=1, le=2_147_483_647, strict=True)
    title: str = Field(min_length=1, max_length=120)
    body: str = Field(default="", max_length=3000)
    eyebrow: str = Field(default="开发者来信", max_length=40)
    image_url: str | None = Field(default=None, max_length=2048)
    image_alt: str = Field(default="", max_length=240)
    action_label: str = Field(default="了解更多", min_length=1, max_length=32)
    action_url: str | None = Field(default=None, max_length=2048)
    display: Literal["once", "daily", "always"] = "once"
    priority: int = Field(default=0, ge=-10000, le=10000, strict=True)
    effects: list[AnnouncementEffect] = Field(default_factory=list, max_length=5)
    enabled: bool = Field(default=True, strict=True)
    platforms: list[Literal["android", "web"]] = Field(default_factory=lambda: ["android", "web"], max_length=2)
    starts_at: datetime | None = None
    ends_at: datetime | None = None
    min_version_code: int | None = Field(default=None, ge=0, le=10_000_000, strict=True)
    max_version_code: int | None = Field(default=None, ge=0, le=10_000_000, strict=True)

    @field_validator("action_url")
    @classmethod
    def validate_action(cls, value: str | None) -> str | None:
        if value is not None and not _https_url(value):
            raise ValueError("action_url must be an absolute HTTPS URL")
        return value

    @field_validator("image_url")
    @classmethod
    def validate_image(cls, value: str | None) -> str | None:
        if value is not None and not (_https_url(value) or
                (value.startswith(ASSET_PREFIX) and ASSET_NAME.fullmatch(value[len(ASSET_PREFIX):]))):
            raise ValueError("image_url must be HTTPS or an announcement asset path")
        return value

    @field_validator("starts_at", "ends_at")
    @classmethod
    def require_timezone(cls, value: datetime | None) -> datetime | None:
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("message times require a timezone, for example +08:00")
        return value

    @model_validator(mode="after")
    def validate_range(self) -> "Announcement":
        if self.starts_at is not None and self.ends_at is not None and self.ends_at <= self.starts_at:
            raise ValueError("ends_at must be later than starts_at")
        if (self.min_version_code is not None and self.max_version_code is not None
                and self.max_version_code < self.min_version_code):
            raise ValueError("max_version_code must be at least min_version_code")
        return self

    def public(self) -> dict:
        return self.model_dump(exclude={"enabled", "platforms", "starts_at", "ends_at",
                                        "min_version_code", "max_version_code"})


class AnnouncementConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: bool = Field(default=True, strict=True)
    announcements: list[Announcement] = Field(default_factory=list, max_length=100)

    @model_validator(mode="after")
    def unique_ids(self) -> "AnnouncementConfig":
        if len({item.id for item in self.announcements}) != len(self.announcements):
            raise ValueError("announcement ids must be unique")
        return self


def config_path() -> Path:
    return settings.announcements_file or settings.root / "announcements.json"


def load_config() -> AnnouncementConfig:
    path = config_path()
    # A custom path signals an explicit operator choice; a missing custom file
    # therefore disables messages instead of silently publishing the example.
    if not path.exists() and settings.announcements_file is None:
        path = EXAMPLE_FILE
    try:
        with path.open("rb") as stream:
            contents = stream.read(MAX_CONFIG_BYTES + 1)
        if len(contents) > MAX_CONFIG_BYTES:
            raise ValueError("configuration exceeds 256 KiB")
        raw = json.loads(contents.decode("utf-8-sig"))
        if isinstance(raw, list):
            raw = {"announcements": raw}
        return AnnouncementConfig.model_validate(raw)
    except (OSError, UnicodeError, ValueError, ValidationError):
        # Do not print content/URLs from configuration into server logs.
        log.warning("首页消息配置无效或无法读取，本次停用弹窗")
        return AnnouncementConfig(enabled=False)


def active_announcements(*, platform: Literal["android", "web"], version_code: int | None = None,
                         at: datetime | None = None) -> list[dict]:
    config = load_config()
    if not config.enabled:
        return []
    instant = at or datetime.now(timezone.utc)
    active = []
    for item in config.announcements:
        if not item.enabled or platform not in item.platforms:
            continue
        if item.starts_at is not None and instant < item.starts_at:
            continue
        if item.ends_at is not None and instant >= item.ends_at:
            continue
        if item.min_version_code is not None and (version_code is None or version_code < item.min_version_code):
            continue
        if item.max_version_code is not None and (version_code is None or version_code > item.max_version_code):
            continue
        active.append(item)
    active.sort(key=lambda item: item.priority, reverse=True)
    return [item.public() for item in active]


@router.get("/v1/announcements")
def announcements(response: Response, platform: Literal["android", "web"] = "web",
                  version_code: int | None = Query(default=None, ge=0, le=10_000_000)) -> dict:
    response.headers["Cache-Control"] = "no-store"
    return {"announcements": active_announcements(platform=platform, version_code=version_code)}


@router.get("/v1/announcements/assets/{filename}")
def announcement_asset(filename: str) -> FileResponse:
    if not ASSET_NAME.fullmatch(filename):
        raise HTTPException(404, "announcement_image_not_found")
    root = (settings.root / "announcements-assets").resolve()
    target = (root / filename).resolve()
    # Reject symlinks that escape the image folder as well as directory paths.
    if not target.is_relative_to(root) or not target.is_file():
        raise HTTPException(404, "announcement_image_not_found")
    return FileResponse(target, media_type=MEDIA_TYPES[target.suffix.lower()],
                        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})


def _main() -> int:
    """Validate the exact file clients will receive before replacing live JSON."""
    import argparse
    import sys

    parser = argparse.ArgumentParser(description="校验首页消息 JSON；不修改服务器配置")
    parser.add_argument("file", type=Path, nargs="?")
    parser.add_argument("--check", type=Path, help="待校验的配置文件路径")
    args = parser.parse_args()
    path = args.check or args.file
    if path is None or (args.check is not None and args.file is not None):
        parser.error("请提供一个文件路径，或使用 --check <文件路径>")
    try:
        contents = path.read_bytes()
        if len(contents) > MAX_CONFIG_BYTES:
            raise ValueError("configuration exceeds 256 KiB")
        raw = json.loads(contents.decode("utf-8-sig"))
        config = AnnouncementConfig.model_validate({"announcements": raw} if isinstance(raw, list) else raw)
    except (OSError, UnicodeError, ValueError, ValidationError) as exc:
        print(f"校验失败：{exc}", file=sys.stderr)
        return 1
    print(f"校验通过：{len(config.announcements)} 条消息；总开关 {'开启' if config.enabled else '关闭'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
