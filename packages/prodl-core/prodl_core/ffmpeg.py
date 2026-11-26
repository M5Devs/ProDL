"""FFmpeg discovery and media probing, without assuming a POSIX layout."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path


class FFmpegNotFound(RuntimeError):
    pass


@dataclass(frozen=True)
class BinaryInfo:
    available: bool
    path: str | None = None
    version: str | None = None


def find_binary(name: str, env_var: str | None = None) -> str | None:
    """Locate an executable on PATH, via an env override, or next to the app."""
    if env_var:
        override = os.environ.get(env_var)
        if override:
            candidate = Path(override).expanduser()
            if candidate.is_file():
                return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    # Windows builds are often shipped next to the app rather than on PATH.
    for suffix in ("", ".exe"):
        local = Path(__file__).resolve().parent / f"{name}{suffix}"
        if local.is_file():
            return str(local)
    return None


def _first_line(binary: str, *args: str) -> str | None:
    try:
        completed = subprocess.run(
            [binary, *args],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    output = (completed.stdout or completed.stderr or "").strip()
    return output.splitlines()[0] if output else None


@lru_cache(maxsize=4)
def ffmpeg_info() -> BinaryInfo:
    path = find_binary("ffmpeg", "PRODL_FFMPEG")
    if not path:
        return BinaryInfo(available=False)
    return BinaryInfo(available=True, path=path, version=_first_line(path, "-version"))


@lru_cache(maxsize=4)
def ffprobe_info() -> BinaryInfo:
    path = find_binary("ffprobe", "PRODL_FFPROBE")
    if not path:
        return BinaryInfo(available=False)
    return BinaryInfo(available=True, path=path, version=_first_line(path, "-version"))


def require_ffmpeg() -> str:
    info = ffmpeg_info()
    if not info.available or not info.path:
        raise FFmpegNotFound(
            "ffmpeg was not found. Install it (apt/brew/winget) or set PRODL_FFMPEG."
        )
    return info.path


def probe_duration(path: str | os.PathLike[str]) -> float | None:
    """Return media duration in seconds using ffprobe when available."""
    info = ffprobe_info()
    if not info.available or not info.path:
        return None
    try:
        completed = subprocess.run(
            [
                info.path,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "json",
                str(path),
            ],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if completed.returncode != 0:
            return None
        payload = json.loads(completed.stdout or "{}")
        duration = payload.get("format", {}).get("duration")
        return float(duration) if duration else None
    except (OSError, subprocess.SubprocessError, ValueError):
        return None


def js_runtime_info() -> BinaryInfo:
    """Report a JS runtime usable by yt-dlp for YouTube signature challenges."""
    for name, env_var in (("deno", "PRODL_DENO"), ("node", None), ("bun", None)):
        path = find_binary(name, env_var)
        if path:
            return BinaryInfo(available=True, path=path, version=_first_line(path, "--version"))
    return BinaryInfo(available=False)
