from __future__ import annotations

from typing import Callable, Literal

from pydantic import BaseModel, ConfigDict, Field

TaskStatus = Literal[
    "queued",
    "downloading",
    "postprocessing",
    "converting",
    "finished",
    "error",
    "canceled",
]

TERMINAL_STATUSES: frozenset[str] = frozenset({"finished", "error", "canceled"})


class VideoFormat(BaseModel):
    format_id: str
    ext: str
    resolution: str
    filesize: int | None = None
    note: str = ""
    has_video: bool
    has_audio: bool
    vcodec: str | None = None
    acodec: str | None = None
    fps: float | None = None
    tbr: float | None = None
    abr: float | None = None
    protocol: str | None = None
    direct_url: str | None = None


class SubtitleInfo(BaseModel):
    lang: str
    lang_name: str
    is_auto: bool  # YouTube auto-generated
    ext: str | None = None


class VideoInfo(BaseModel):
    title: str
    duration: int | None = None
    thumbnail: str | None = None
    uploader: str | None = None
    view_count: int | None = None
    webpage_url: str
    extractor: str
    formats: list[VideoFormat] = Field(default_factory=list)
    subtitles: list[SubtitleInfo] = Field(default_factory=list)

    id: str | None = None
    channel: str | None = None
    like_count: int | None = None
    upload_date: str | None = None
    description: str | None = None
    is_live: bool = False
    age_limit: int | None = None
    # Direct CDN links enable ad-free in-app playback in a WebView shell.
    best_video_url: str | None = None
    best_audio_url: str | None = None
    # Related videos, when the extractor returns them with the watch page.
    related: list["SearchResult"] = Field(default_factory=list)


class SearchResult(BaseModel):
    id: str
    title: str
    url: str
    thumbnail: str | None = None
    duration: int | None = None
    uploader: str | None = None
    view_count: int | None = None
    is_live: bool = False
    extractor: str | None = None
    # "video", "channel" or "playlist": the UI renders each differently.
    kind: str = "video"
    # Subscriber count for channels, video count for playlists.
    item_count: int | None = None


class DownloadOptions(BaseModel):
    """
    Options for downloading media.

    progress_hook signature called by yt-dlp:
        def hook(d: dict):
            d['status']        # 'downloading' | 'finished' | 'error'
            d['_percent_str']  # '45.2%'
            d['_speed_str']    # '1.23MiB/s'
            d['eta']           # seconds remaining (int)
    """

    url: str
    format_id: str = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"
    audio_only: bool = False
    audio_format: Literal["mp3", "m4a", "opus", "flac", "wav"] = "mp3"
    audio_quality: str = "192"

    # Subtitles
    subtitles: bool = False
    sub_langs: list[str] = ["en", "ar"]
    sub_embed: bool = False
    auto_subs: bool = True

    # Thumbnail
    thumbnail: bool = False
    thumb_embed: bool = True  # embed in MP3 as cover art

    # SponsorBlock
    sponsorblock: bool = False
    sb_action: Literal["skip", "cut", "mark"] = "cut"
    sb_categories: list[str] = ["sponsor", "intro", "outro"]

    # Output
    output_dir: str | None = None
    output_template: str = "%(title).120B [%(id)s].%(ext)s"
    noplaylist: bool = True

    # Network / auth
    cookies_file: str | None = None
    retries: int = 3
    concurrent_fragments: int = 1
    socket_timeout: int = 30

    progress_hook: Callable | None = None
    postprocessor_hook: Callable | None = None
    max_filesize: str | None = None

    model_config = ConfigDict(arbitrary_types_allowed=True)


class DownloadResult(BaseModel):
    filepath: str
    filename: str
    title: str
    ext: str
    filesize: int | None = None
    duration: float | None = None
    media_id: str | None = None


class StreamInfo(BaseModel):
    url: str  # direct stream URL from yt-dlp
    title: str
    ext: str
    filesize: int | None = None
    format_id: str | None = None
    expires_hint: str | None = None


class ConvertResult(BaseModel):
    output_path: str
    filename: str
    format: str
    filesize: int | None = None
    duration: float | None = None
    elapsed: float | None = None


class FileRef(BaseModel):
    """A finished artefact the client can fetch over HTTP."""

    filename: str
    filesize: int | None = None
    download_url: str | None = None
    media_type: str | None = None


class ProgressEvent(BaseModel):
    task_id: str | None = None
    seq: int | None = None
    status: TaskStatus
    percent: float | None = None
    downloaded_bytes: int | None = None
    total_bytes: int | None = None
    speed: float | None = None
    speed_str: str | None = None
    eta: int | None = None
    elapsed: float | None = None
    filename: str | None = None
    title: str | None = None
    message: str | None = None
    error: str | None = None
    timestamp: float = 0.0
    file: FileRef | None = None
