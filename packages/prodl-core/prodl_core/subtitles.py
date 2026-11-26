import asyncio
from functools import partial
from pathlib import Path

import yt_dlp

from .models import SubtitleInfo
from .ytdlp_opts import base_opts


def _collect(info: dict) -> list[SubtitleInfo]:
    subtitles: list[SubtitleInfo] = []
    for key, is_auto in (("subtitles", False), ("automatic_captions", True)):
        for lang, entries in (info.get(key) or {}).items():
            lang_name = lang
            ext = None
            if isinstance(entries, list) and entries:
                lang_name = entries[0].get("name") or lang
                ext = entries[0].get("ext")
            subtitles.append(
                SubtitleInfo(
                    lang=str(lang), lang_name=str(lang_name), is_auto=is_auto, ext=ext
                )
            )
    return subtitles


def list_subtitles_sync(
    url: str, *, cookies_file: str | Path | None = None
) -> list[SubtitleInfo]:
    opts = base_opts()
    if cookies_file:
        opts["cookiefile"] = str(cookies_file)

    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False)
    if not info:
        return []
    return _collect(info)


async def list_subtitles(
    url: str, *, cookies_file: str | Path | None = None
) -> list[SubtitleInfo]:
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, partial(list_subtitles_sync, url, cookies_file=cookies_file)
    )
