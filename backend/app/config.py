"""Runtime configuration. Everything overridable by env."""
from __future__ import annotations

import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path


def _bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _jwt_secret() -> str:
    # A demo server may run without a configured secret: tokens then die with
    # the process, which is harmless. A real server must never sign with "".
    configured = os.environ.get("STU_APP_JWT_SECRET", "")
    if configured:
        return configured
    return secrets.token_urlsafe(32) if _bool("STU_APP_DEMO") else ""


def _origins() -> tuple[str, ...]:
    # The browser version is same-origin and needs no CORS. These are the
    # packaged App's WebView origins (Android / iOS) and the local dev server.
    raw = os.environ.get(
        "STU_APP_CORS_ORIGINS",
        "https://localhost,capacitor://localhost,http://localhost:5180",
    )
    return tuple(o.strip() for o in raw.split(",") if o.strip())


@dataclass(frozen=True)
class Settings:
    root: Path = field(default_factory=lambda: Path(os.environ.get("STU_APP_HOME", "/data/stu-app")))
    db_path: Path = field(default_factory=lambda: Path(os.environ["STU_APP_HOME"]) / "app.db"
                          if os.environ.get("STU_APP_HOME") else Path("/data/stu-app/app.db"))
    jwt_secret: str = field(default_factory=_jwt_secret)
    jwt_ttl_seconds: int = 60 * 60 * 24 * 30

    # Built web App (web/dist). When set, the API process also serves the
    # browser version, so one service and one domain cover web and the App.
    web_dir: Path | None = field(default_factory=lambda: Path(os.environ["STU_APP_WEB_DIR"])
                                 if os.environ.get("STU_APP_WEB_DIR") else None)
    cors_origins: tuple[str, ...] = field(default_factory=_origins)

    # Throttling (PLAN.md §七)
    sync_min_interval: int = 600          # 每租户每源最小间隔 10 分钟
    global_jw_concurrency: int = 3        # 全局教务并发
    login_max_per_hour: int = 3           # 同租户 1 小时登录次数上限
    login_backoff: tuple[int, ...] = (60, 300, 900)

    demo: bool = field(default_factory=lambda: _bool("STU_APP_DEMO", False))

    @property
    def tenants(self) -> Path:
        return self.root / "tenants"

    def tenant_dir(self, user_id: str) -> Path:
        return self.tenants / user_id

    def tenant_home(self, user_id: str) -> Path:
        return self.tenant_dir(user_id) / "stu-mcp"

    def tenant_key(self, user_id: str) -> Path:
        return self.tenant_dir(user_id) / "master.key"

    def validate(self) -> None:
        if len(self.jwt_secret) < 32:
            raise RuntimeError("STU_APP_JWT_SECRET 未设置或太短；生产必须提供 32 字节以上随机串")
        self.root.mkdir(parents=True, exist_ok=True)
        self.tenants.mkdir(parents=True, exist_ok=True)


settings = Settings()
