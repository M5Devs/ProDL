"""SponsorBlock integration (https://sponsor.ajay.app).

Segments are used two ways:

* the frontend can skip them during ad-free playback, and
* yt-dlp can cut or chapter-mark them while downloading.
"""

from __future__ import annotations

import json
import os
import re
from typing import Any
from urllib.parse import parse_qs, urlparse

import httpx

DEFAULT_API = "https://sponsor.ajay.app/api/skipSegments"
DEFAULT_CATEGORIES = (
    "sponsor",
    "selfpromo",
    "interaction",
    "intro",
    "outro",
    "preview",
    "music_offtopic",
)
_VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")


def api_url() -> str:
    return os.environ.get("PRODL_SPONSORBLOCK_API") or DEFAULT_API


def extract_video_id(url: str) -> str | None:
    """Best-effort YouTube video id extraction (SponsorBlock only knows YouTube)."""
    if not url:
        return None
    if _VIDEO_ID_RE.match(url):
        return url

    parsed = urlparse(url)
    host = (parsed.hostname or "").lower()
    if host.endswith("youtu.be"):
        candidate = parsed.path.strip("/").split("/")[0]
        return candidate if _VIDEO_ID_RE.match(candidate) else None
    if "youtube" in host:
        if parsed.path in ("/watch", "/watch/"):
            candidate = parse_qs(parsed.query).get("v", [""])[0]
            return candidate if _VIDEO_ID_RE.match(candidate) else None
        match = re.match(r"^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})", parsed.path)
        if match:
            return match.group(1)
    return None


def normalize_segments(raw: Any) -> list[dict]:
    """Map the SponsorBlock payload to the public segment schema."""
    segments: list[dict] = []
    if not isinstance(raw, list):
        return segments
    for item in raw:
        if not isinstance(item, dict):
            continue
        span = item.get("segment") or []
        if len(span) != 2:
            continue
        try:
            start, end = float(span[0]), float(span[1])
        except (TypeError, ValueError):
            continue
        segments.append(
            {
                "category": str(item.get("category") or "sponsor"),
                "start": round(start, 3),
                "end": round(end, 3),
                "action": str(item.get("actionType") or "skip"),
                "uuid": item.get("UUID"),
            }
        )
    segments.sort(key=lambda segment: segment["start"])
    return segments


async def get_segments(
    video_id: str,
    categories: list[str] | None = None,
    *,
    api: str | None = None,
    timeout: float = 8.0,
) -> list[dict]:
    """Fetch skip segments for a video; an empty list means "no segments"."""
    if not video_id:
        return []
    params = {
        "videoID": video_id,
        "categories": json.dumps(list(categories or DEFAULT_CATEGORIES)),
    }
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.get(api or api_url(), params=params)
        if response.status_code == 404:
            return []
        response.raise_for_status()
        return response.json()
