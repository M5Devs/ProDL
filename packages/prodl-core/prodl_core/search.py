"""Key-less video search built on yt-dlp's ``ytsearch`` pseudo-extractor.

No YouTube Data API key is required: yt-dlp resolves the query through the
same public web endpoints it uses for downloads, so search keeps working for
every site yt-dlp supports (``ytsearch`` targets YouTube, other extractors can
be addressed by prefixing the query, e.g. ``ytsearch5:`` vs a direct URL).
"""

from __future__ import annotations

import asyncio
import os
from functools import partial
from typing import Any

import yt_dlp

from .models import SearchResult
from .ytdlp_opts import base_opts

DEFAULT_SEARCH_PREFIX = "ytsearch"
MAX_LIMIT = 50


def _thumbnail(entry: dict) -> str | None:
    thumbnails = entry.get("thumbnails") or []
    if thumbnails:
        return thumbnails[-1].get("url")
    return entry.get("thumbnail")


def _entry_url(entry: dict) -> str | None:
    for key in ("webpage_url", "original_url", "url"):
        value = entry.get(key)
        if value and str(value).startswith("http"):
            return str(value)
    media_id = entry.get("id")
    if media_id and (entry.get("ie_key") or entry.get("extractor_key")) in (
        None,
        "Youtube",
        "youtube",
    ):
        return f"https://www.youtube.com/watch?v={media_id}"
    return None


def search_videos_sync(
    query: str,
    limit: int = 10,
    *,
    cookies_file: str | os.PathLike[str] | None = None,
    prefix: str = DEFAULT_SEARCH_PREFIX,
    timeout: int = 25,
) -> list[SearchResult]:
    query = (query or "").strip()
    if not query:
        return []
    limit = max(1, min(int(limit or 10), MAX_LIMIT))

    opts: dict[str, Any] = base_opts(
        extract_flat="in_playlist",
        skip_download=True,
        ignoreerrors=True,
        socket_timeout=timeout,
    )
    if cookies_file:
        opts["cookiefile"] = str(cookies_file)

    # A pasted URL resolves directly; anything else becomes a keyless search.
    is_url = query.lower().startswith(("http://", "https://"))
    target = query if is_url else f"{prefix}{limit}:{query}"

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(target, download=False)

    if not info:
        return []

    entries = info.get("entries") if info.get("_type") == "playlist" else [info]
    results: list[SearchResult] = []
    for entry in entries or []:
        if not entry:
            continue
        url = _entry_url(entry)
        if not url:
            continue
        results.append(
            SearchResult(
                id=str(entry.get("id") or ""),
                title=str(entry.get("title") or "Untitled"),
                url=url,
                thumbnail=_thumbnail(entry),
                duration=entry.get("duration"),
                uploader=entry.get("uploader") or entry.get("channel"),
                view_count=entry.get("view_count"),
                is_live=bool(entry.get("is_live")),
                extractor=entry.get("ie_key") or entry.get("extractor_key"),
            )
        )
    return results[:limit]


async def search_videos(
    query: str,
    limit: int = 10,
    *,
    cookies_file: str | os.PathLike[str] | None = None,
) -> list[SearchResult]:
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, partial(search_videos_sync, query, limit, cookies_file=cookies_file)
    )
