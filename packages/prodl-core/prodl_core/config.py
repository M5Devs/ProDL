"""Cross-platform runtime paths.

Every path is built with :mod:`pathlib` and can be overridden through an
environment variable, so the same code runs unchanged on Linux, macOS,
Windows, inside Docker, and inside a local server shell (Termux / Android
WebView wrapper).  Nothing here assumes a POSIX ``/tmp`` layout.
"""

from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

APP_NAME = "ProDL"


def _env_path(name: str) -> Path | None:
    raw = os.environ.get(name)
    if not raw:
        return None
    return Path(raw).expanduser()


def ensure_dir(path: str | os.PathLike[str]) -> Path:
    """Create ``path`` (and parents) if missing and return it as a Path."""
    resolved = Path(path).expanduser()
    resolved.mkdir(parents=True, exist_ok=True)
    return resolved


def default_data_dir() -> Path:
    """Platform-appropriate private data directory for ProDL."""
    override = _env_path("PRODL_DATA_DIR")
    if override is not None:
        return override

    if sys.platform == "win32":
        for var in ("LOCALAPPDATA", "APPDATA"):
            base = os.environ.get(var)
            if base:
                return Path(base) / APP_NAME
        return Path.home() / "AppData" / "Local" / APP_NAME

    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / APP_NAME

    xdg = os.environ.get("XDG_DATA_HOME")
    if xdg:
        return Path(xdg) / APP_NAME
    return Path.home() / ".local" / "share" / APP_NAME


def writable_dir(preferred: str | os.PathLike[str]) -> Path:
    """Return ``preferred`` when writable, else a per-user temp fallback.

    Sandboxed shells (Android local-server, read-only containers) may not be
    able to write to the default location; falling back keeps the server
    usable instead of failing at import time.
    """
    candidate = Path(preferred).expanduser()
    try:
        ensure_dir(candidate)
        if os.access(candidate, os.W_OK):
            return candidate
    except OSError:
        pass

    fallback = Path(tempfile.gettempdir()) / APP_NAME
    return ensure_dir(fallback)


def data_dir() -> Path:
    return writable_dir(default_data_dir())


def downloads_dir() -> Path:
    return writable_dir(_env_path("PRODL_DOWNLOAD_DIR") or data_dir() / "downloads")


def uploads_dir() -> Path:
    return writable_dir(data_dir() / "uploads")


def temp_dir() -> Path:
    return writable_dir(data_dir() / "tmp")


def cookies_file() -> Path:
    return _env_path("PRODL_COOKIES_FILE") or data_dir() / "cookies.txt"
