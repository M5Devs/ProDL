"""GET /api/health — server, yt-dlp, ffmpeg and cookies readiness."""

from __future__ import annotations

import os
import platform
import sys
import time

from fastapi import APIRouter, Request

from prodl_core import cookie_status, ffmpeg_info, ffprobe_info, js_runtime_info
import yt_dlp

from .. import __version__
from ..deps import SettingsDep
from ..schemas import (
    ComponentStatus,
    CookiesStatusResponse,
    HealthResponse,
)

router = APIRouter(tags=["health"])

API_VERSION = "1.0.0"


@router.get("/health", response_model=HealthResponse, summary="Server status")
def health(request: Request, settings: SettingsDep) -> HealthResponse:
    ffmpeg = ffmpeg_info()
    ffprobe = ffprobe_info()
    runtime = js_runtime_info()
    cookies = cookie_status(settings.cookies_file)

    writable = False
    try:
        settings.download_dir.mkdir(parents=True, exist_ok=True)
        probe = settings.download_dir / ".write-probe"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        writable = True
    except OSError:
        writable = os.access(settings.download_dir, os.W_OK)

    yt_dlp_available = bool(yt_dlp.version.__version__)
    status = "ok" if (writable and yt_dlp_available) else "degraded"

    return HealthResponse(
        status=status,
        api_version=API_VERSION,
        version=__version__,
        uptime_seconds=round(time.time() - request.app.state.started_at, 3),
        platform=f"{platform.system()} {platform.release()}",
        python=sys.version.split()[0],
        data_dir=str(settings.data_dir),
        download_dir=str(settings.download_dir),
        download_dir_writable=writable,
        yt_dlp=ComponentStatus(
            available=yt_dlp_available,
            version=yt_dlp.version.__version__,
            note="pip install -U yt-dlp if extraction starts failing",
        ),
        ffmpeg=ComponentStatus(
            available=ffmpeg.available,
            version=ffmpeg.version,
            path=ffmpeg.path,
            note=None if ffmpeg.available else "Install ffmpeg or set PRODL_FFMPEG",
        ),
        ffprobe=ComponentStatus(
            available=ffprobe.available, version=ffprobe.version, path=ffprobe.path
        ),
        js_runtime=ComponentStatus(
            available=runtime.available,
            version=runtime.version,
            path=runtime.path,
            note=None
            if runtime.available
            else "A JS runtime (deno/node) helps yt-dlp solve YouTube challenges",
        ),
        cookies=CookiesStatusResponse(
            configured=cookies.configured,
            cookie_count=cookies.cookie_count,
            updated_at=cookies.updated_at,
        ),
        features={
            "search": True,
            "info": True,
            "download": True,
            "progress_websocket": True,
            "progress_sse": True,
            "convert": ffmpeg.available,
            "cookies": True,
            "sponsorblock": True,
            "subtitles": True,
            "thumbnail": True,
        },
    )
