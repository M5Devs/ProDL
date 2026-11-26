"""Cookie management endpoints (local, owner-only storage)."""

from __future__ import annotations

from fastapi import APIRouter, File, HTTPException, UploadFile

from prodl_core import CookieFormatError, clear_cookies, cookie_status, save_cookies

from ..deps import SettingsDep
from ..schemas import MAX_COOKIE_BYTES, CookiesRequest, CookiesStatusResponse

router = APIRouter(tags=["cookies"])


def _response(settings, **overrides) -> CookiesStatusResponse:
    status = cookie_status(settings.cookies_file, **overrides)
    return CookiesStatusResponse(
        configured=status.configured,
        cookie_count=status.cookie_count,
        updated_at=status.updated_at,
    )


@router.get("/cookies", response_model=CookiesStatusResponse, summary="Cookie status")
async def get_cookies(settings: SettingsDep) -> CookiesStatusResponse:
    return _response(settings)


@router.post("/cookies", response_model=CookiesStatusResponse, summary="Store cookies")
async def store_cookies(payload: CookiesRequest, settings: SettingsDep) -> CookiesStatusResponse:
    try:
        saved = save_cookies(payload.content, settings.cookies_file)
    except CookieFormatError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except OSError as exc:
        raise HTTPException(
            status_code=500, detail="Could not write the cookie store"
        ) from exc
    return _response(settings, cookie_count=saved.cookie_count)


@router.post(
    "/cookies/upload",
    response_model=CookiesStatusResponse,
    summary="Upload a Netscape cookie file",
)
async def upload_cookies(
    settings: SettingsDep, file: UploadFile = File(...)
) -> CookiesStatusResponse:
    raw = await file.read(MAX_COOKIE_BYTES + 1)
    await file.close()
    if len(raw) > MAX_COOKIE_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"Cookie file exceeds {MAX_COOKIE_BYTES // (1024 * 1024)} MiB",
        )
    try:
        text = raw.decode("utf-8", errors="replace")
        saved = save_cookies(text, settings.cookies_file)
    except CookieFormatError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return _response(settings, cookie_count=saved.cookie_count)


@router.delete("/cookies", response_model=CookiesStatusResponse, summary="Delete cookies")
async def delete_cookies(settings: SettingsDep) -> CookiesStatusResponse:
    clear_cookies(settings.cookies_file)
    return _response(settings)
