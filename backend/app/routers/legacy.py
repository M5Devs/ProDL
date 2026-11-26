"""Deprecated v1 endpoints kept so existing clients keep working.

These mirror the pre-2.0 API (``/health``, ``/info``, ``/download``). They are
documented as deprecated in API_SPEC.md and will be removed in a future major
version; new code should use the ``/api/*`` routes.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from prodl_core import DownloadOptions, ProDLDownloader

from ..deps import SettingsDep
from ..services import media as media_service

router = APIRouter(tags=["legacy"], deprecated=True)


class LegacyInfoRequest(BaseModel):
    url: str


class LegacyDownloadRequest(BaseModel):
    url: str
    format_id: str = Field(
        default="bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    )
    audio_only: bool = False


@router.get("/health", summary="Deprecated: use /api/health")
def legacy_health() -> dict:
    return {"status": "ok"}


@router.post("/info", summary="Deprecated: use GET /api/info")
async def legacy_info(payload: LegacyInfoRequest, settings: SettingsDep):
    try:
        info = await media_service.get_video_info(settings, payload.url)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=media_service.clean_error(exc)) from exc
    # v1 clients read a flat payload; InfoResponse is a superset.
    return info.model_dump(exclude={"sponsorblock", "cookies_used"})


@router.post("/download", summary="Deprecated: use POST /api/download")
async def legacy_download(payload: LegacyDownloadRequest, settings: SettingsDep):
    cookie_path = media_service.cookies_for(settings)
    options = DownloadOptions(
        url=payload.url,
        format_id=payload.format_id,
        audio_only=payload.audio_only,
        output_dir=str(settings.download_dir),
        cookies_file=str(cookie_path) if cookie_path else None,
    )
    try:
        result = ProDLDownloader.download_sync(options)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=media_service.clean_error(exc)) from exc

    return FileResponse(
        path=result.filepath,
        filename=result.filename,
        media_type=media_service.guess_media_type(result.filename),
    )
