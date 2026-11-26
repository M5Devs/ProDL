"""Request/response schemas for the public JSON API (see API_SPEC.md)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field, field_validator, model_validator

from prodl_core.converter import SUPPORTED_FORMATS
from prodl_core.models import (
    ConvertResult,
    FileRef,
    ProgressEvent,
    SearchResult,
    TaskStatus,
    VideoInfo,
)

AudioFormat = Literal["mp3", "m4a", "opus", "flac", "wav"]

DEFAULT_FORMAT = "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best"


class ErrorResponse(BaseModel):
    detail: str
    code: str | None = None


class ComponentStatus(BaseModel):
    available: bool
    version: str | None = None
    path: str | None = None
    note: str | None = None


class CookiesStatusResponse(BaseModel):
    configured: bool
    cookie_count: int
    updated_at: str | None = None
    storage: str = "local-file"


class HealthResponse(BaseModel):
    status: Literal["ok", "degraded"]
    api_version: str
    version: str
    uptime_seconds: float
    platform: str
    python: str
    data_dir: str
    download_dir: str
    download_dir_writable: bool
    yt_dlp: ComponentStatus
    ffmpeg: ComponentStatus
    ffprobe: ComponentStatus
    js_runtime: ComponentStatus
    cookies: CookiesStatusResponse
    features: dict[str, bool] = Field(default_factory=dict)


class SearchResponse(BaseModel):
    query: str
    limit: int
    count: int
    results: list[SearchResult]


class SponsorSegment(BaseModel):
    category: str
    start: float
    end: float
    action: str = "skip"
    uuid: str | None = None


class SponsorBlockInfo(BaseModel):
    requested: bool = False
    video_id: str | None = None
    categories: list[str] = Field(default_factory=list)
    segments: list[SponsorSegment] = Field(default_factory=list)
    error: str | None = None


class InfoResponse(VideoInfo):
    sponsorblock: SponsorBlockInfo | None = None
    cookies_used: bool = False


class DownloadRequest(BaseModel):
    url: str = Field(min_length=4)
    format_id: str | None = None
    video_quality: str | None = Field(
        default=None, description="Target height such as '1080p' or '720'"
    )
    audio_only: bool = False
    audio_format: AudioFormat = "mp3"
    audio_quality: str = "192"

    subtitles: bool = False
    subtitle_langs: list[str] = Field(default_factory=lambda: ["en"])
    embed_subtitles: bool = False
    auto_subtitles: bool = True

    thumbnail: bool = False
    embed_thumbnail: bool = True

    sponsorblock: bool = False
    sponsorblock_action: Literal["cut", "mark", "skip"] = "cut"
    sponsorblock_categories: list[str] = Field(
        default_factory=lambda: ["sponsor", "intro", "outro"]
    )

    use_cookies: bool = True
    max_filesize: str | None = None
    concurrent_fragments: int = Field(default=1, ge=1, le=16)

    @field_validator("url")
    @classmethod
    def _clean_url(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("url must not be empty")
        return value

    @field_validator("video_quality")
    @classmethod
    def _clean_quality(cls, value: str | None) -> str | None:
        if value is None:
            return None
        cleaned = value.strip().lower().rstrip("p")
        if not cleaned.isdigit():
            raise ValueError("video_quality must look like '1080p'")
        return cleaned

    @property
    def format_selector(self) -> str:
        if self.audio_only:
            return "bestaudio/best"
        if self.format_id:
            return self.format_id
        if self.video_quality:
            height = self.video_quality
            return (
                f"bestvideo[height<={height}]+bestaudio/"
                f"best[height<={height}]/best"
            )
        return DEFAULT_FORMAT


class ConvertRequest(BaseModel):
    target_format: str = "mp3"
    audio_bitrate: str = "192k"
    video_crf: int = Field(default=23, ge=0, le=51)
    start: float | None = Field(default=None, ge=0)
    end: float | None = Field(default=None, gt=0)
    task_id: str | None = Field(
        default=None, description="Convert an artefact produced by /api/download"
    )
    path: str | None = Field(
        default=None, description="Filename inside the server download directory"
    )

    @field_validator("target_format")
    @classmethod
    def _check_format(cls, value: str) -> str:
        cleaned = value.strip().lower().lstrip(".")
        if cleaned not in SUPPORTED_FORMATS:
            raise ValueError(
                f"unsupported target_format; use one of {', '.join(SUPPORTED_FORMATS)}"
            )
        return cleaned

    @model_validator(mode="after")
    def _check_source(self) -> "ConvertRequest":
        if self.task_id and self.path:
            raise ValueError("provide either task_id or path, not both")
        if self.start is not None and self.end is not None and self.end <= self.start:
            raise ValueError("end must be greater than start")
        return self


MAX_COOKIE_BYTES = 2 * 1024 * 1024


class CookiesRequest(BaseModel):
    content: str = Field(
        min_length=1,
        max_length=MAX_COOKIE_BYTES,
        description="Netscape-format cookie file text",
    )


class TaskAccepted(BaseModel):
    task_id: str
    kind: Literal["download", "convert"]
    status: TaskStatus
    created_at: float
    websocket_url: str
    sse_url: str
    task_url: str
    file_url: str | None = None


class TaskSnapshot(BaseModel):
    task_id: str
    kind: Literal["download", "convert"]
    status: TaskStatus
    created_at: float
    updated_at: float
    error: str | None = None
    file: FileRef | None = None
    last_event: ProgressEvent | None = None


class ConvertResponse(BaseModel):
    task_id: str
    status: TaskStatus
    result: ConvertResult | None = None


class ApiIndex(BaseModel):
    name: str
    version: str
    api_version: str
    docs: str
    openapi: str
    endpoints: dict[str, str]
