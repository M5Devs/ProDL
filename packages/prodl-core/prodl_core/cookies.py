"""On-device storage of Netscape-format cookies.

Cookies are only ever written to a local file with ``0600`` permissions and
never logged or echoed back through the API.  They let yt-dlp reach
age-restricted videos and private playlists belonging to the device owner.
"""

from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from . import config

NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
MAX_COOKIE_BYTES = 2 * 1024 * 1024


class CookieFormatError(ValueError):
    """Raised when the submitted text is not valid Netscape cookie data."""


@dataclass(frozen=True)
class CookieStatus:
    configured: bool
    cookie_count: int
    updated_at: str | None
    path: str


def _cookie_lines(text: str) -> list[str]:
    return [
        line
        for line in text.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def count_cookies(text: str) -> int:
    return len(_cookie_lines(text))


def validate_netscape(text: str) -> int:
    """Validate cookie text and return the number of cookie rows.

    Only line numbers are reported on failure so cookie values never leak
    into logs or HTTP error responses.
    """
    if not text or not text.strip():
        raise CookieFormatError("Cookie file is empty")
    if len(text.encode("utf-8")) > MAX_COOKIE_BYTES:
        raise CookieFormatError("Cookie file is too large (max 2 MiB)")

    rows = _cookie_lines(text)
    if not rows:
        raise CookieFormatError("No cookie rows found")

    for index, line in enumerate(rows, start=1):
        fields = line.split("\t")
        if len(fields) != 7:
            raise CookieFormatError(
                f"Cookie row {index} has {len(fields)} tab-separated fields, expected 7"
            )
        if not fields[0] or not fields[5]:
            raise CookieFormatError(f"Cookie row {index} is missing domain or name")
    return len(rows)


def save_netscape(text: str, path: str | os.PathLike[str] | None = None) -> CookieStatus:
    """Atomically persist validated cookie text with owner-only permissions."""
    count = validate_netscape(text)
    target = Path(path) if path else config.cookies_file()
    target.parent.mkdir(parents=True, exist_ok=True)

    body = text if text.lstrip().startswith("#") else f"{NETSCAPE_HEADER}\n{text}"
    if not body.endswith("\n"):
        body += "\n"

    fd, tmp_name = tempfile.mkstemp(prefix=".prodl-cookies-", dir=str(target.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)
        try:
            os.chmod(tmp_name, 0o600)
        except OSError:  # pragma: no cover - non-POSIX filesystems
            pass
        os.replace(tmp_name, target)
    finally:
        if os.path.exists(tmp_name):
            os.unlink(tmp_name)

    return status(target, cookie_count=count)


def status(
    path: str | os.PathLike[str] | None = None, *, cookie_count: int | None = None
) -> CookieStatus:
    target = Path(path) if path else config.cookies_file()
    if not target.is_file():
        return CookieStatus(False, 0, None, str(target))

    updated = datetime.fromtimestamp(target.stat().st_mtime, tz=timezone.utc)
    count = cookie_count
    if count is None:
        try:
            count = count_cookies(target.read_text(encoding="utf-8", errors="replace"))
        except OSError:
            count = 0
    return CookieStatus(True, count, updated.isoformat(timespec="seconds"), str(target))


def clear(path: str | os.PathLike[str] | None = None) -> bool:
    target = Path(path) if path else config.cookies_file()
    try:
        target.unlink()
        return True
    except FileNotFoundError:
        return False


def existing_path(path: str | os.PathLike[str] | None = None) -> Path | None:
    """Return the cookie file only when it exists (handy for yt-dlp options)."""
    target = Path(path) if path else config.cookies_file()
    return target if target.is_file() else None
