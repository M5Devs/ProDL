"""GET /api/search — key-less video search."""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from ..deps import SettingsDep
from ..schemas import SearchResponse
from ..services import media as media_service

router = APIRouter(tags=["search"])


@router.get("/search", response_model=SearchResponse, summary="Search videos")
async def search(
    settings: SettingsDep,
    q: str = Query(..., min_length=1, max_length=256, description="Search query"),
    limit: int | None = Query(default=None, ge=1, le=50),
) -> SearchResponse:
    query = q.strip()
    if not query:
        raise HTTPException(status_code=422, detail="q must not be blank")

    effective_limit = min(limit or settings.search_limit, settings.search_limit_max)
    try:
        results = await media_service.search(settings, query, effective_limit)
    except Exception as exc:  # noqa: BLE001 - upstream extractor failure
        raise HTTPException(
            status_code=502, detail=media_service.clean_error(exc)
        ) from exc

    return SearchResponse(
        query=query,
        limit=effective_limit,
        count=len(results),
        results=results,
    )
