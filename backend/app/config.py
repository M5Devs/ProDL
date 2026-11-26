"""Backend configuration.

All values come from environment variables with cross-platform defaults, so
the same image works in Docker, on a laptop, and inside an Android local
server shell without editing code.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

from prodl_core import config as core_config
from prodl_core.sponsorblock import DEFAULT_CATEGORIES as SPONSORBLOCK_CATEGORIES

REPO_ROOT = Path(__file__).resolve().parents[2]


def _env_bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def _env_list(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = os.environ.get(name)
    if raw is None:
        return default
    items = tuple(item.strip() for item in raw.split(",") if item.strip())
    return items or default


@dataclass(frozen=True)
class Settings:
    data_dir: Path = field(default_factory=core_config.data_dir)
    download_dir: Path = field(default_factory=core_config.downloads_dir)
    uploads_dir: Path = field(default_factory=core_config.uploads_dir)
    cookies_file: Path = field(default_factory=core_config.cookies_file)
    cors_origins: tuple[str, ...] = ("*",)
    serve_frontend: bool = True
    frontend_dir: Path = REPO_ROOT / "frontend"
    api_prefix: str = "/api"
    search_limit: int = 10
    search_limit_max: int = 50
    max_workers: int = 4
    max_tasks: int = 200
    task_ttl_seconds: int = 3600
    max_upload_mb: int = 512
    sponsorblock_api: str = "https://sponsor.ajay.app/api/skipSegments"
    sponsorblock_categories: tuple[str, ...] = SPONSORBLOCK_CATEGORIES

    @property
    def allow_credentials(self) -> bool:
        # Browsers reject credentials together with a wildcard origin.
        return "*" not in self.cors_origins

    def ensure_directories(self) -> None:
        for directory in (self.data_dir, self.download_dir, self.uploads_dir):
            directory.mkdir(parents=True, exist_ok=True)


def load_settings(**overrides) -> Settings:
    values = {
        "data_dir": core_config.data_dir(),
        "download_dir": core_config.downloads_dir(),
        "uploads_dir": core_config.uploads_dir(),
        "cookies_file": core_config.cookies_file(),
        "cors_origins": _env_list("PRODL_CORS_ORIGINS", ("*",)),
        "serve_frontend": _env_bool("PRODL_SERVE_FRONTEND", True),
        "frontend_dir": Path(
            os.environ.get("PRODL_FRONTEND_DIR") or REPO_ROOT / "frontend"
        ).expanduser(),
        "search_limit": _env_int("PRODL_SEARCH_LIMIT", 10),
        "search_limit_max": _env_int("PRODL_SEARCH_LIMIT_MAX", 50),
        "max_workers": _env_int("PRODL_MAX_WORKERS", 4),
        "max_tasks": _env_int("PRODL_MAX_TASKS", 200),
        "task_ttl_seconds": _env_int("PRODL_TASK_TTL", 3600),
        "max_upload_mb": _env_int("PRODL_MAX_UPLOAD_MB", 512),
        "sponsorblock_api": os.environ.get("PRODL_SPONSORBLOCK_API")
        or "https://sponsor.ajay.app/api/skipSegments",
        "sponsorblock_categories": _env_list(
            "PRODL_SPONSORBLOCK_CATEGORIES", SPONSORBLOCK_CATEGORIES
        ),
    }
    values.update(overrides)
    settings = Settings(**values)
    settings.ensure_directories()
    return settings


@lru_cache(maxsize=1)
def get_cached_settings() -> Settings:
    return load_settings()
