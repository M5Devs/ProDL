"""Service layer: the only place that talks to ``prodl_core``.

Routers stay thin and tests can monkeypatch these functions to keep the suite
hermetic (no network, no ffmpeg, no yt-dlp).
"""

from __future__ import annotations

import mimetypes
import re
from pathlib import Path
from typing import Any

from prodl_core import (
    ConversionError,
    DownloadCancelled,
    DownloadOptions,
    FileRef,
    ProgressEvent,
    ProDLDownloader,
    SearchResult,
    convert_media_sync,
    existing_cookies,
    extract_video_id,
    get_segments,
    make_postprocessor_hook,
    make_progress_hook,
    normalize_segments,
    search_videos,
)

from ..config import Settings
from ..schemas import (
    ConvertRequest,
    DownloadRequest,
    InfoResponse,
    SponsorBlockInfo,
    SponsorSegment,
)
from ..tasks import Task, TaskManager

_ANSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b[@-Z\\-_]")
_MAX_ERROR_CHARS = 600


def clean_error(exc: BaseException) -> str:
    """Short, single-line, ANSI-free error text safe to show in the UI."""
    message = _ANSI_RE.sub("", str(exc) or exc.__class__.__name__)
    message = " ".join(message.split())
    if message.lower().startswith("error:"):
        message = message[6:].strip()
    return message[:_MAX_ERROR_CHARS] or exc.__class__.__name__


def guess_media_type(filename: str) -> str:
    return mimetypes.guess_type(filename)[0] or "application/octet-stream"


def cookies_for(settings: Settings, *, enabled: bool = True) -> Path | None:
    return existing_cookies(settings.cookies_file) if enabled else None


# ── read-only operations ────────────────────────────────────────────────────
async def search(
    settings: Settings, query: str, limit: int | None = None
) -> list[SearchResult]:
    limit = min(limit or settings.search_limit, settings.search_limit_max)
    return await search_videos(
        query, limit, cookies_file=cookies_for(settings, enabled=True)
    )


async def get_video_info(
    settings: Settings,
    url: str,
    *,
    sponsorblock: bool = False,
    categories: list[str] | None = None,
) -> InfoResponse:
    cookies = cookies_for(settings, enabled=True)
    info = await ProDLDownloader.get_info(url, cookies_file=cookies)
    response = InfoResponse(**info.model_dump(), cookies_used=bool(cookies))

    if sponsorblock:
        selected = list(categories or settings.sponsorblock_categories)
        video_id = extract_video_id(info.webpage_url or url)
        segments: list[SponsorSegment] = []
        error: str | None = None
        if video_id:
            try:
                raw = await get_segments(
                    video_id, selected, api=settings.sponsorblock_api
                )
                segments = [SponsorSegment(**item) for item in normalize_segments(raw)]
            except Exception as exc:  # SponsorBlock is strictly optional
                error = clean_error(exc)
        response.sponsorblock = SponsorBlockInfo(
            requested=True,
            video_id=video_id,
            categories=selected,
            segments=segments,
            error=error,
        )
    return response


# ── background jobs (run inside the task manager's thread pool) ─────────────
def run_download(
    task: Task, request: DownloadRequest, settings: Settings, manager: TaskManager
) -> None:
    def on_event(event: ProgressEvent) -> None:
        manager.publish(task.id, event)

    try:
        manager.publish(
            task.id,
            ProgressEvent(status="downloading", percent=0.0, message="Starting download…"),
        )
        cookie_path = cookies_for(settings, enabled=request.use_cookies)
        options = DownloadOptions(
            url=request.url,
            format_id=request.format_selector,
            audio_only=request.audio_only,
            audio_format=request.audio_format,
            audio_quality=request.audio_quality,
            subtitles=request.subtitles,
            sub_langs=request.subtitle_langs,
            sub_embed=request.embed_subtitles,
            auto_subs=request.auto_subtitles,
            thumbnail=request.thumbnail,
            thumb_embed=request.embed_thumbnail,
            sponsorblock=request.sponsorblock,
            sb_action=request.sponsorblock_action,
            sb_categories=request.sponsorblock_categories,
            output_dir=str(settings.download_dir),
            cookies_file=str(cookie_path) if cookie_path else None,
            max_filesize=request.max_filesize,
            concurrent_fragments=request.concurrent_fragments,
            progress_hook=make_progress_hook(
                on_event, task_id=task.id, cancel_event=task.cancel_event
            ),
            postprocessor_hook=make_postprocessor_hook(on_event, task_id=task.id),
        )
        result = ProDLDownloader.download_sync(options)
    except DownloadCancelled:
        manager.mark_canceled(task.id)
        return
    except BaseException as exc:  # noqa: BLE001 - surface every failure to the client
        manager.fail(task.id, clean_error(exc))
        return

    task.meta["filepath"] = result.filepath
    file_ref = FileRef(
        filename=result.filename,
        filesize=result.filesize,
        download_url=f"{settings.api_prefix}/files/{task.id}",
        media_type=guess_media_type(result.filename),
    )
    manager.finish(task.id, file=file_ref, message="Download complete")


def run_convert(
    task: Task,
    source: Path,
    request: ConvertRequest,
    settings: Settings,
    manager: TaskManager,
) -> None:
    def on_event(event: ProgressEvent) -> None:
        manager.publish(task.id, event)

    try:
        manager.publish(
            task.id,
            ProgressEvent(status="converting", percent=0.0, message="Starting conversion…"),
        )
        result = convert_media_sync(
            source,
            request.target_format,
            audio_bitrate=request.audio_bitrate,
            video_crf=request.video_crf,
            start=request.start,
            end=request.end,
            output_dir=settings.download_dir,
            on_event=on_event,
            task_id=task.id,
        )
    except BaseException as exc:  # noqa: BLE001
        manager.fail(task.id, clean_error(exc))
        return

    task.meta["filepath"] = result.output_path
    file_ref = FileRef(
        filename=result.filename,
        filesize=result.filesize,
        download_url=f"{settings.api_prefix}/files/{task.id}",
        media_type=guess_media_type(result.filename),
    )
    manager.finish(
        task.id,
        file=file_ref,
        message=f"Converted to {result.format.upper()}",
    )


# ── helpers ─────────────────────────────────────────────────────────────────
class SourceError(ValueError):
    """Raised when a conversion source cannot be resolved safely."""


def resolve_download_artifact(settings: Settings, task_id: str, manager: TaskManager) -> Path:
    task = manager.get(task_id)
    if task is None:
        raise SourceError("Unknown task_id")
    raw = task.meta.get("filepath")
    if not raw:
        raise SourceError("Task has no finished file yet")
    return _ensure_inside(Path(raw), settings.download_dir)


def resolve_relative_path(settings: Settings, relative: str) -> Path:
    if not relative or relative.strip() in {".", ".."}:
        raise SourceError("path must point to a file inside the download directory")
    candidate = _ensure_inside(settings.download_dir / relative, settings.download_dir)
    if not candidate.is_file():
        raise SourceError("File not found in the download directory")
    return candidate


def _ensure_inside(candidate: Path, root: Path) -> Path:
    resolved = candidate.expanduser().resolve()
    root_resolved = root.expanduser().resolve()
    if not resolved.is_relative_to(root_resolved):
        raise SourceError("Resolved path escapes the download directory")
    if not resolved.is_file():
        raise SourceError("File not found")
    return resolved


def task_file(task: Task, settings: Settings) -> Path:
    raw = task.meta.get("filepath")
    if not raw:
        raise SourceError("Task has no finished file yet")
    path = Path(raw).expanduser().resolve()
    if not path.is_file():
        raise SourceError("File is no longer available")
    return path


def stats_for(task: Task) -> dict[str, Any]:
    return {
        "task_id": task.id,
        "kind": task.kind,
        "status": task.status,
        "created_at": task.created_at,
        "updated_at": task.updated_at,
        "error": task.error,
        "file": task.file,
        "last_event": task.last_event,
    }
