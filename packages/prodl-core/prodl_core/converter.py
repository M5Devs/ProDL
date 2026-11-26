"""FFmpeg-based conversion / trimming.

Runs ffmpeg as an argument vector (never through a shell) so filenames with
spaces or non-ASCII characters behave identically on Linux, macOS, Windows and
Android local-server shells.
"""

from __future__ import annotations

import asyncio
import time
from functools import partial
from pathlib import Path
from typing import Callable, Iterable

from . import config
from .ffmpeg import FFmpegNotFound, probe_duration, require_ffmpeg
from .models import ConvertResult, ProgressEvent
from .progress import ffmpeg_progress_event

AUDIO_FORMATS = ("mp3", "m4a", "aac", "opus", "flac", "wav", "ogg")
VIDEO_FORMATS = ("mp4", "webm", "mkv", "gif")
SUPPORTED_FORMATS = AUDIO_FORMATS + VIDEO_FORMATS


class ConversionError(RuntimeError):
    pass


def _audio_codec_args(target: str, bitrate: str) -> list[str]:
    if target == "mp3":
        return ["-vn", "-c:a", "libmp3lame", "-b:a", bitrate]
    if target in ("m4a", "aac"):
        return ["-vn", "-c:a", "aac", "-b:a", bitrate]
    if target == "opus":
        return ["-vn", "-c:a", "libopus", "-b:a", bitrate]
    if target == "flac":
        return ["-vn", "-c:a", "flac"]
    if target == "wav":
        return ["-vn", "-c:a", "pcm_s16le"]
    if target == "ogg":
        return ["-vn", "-c:a", "libvorbis", "-q:a", "5"]
    raise ConversionError(f"Unsupported audio format: {target}")


def _video_codec_args(target: str, crf: int) -> list[str]:
    if target == "gif":
        return ["-vf", "fps=12,scale=480:-1:flags=lanczos", "-loop", "0"]
    if target == "webm":
        return ["-c:v", "libvpx-vp9", "-crf", str(crf), "-b:v", "0", "-c:a", "libopus"]
    if target == "mkv":
        return ["-c:v", "libx264", "-crf", str(crf), "-c:a", "aac"]
    return ["-c:v", "libx264", "-crf", str(crf), "-c:a", "aac", "-movflags", "+faststart"]


def build_ffmpeg_args(
    source: Path,
    destination: Path,
    target: str,
    *,
    audio_bitrate: str = "192k",
    video_crf: int = 23,
    start: float | None = None,
    end: float | None = None,
    ffmpeg: str | None = None,
) -> list[str]:
    target = target.lower().lstrip(".")
    if target not in SUPPORTED_FORMATS:
        raise ConversionError(
            f"Unsupported target format '{target}'. "
            f"Supported: {', '.join(SUPPORTED_FORMATS)}"
        )
    if start is not None and start < 0:
        raise ConversionError("start must be >= 0")
    if start is not None and end is not None and end <= start:
        raise ConversionError("end must be greater than start")

    args: list[str] = [
        ffmpeg or require_ffmpeg(),
        "-hide_banner",
        "-nostdin",
        "-y",
        "-loglevel",
        "error",
        "-progress",
        "pipe:1",
        "-nostats",
    ]
    # Input seeking keeps trimming fast even for long files.
    if start is not None:
        args += ["-ss", f"{start:.3f}"]
    if end is not None:
        args += ["-to", f"{end:.3f}"]
    args += ["-i", str(source)]

    if target in AUDIO_FORMATS:
        args += _audio_codec_args(target, audio_bitrate)
    else:
        args += _video_codec_args(target, video_crf)

    args.append(str(destination))
    return args


def _unique_destination(directory: Path, stem: str, suffix: str) -> Path:
    candidate = directory / f"{stem}.{suffix}"
    index = 1
    while candidate.exists():
        candidate = directory / f"{stem} ({index}).{suffix}"
        index += 1
    return candidate


def convert_media_sync(
    source: str | Path,
    target_format: str,
    *,
    audio_bitrate: str = "192k",
    video_crf: int = 23,
    start: float | None = None,
    end: float | None = None,
    output_dir: str | Path | None = None,
    on_event: Callable[[ProgressEvent], None] | None = None,
    task_id: str | None = None,
) -> ConvertResult:
    source_path = Path(source).expanduser().resolve()
    if not source_path.is_file():
        raise ConversionError(f"Source file not found: {source_path}")

    target = target_format.lower().lstrip(".")
    destination_dir = Path(output_dir) if output_dir else config.downloads_dir()
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = _unique_destination(destination_dir, source_path.stem, target)

    total_seconds = probe_duration(source_path)
    if total_seconds and start is not None:
        total_seconds = max(0.0, total_seconds - start)
    if total_seconds and end is not None:
        total_seconds = min(total_seconds, max(0.0, end - (start or 0.0)))

    args = build_ffmpeg_args(
        source_path,
        destination,
        target,
        audio_bitrate=audio_bitrate,
        video_crf=video_crf,
        start=start,
        end=end,
    )

    started = time.time()
    try:
        process = _run_with_progress(args, total_seconds, on_event, task_id)
    except FileNotFoundError as exc:  # pragma: no cover - defensive
        raise FFmpegNotFound(str(exc)) from exc

    if not destination.exists() or destination.stat().st_size == 0:
        raise ConversionError("ffmpeg produced no output")

    return ConvertResult(
        output_path=str(destination),
        filename=destination.name,
        format=target,
        filesize=destination.stat().st_size,
        duration=total_seconds,
        elapsed=round(time.time() - started, 2),
    )


def _run_with_progress(
    args: Iterable[str],
    total_seconds: float | None,
    on_event: Callable[[ProgressEvent], None] | None,
    task_id: str | None,
):
    import subprocess
    import tempfile

    # stderr goes to a temp file: reading two pipes while ffmpeg runs can
    # deadlock once the unread pipe buffer fills.
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace") as errors:
        process = subprocess.Popen(
            list(args),
            stdout=subprocess.PIPE,
            stderr=errors,
            text=True,
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            line = line.strip()
            if not line:
                continue
            if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
                raw = line.split("=", 1)[1]
                if raw.isdigit():
                    seconds = int(raw) / 1_000_000
                    if on_event and total_seconds:
                        on_event(
                            ffmpeg_progress_event(seconds, total_seconds, task_id=task_id)
                        )
            elif line == "progress=end" and on_event:
                on_event(
                    ffmpeg_progress_event(
                        total_seconds or 0.0,
                        total_seconds,
                        task_id=task_id,
                        message="Finishing…",
                    )
                )

        process.wait()
        if process.returncode != 0:
            errors.seek(0)
            detail = "\n".join(errors.read().strip().splitlines()[-8:])
            raise ConversionError(detail or "ffmpeg failed")
    return process


async def convert_media(source: str | Path, target_format: str, **kwargs) -> ConvertResult:
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(
        None, partial(convert_media_sync, source, target_format, **kwargs)
    )
