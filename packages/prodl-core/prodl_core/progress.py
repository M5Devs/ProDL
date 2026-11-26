"""Normalisation of yt-dlp / ffmpeg progress into :class:`ProgressEvent`."""

from __future__ import annotations

import threading
import time
from pathlib import Path
from typing import Callable

from .models import ProgressEvent

try:  # yt-dlp exposes DownloadCancelled for aborting from inside a hook
    from yt_dlp.utils import DownloadCancelled
except Exception:  # pragma: no cover - very old yt-dlp
    class DownloadCancelled(Exception):  # type: ignore[no-redef]
        pass


def _as_int(value) -> int | None:
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _percent(d: dict) -> float | None:
    downloaded = d.get("downloaded_bytes")
    total = d.get("total_bytes") or d.get("total_bytes_estimate")
    if downloaded is not None and total:
        return round(min(downloaded / total * 100, 100.0), 2)

    frag_index, frag_count = d.get("fragment_index"), d.get("fragment_count")
    if frag_index is not None and frag_count:
        return round(min(frag_index / frag_count * 100, 100.0), 2)

    raw = (d.get("_percent_str") or "").strip().rstrip("%")
    try:
        return round(float(raw), 2)
    except ValueError:
        return None


def normalize_ytdlp_progress(d: dict, *, task_id: str | None = None) -> ProgressEvent:
    """Map a raw yt-dlp progress dict onto the public event schema."""
    raw_status = d.get("status")
    if raw_status == "finished":
        status = "postprocessing"
        message = "Download complete, post-processing…"
    elif raw_status == "error":
        status = "error"
        message = "Download failed"
    else:
        status = "downloading"
        message = None

    filename = d.get("filename")
    info = d.get("info_dict") or {}
    total = d.get("total_bytes") or d.get("total_bytes_estimate")

    return ProgressEvent(
        task_id=task_id,
        status=status,
        percent=_percent(d) if status != "postprocessing" else 100.0,
        downloaded_bytes=_as_int(d.get("downloaded_bytes")),
        total_bytes=_as_int(total),
        speed=float(d["speed"]) if d.get("speed") else None,
        speed_str=(d.get("_speed_str") or "").strip() or None,
        eta=_as_int(d.get("eta")),
        elapsed=round(time.time() - float(d["start_time"]), 2) if d.get("start_time") else None,
        filename=Path(filename).name if filename else None,
        title=info.get("title"),
        message=message,
        error=None,
        timestamp=time.time(),
    )


def make_progress_hook(
    on_event: Callable[[ProgressEvent], None],
    *,
    task_id: str | None = None,
    cancel_event: threading.Event | None = None,
) -> Callable[[dict], None]:
    """Build a yt-dlp ``progress_hooks`` entry that emits ProgressEvents."""

    def hook(d: dict) -> None:
        if cancel_event is not None and cancel_event.is_set():
            raise DownloadCancelled("canceled by client")
        on_event(normalize_ytdlp_progress(d, task_id=task_id))

    return hook


def make_postprocessor_hook(
    on_event: Callable[[ProgressEvent], None],
    *,
    task_id: str | None = None,
) -> Callable[[dict], None]:
    def hook(d: dict) -> None:
        name = d.get("postprocessor") or "post-processing"
        on_event(
            ProgressEvent(
                task_id=task_id,
                status="postprocessing",
                percent=100.0,
                message=f"{name}: {d.get('status', 'running')}",
                timestamp=time.time(),
            )
        )

    return hook


def ffmpeg_progress_event(
    out_time_seconds: float,
    total_seconds: float | None,
    *,
    task_id: str | None = None,
    message: str = "Converting…",
) -> ProgressEvent:
    percent = None
    if total_seconds:
        percent = round(min(out_time_seconds / total_seconds * 100, 100.0), 2)
    return ProgressEvent(
        task_id=task_id,
        status="converting",
        percent=percent,
        elapsed=out_time_seconds,
        total_bytes=None,
        message=message,
        timestamp=time.time(),
    )
