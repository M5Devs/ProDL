from __future__ import annotations

import asyncio
import os
import time
from functools import partial
from pathlib import Path
from typing import Any

import yt_dlp

from . import config
from .ytdlp_opts import base_opts as _base_opts
from .models import (
    DownloadOptions,
    DownloadResult,
    StreamInfo,
    SubtitleInfo,
    VideoFormat,
    VideoInfo,
)


def _pick_entry(info: dict, url: str) -> dict:
    """Collapse a playlist response to its first playable entry."""
    if info.get("_type") in ("playlist", "multi_video") and info.get("entries"):
        entries = [entry for entry in info["entries"] if entry]
        if not entries:
            raise ValueError(f"Playlist has no playable entries: {url}")
        return entries[0]
    return info


def _has(stream_codec: Any) -> bool:
    return bool(stream_codec) and stream_codec != "none"


VIDEO_EXTS = {"mp4", "mkv", "webm", "mov", "avi", "flv", "m4v", "3gp", "ts", "m3u8", "mpd"}
AUDIO_EXTS = {"mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "weba", "wma"}


def _format_from_top_level(
    info: dict, *, include_direct_urls: bool = True
) -> VideoFormat | None:
    """Synthesise a format for single-file responses (generic direct links)."""
    url = info.get("url")
    if not url:
        return None

    ext = str(info.get("ext") or "").lower()
    has_video = _has(info.get("vcodec")) or ext in VIDEO_EXTS
    has_audio = _has(info.get("acodec")) or ext in AUDIO_EXTS

    resolution = info.get("resolution")
    if not resolution:
        width, height = info.get("width"), info.get("height")
        resolution = f"{width}x{height}" if width and height else (
            "video" if has_video else "audio only"
        )

    return VideoFormat(
        format_id=str(info.get("format_id") or "best"),
        ext=ext,
        resolution=str(resolution),
        filesize=info.get("filesize") or info.get("filesize_approx"),
        note=str(info.get("format_note") or ""),
        has_video=has_video,
        has_audio=has_audio,
        vcodec=info.get("vcodec"),
        acodec=info.get("acodec"),
        fps=info.get("fps"),
        tbr=info.get("tbr"),
        abr=info.get("abr"),
        protocol=info.get("protocol"),
        direct_url=str(url) if include_direct_urls else None,
    )


def _build_formats(
    info: dict, *, include_direct_urls: bool = True
) -> list[VideoFormat]:
    formats: list[VideoFormat] = []
    for fmt in info.get("formats") or []:
        has_video = _has(fmt.get("vcodec"))
        has_audio = _has(fmt.get("acodec"))
        if not (has_video or has_audio):
            continue

        resolution = fmt.get("resolution")
        if not resolution:
            width, height = fmt.get("width"), fmt.get("height")
            if width and height:
                resolution = f"{width}x{height}"
            elif has_video:
                resolution = "video only"
            else:
                resolution = "audio only"

        filesize = fmt.get("filesize") or fmt.get("filesize_approx")
        formats.append(
            VideoFormat(
                format_id=str(fmt.get("format_id", "")),
                ext=str(fmt.get("ext", "")),
                resolution=str(resolution),
                filesize=filesize,
                note=str(fmt.get("format_note") or fmt.get("note") or ""),
                has_video=has_video,
                has_audio=has_audio,
                vcodec=fmt.get("vcodec"),
                acodec=fmt.get("acodec"),
                fps=fmt.get("fps"),
                tbr=fmt.get("tbr"),
                abr=fmt.get("abr"),
                protocol=fmt.get("protocol"),
                direct_url=fmt.get("url") if include_direct_urls else None,
            )
        )
    return formats


def _build_subtitles(info: dict) -> list[SubtitleInfo]:
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


def _pick_stream_urls(formats: list[VideoFormat]) -> tuple[str | None, str | None]:
    """Best direct URL for playback: prefer progressive, then video-only."""
    best_video = None
    best_video_only = None
    best_audio = None
    for fmt in formats:
        if not fmt.direct_url:
            continue
        if fmt.has_video and fmt.has_audio:
            best_video = fmt.direct_url
        elif fmt.has_video:
            best_video_only = fmt.direct_url
        elif fmt.has_audio:
            best_audio = fmt.direct_url
    return (best_video or best_video_only), best_audio


class ProDLDownloader:
    # ── info ────────────────────────────────────────────────────────────────
    @staticmethod
    def get_info_sync(
        url: str,
        *,
        cookies_file: str | os.PathLike[str] | None = None,
        include_direct_urls: bool = True,
    ) -> VideoInfo:
        opts = _base_opts()
        if cookies_file:
            opts["cookiefile"] = str(cookies_file)

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
        if info is None:
            raise ValueError(f"Could not extract info for URL: {url}")
        info = _pick_entry(info, url)

        formats = _build_formats(info, include_direct_urls=include_direct_urls)
        if not formats:
            single = _format_from_top_level(
                info, include_direct_urls=include_direct_urls
            )
            formats = [single] if single else []
        best_video_url, best_audio_url = _pick_stream_urls(formats)
        description = info.get("description")
        if description and len(description) > 2000:
            description = description[:2000] + "…"

        return VideoInfo(
            title=str(info.get("title") or ""),
            duration=info.get("duration"),
            thumbnail=info.get("thumbnail"),
            uploader=info.get("uploader"),
            view_count=info.get("view_count"),
            webpage_url=str(info.get("webpage_url") or url),
            extractor=str(info.get("extractor") or ""),
            formats=formats,
            subtitles=_build_subtitles(info),
            id=str(info.get("id")) if info.get("id") else None,
            channel=info.get("channel"),
            like_count=info.get("like_count"),
            upload_date=info.get("upload_date"),
            description=description,
            is_live=bool(info.get("is_live")),
            age_limit=info.get("age_limit"),
            best_video_url=best_video_url,
            best_audio_url=best_audio_url,
        )

    @staticmethod
    async def get_info(
        url: str,
        *,
        cookies_file: str | os.PathLike[str] | None = None,
        include_direct_urls: bool = True,
    ) -> VideoInfo:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None,
            partial(
                ProDLDownloader.get_info_sync,
                url,
                cookies_file=cookies_file,
                include_direct_urls=include_direct_urls,
            ),
        )

    # ── direct stream ───────────────────────────────────────────────────────
    @staticmethod
    def get_stream_info_sync(opts: DownloadOptions) -> StreamInfo:
        ydl_opts = _base_opts(format=opts.format_id, skip_download=True)
        if opts.cookies_file:
            ydl_opts["cookiefile"] = str(opts.cookies_file)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(opts.url, download=False)
        if info is None:
            raise ValueError(f"Could not extract stream info for URL: {opts.url}")
        info = _pick_entry(info, opts.url)

        requested = info.get("requested_downloads")
        if requested:
            direct_url = requested[0]["url"]
        else:
            direct_url = info["url"]

        return StreamInfo(
            url=str(direct_url),
            title=str(info.get("title") or ""),
            ext=str(info.get("ext") or ""),
            filesize=info.get("filesize") or info.get("filesize_approx"),
            format_id=info.get("format_id"),
            expires_hint="CDN URLs are short-lived and bound to the requesting IP",
        )

    @staticmethod
    async def get_stream_info(opts: DownloadOptions) -> StreamInfo:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None, partial(ProDLDownloader.get_stream_info_sync, opts)
        )

    # ── download ────────────────────────────────────────────────────────────
    @staticmethod
    def build_ydl_opts(opts: DownloadOptions) -> dict[str, Any]:
        output_dir = Path(opts.output_dir) if opts.output_dir else config.downloads_dir()
        output_dir.mkdir(parents=True, exist_ok=True)

        ydl_opts: dict[str, Any] = _base_opts(
            outtmpl=str(output_dir / opts.output_template),
            merge_output_format="mp4",
            noplaylist=opts.noplaylist,
            retries=opts.retries,
            concurrent_fragment_downloads=opts.concurrent_fragments,
            socket_timeout=opts.socket_timeout,
            continuedl=True,
            overwrites=False,
        )

        if opts.audio_only:
            ydl_opts["format"] = "bestaudio/best"
            ydl_opts["postprocessors"] = [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": opts.audio_format,
                    "preferredquality": opts.audio_quality,
                }
            ]
        else:
            ydl_opts["format"] = opts.format_id

        if opts.subtitles:
            ydl_opts["writesubtitles"] = True
            ydl_opts["writeautomaticsub"] = opts.auto_subs
            ydl_opts["subtitleslangs"] = opts.sub_langs
            ydl_opts["embedsubtitles"] = opts.sub_embed

        if opts.thumbnail:
            ydl_opts["writethumbnail"] = True
            ydl_opts["embedthumbnail"] = opts.thumb_embed

        if opts.sponsorblock:
            categories = set(opts.sb_categories)
            if opts.sb_action == "cut":
                ydl_opts["sponsorblock_remove"] = categories
            else:  # 'mark' and client-side 'skip' both annotate chapters
                ydl_opts["sponsorblock_mark"] = categories

        if opts.cookies_file:
            ydl_opts["cookiefile"] = str(opts.cookies_file)

        if opts.progress_hook:
            ydl_opts["progress_hooks"] = [opts.progress_hook]
        if opts.postprocessor_hook:
            ydl_opts["postprocessor_hooks"] = [opts.postprocessor_hook]

        if opts.max_filesize:
            ydl_opts["max_filesize"] = opts.max_filesize

        return ydl_opts

    @staticmethod
    def download_sync(opts: DownloadOptions) -> DownloadResult:
        ydl_opts = ProDLDownloader.build_ydl_opts(opts)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(opts.url, download=True)
            if info is None:
                raise ValueError(f"Download failed for URL: {opts.url}")
            info = _pick_entry(info, opts.url)

            requested = info.get("requested_downloads") or []
            if requested and requested[0].get("filepath"):
                filepath = requested[0]["filepath"]
            else:
                filepath = ydl.prepare_filename(info)

        path = Path(filepath)
        if not path.exists() and opts.audio_only:
            # FFmpegExtractAudio swaps the container, so look for the target ext.
            candidate = path.with_suffix(f".{opts.audio_format}")
            if candidate.exists():
                path = candidate

        return DownloadResult(
            filepath=str(path),
            filename=path.name,
            title=str(info.get("title") or ""),
            ext=path.suffix.lstrip("."),
            filesize=path.stat().st_size if path.exists() else (
                info.get("filesize") or info.get("filesize_approx")
            ),
            duration=info.get("duration"),
            media_id=str(info.get("id")) if info.get("id") else None,
        )

    @staticmethod
    async def download(opts: DownloadOptions) -> DownloadResult:
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            None, partial(ProDLDownloader.download_sync, opts)
        )
