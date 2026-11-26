"""Media endpoints: info, download, convert, files."""

from __future__ import annotations

import re
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse

from prodl_core import ffmpeg_info

from ..config import Settings
from ..deps import SettingsDep, TasksDep, accepted
from ..schemas import (
    ConvertRequest,
    DownloadRequest,
    InfoResponse,
    TaskAccepted,
)
from ..services import media as media_service

router = APIRouter(tags=["media"])

_SAFE_SUFFIX = re.compile(r"^\.[A-Za-z0-9]{1,8}$")


# ── metadata ────────────────────────────────────────────────────────────────
@router.get("/info", response_model=InfoResponse, summary="Extract metadata")
async def info(
    settings: SettingsDep,
    url: str = Query(..., min_length=4, description="Media URL"),
    sponsorblock: bool = Query(default=False),
    categories: str | None = Query(
        default=None, description="Comma-separated SponsorBlock categories"
    ),
) -> InfoResponse:
    selected = (
        [item.strip() for item in categories.split(",") if item.strip()]
        if categories
        else None
    )
    try:
        return await media_service.get_video_info(
            settings, url.strip(), sponsorblock=sponsorblock, categories=selected
        )
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=400, detail=media_service.clean_error(exc)
        ) from exc


# ── download ────────────────────────────────────────────────────────────────
@router.post(
    "/download",
    response_model=TaskAccepted,
    status_code=202,
    summary="Start a download (returns task_id)",
)
async def start_download(
    payload: DownloadRequest, settings: SettingsDep, tasks: TasksDep
) -> TaskAccepted:
    task = tasks.create(
        "download",
        meta={
            "url": payload.url,
            "audio_only": payload.audio_only,
            "format_id": payload.format_selector,
        },
    )
    tasks.submit(media_service.run_download, task, payload, settings, tasks)
    return accepted(task, settings)


# ── convert ─────────────────────────────────────────────────────────────────
@router.post(
    "/convert",
    response_model=TaskAccepted,
    status_code=202,
    summary="Convert or trim a server-side file",
)
async def start_convert(
    payload: ConvertRequest, settings: SettingsDep, tasks: TasksDep
) -> TaskAccepted:
    if not ffmpeg_info().available:
        raise HTTPException(status_code=503, detail="ffmpeg is not available")

    try:
        if payload.task_id:
            source = media_service.resolve_download_artifact(
                settings, payload.task_id, tasks
            )
        elif payload.path:
            source = media_service.resolve_relative_path(settings, payload.path)
        else:
            raise HTTPException(
                status_code=400,
                detail="Provide task_id, path, or upload a file to /api/convert/upload",
            )
    except media_service.SourceError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    task = tasks.create(
        "convert",
        meta={"source": str(source), "target_format": payload.target_format},
    )
    tasks.submit(media_service.run_convert, task, source, payload, settings, tasks)
    return accepted(task, settings)


@router.post(
    "/convert/upload",
    response_model=TaskAccepted,
    status_code=202,
    summary="Upload a file and convert it",
)
async def start_convert_upload(
    settings: SettingsDep,
    tasks: TasksDep,
    file: UploadFile = File(..., description="Media file to convert"),
    target_format: str = Form("mp3"),
    audio_bitrate: str = Form("192k"),
    video_crf: int = Form(23),
    start: float | None = Form(None),
    end: float | None = Form(None),
) -> TaskAccepted:
    if not ffmpeg_info().available:
        raise HTTPException(status_code=503, detail="ffmpeg is not available")

    try:
        request = ConvertRequest(
            target_format=target_format,
            audio_bitrate=audio_bitrate,
            video_crf=video_crf,
            start=start,
            end=end,
        )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=media_service.clean_error(exc)) from exc

    source = await _store_upload(file, settings)
    task = tasks.create(
        "convert",
        meta={"source": str(source), "target_format": request.target_format},
    )
    tasks.submit(media_service.run_convert, task, source, request, settings, tasks)
    return accepted(task, settings)


async def _store_upload(file: UploadFile, settings: Settings) -> Path:
    suffix = Path(file.filename or "").suffix
    if not _SAFE_SUFFIX.match(suffix):
        suffix = ".bin"

    settings.uploads_dir.mkdir(parents=True, exist_ok=True)
    destination = settings.uploads_dir / f"upload-{tasks_token()}{suffix.lower()}"
    limit = settings.max_upload_mb * 1024 * 1024
    written = 0

    with destination.open("wb") as handle:
        while chunk := await file.read(1024 * 1024):
            written += len(chunk)
            if written > limit:
                handle.close()
                destination.unlink(missing_ok=True)
                raise HTTPException(
                    status_code=413,
                    detail=f"Upload exceeds {settings.max_upload_mb} MiB",
                )
            handle.write(chunk)

    await file.close()
    if written == 0:
        destination.unlink(missing_ok=True)
        raise HTTPException(status_code=422, detail="Uploaded file is empty")
    return destination


def tasks_token() -> str:
    import uuid

    return uuid.uuid4().hex[:12]


# ── finished artefacts ──────────────────────────────────────────────────────
@router.get("/files/{task_id}", summary="Download a finished artefact")
async def get_file(task_id: str, settings: SettingsDep, tasks: TasksDep) -> FileResponse:
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Unknown task_id")
    try:
        path = media_service.task_file(task, settings)
    except media_service.SourceError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc

    media_type = media_service.guess_media_type(path.name)
    return FileResponse(path=path, filename=path.name, media_type=media_type)


@router.delete("/files/{task_id}", status_code=204, summary="Delete a finished artefact")
async def delete_file(task_id: str, settings: SettingsDep, tasks: TasksDep) -> None:
    task = tasks.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Unknown task_id")
    try:
        path = media_service.task_file(task, settings)
        path.unlink(missing_ok=True)
    except media_service.SourceError:
        pass
    tasks.delete(task_id)
