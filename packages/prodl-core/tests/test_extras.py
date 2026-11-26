from __future__ import annotations

import stat

import pytest

from prodl_core import config
from prodl_core.converter import ConversionError, build_ffmpeg_args
from prodl_core.cookies import (
    CookieFormatError,
    clear,
    count_cookies,
    save_netscape,
    status,
    validate_netscape,
)
from prodl_core.progress import normalize_ytdlp_progress
from prodl_core.sponsorblock import extract_video_id, normalize_segments

COOKIES = (
    "# Netscape HTTP Cookie File\n"
    ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tabc\n"
    ".youtube.com\tTRUE\t/\tFALSE\t1893456000\tPREF\tdef\n"
)


# ── config ──────────────────────────────────────────────────────────────────
def test_data_dir_env_override(monkeypatch, tmp_path):
    monkeypatch.setenv("PRODL_DATA_DIR", str(tmp_path / "custom"))
    assert config.default_data_dir() == tmp_path / "custom"
    assert config.downloads_dir() == tmp_path / "custom" / "downloads"
    assert config.cookies_file() == tmp_path / "custom" / "cookies.txt"
    assert config.downloads_dir().is_dir()


def test_download_dir_env_override(monkeypatch, tmp_path):
    monkeypatch.setenv("PRODL_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("PRODL_DOWNLOAD_DIR", str(tmp_path / "elsewhere"))
    assert config.downloads_dir() == tmp_path / "elsewhere"


def test_writable_dir_falls_back_for_unusable_path(tmp_path):
    blocker = tmp_path / "file-not-dir"
    blocker.write_text("x")
    resolved = config.writable_dir(blocker / "child")
    assert resolved != blocker / "child"
    assert resolved.is_dir()


# ── cookies ─────────────────────────────────────────────────────────────────
def test_validate_netscape_counts_rows():
    assert validate_netscape(COOKIES) == 2
    assert count_cookies(COOKIES) == 2


def test_validate_netscape_reports_only_line_numbers():
    with pytest.raises(CookieFormatError) as excinfo:
        validate_netscape(".youtube.com\tTRUE\t/\tSID\n")
    message = str(excinfo.value)
    assert "row 1" in message
    assert "SID" not in message


def test_validate_netscape_rejects_empty():
    with pytest.raises(CookieFormatError):
        validate_netscape("   ")


def test_save_status_clear_round_trip(tmp_path):
    target = tmp_path / "cookies.txt"
    saved = save_netscape(COOKIES, target)
    assert saved.configured and saved.cookie_count == 2
    assert target.read_text().startswith("# Netscape HTTP Cookie File")
    if hasattr(stat, "S_IMODE"):
        assert stat.S_IMODE(target.stat().st_mode) == 0o600

    assert status(target).cookie_count == 2
    assert clear(target) is True
    assert clear(target) is False
    assert status(target).configured is False


def test_save_adds_header_when_missing(tmp_path):
    target = tmp_path / "cookies.txt"
    save_netscape(".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tabc\n", target)
    assert target.read_text().startswith("# Netscape HTTP Cookie File")


# ── sponsorblock ────────────────────────────────────────────────────────────
@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://www.youtube.com/watch?v=jNQXAC9IVRw", "jNQXAC9IVRw"),
        ("https://youtube.com/watch?v=jNQXAC9IVRw&t=10", "jNQXAC9IVRw"),
        ("https://youtu.be/jNQXAC9IVRw?t=5", "jNQXAC9IVRw"),
        ("https://www.youtube.com/shorts/jNQXAC9IVRw", "jNQXAC9IVRw"),
        ("https://www.youtube.com/live/jNQXAC9IVRw", "jNQXAC9IVRw"),
        ("https://vimeo.com/12345", None),
        ("jNQXAC9IVRw", "jNQXAC9IVRw"),
        ("", None),
    ],
)
def test_extract_video_id(url, expected):
    assert extract_video_id(url) == expected


def test_normalize_segments_sorts_and_filters():
    raw = [
        {"category": "intro", "segment": [0, 4.5], "actionType": "skip", "UUID": "b"},
        {"category": "sponsor", "segment": [30, 45], "actionType": "mute", "UUID": "a"},
        {"category": "broken", "segment": [1]},
        "not-a-dict",
    ]
    segments = normalize_segments(raw)
    assert [segment["category"] for segment in segments] == ["intro", "sponsor"]
    assert segments[1]["start"] == 30.0
    assert segments[1]["action"] == "mute"


def test_normalize_segments_handles_garbage():
    assert normalize_segments(None) == []
    assert normalize_segments({"segment": [1, 2]}) == []


# ── progress ────────────────────────────────────────────────────────────────
def test_normalize_progress_downloading():
    event = normalize_ytdlp_progress(
        {
            "status": "downloading",
            "downloaded_bytes": 512,
            "total_bytes": 1024,
            "speed": 2048.0,
            "_speed_str": " 2.00MiB/s",
            "eta": 12,
            "filename": "/tmp/a/b/video.f137.mp4",
            "info_dict": {"title": "A title"},
        },
        task_id="t1",
    )
    assert event.status == "downloading"
    assert event.percent == 50.0
    assert event.speed == 2048.0
    assert event.eta == 12
    assert event.filename == "video.f137.mp4"
    assert event.title == "A title"
    assert event.task_id == "t1"


def test_normalize_progress_uses_fragments_and_estimates():
    event = normalize_ytdlp_progress(
        {"status": "downloading", "fragment_index": 3, "fragment_count": 12}
    )
    assert event.percent == 25.0

    estimate = normalize_ytdlp_progress(
        {"status": "downloading", "downloaded_bytes": 5, "total_bytes_estimate": 10}
    )
    assert estimate.percent == 50.0


def test_normalize_progress_finished_and_error():
    finished = normalize_ytdlp_progress({"status": "finished", "filename": "x.mp4"})
    assert finished.status == "postprocessing"
    assert finished.percent == 100.0

    error = normalize_ytdlp_progress({"status": "error"})
    assert error.status == "error"


# ── converter ───────────────────────────────────────────────────────────────
def test_build_ffmpeg_args_audio():
    args = build_ffmpeg_args("in.mp4", "out.mp3", "mp3", audio_bitrate="320k", ffmpeg="ffmpeg")
    assert args[0] == "ffmpeg"
    assert "-vn" in args
    assert "libmp3lame" in args
    assert args[-1] == "out.mp3"
    assert "-progress" in args


def test_build_ffmpeg_args_video_and_gif():
    mp4 = build_ffmpeg_args("in.mkv", "out.mp4", "mp4", video_crf=28, ffmpeg="ffmpeg")
    assert "libx264" in mp4 and "28" in mp4

    gif = build_ffmpeg_args("in.mp4", "out.gif", "gif", ffmpeg="ffmpeg")
    assert "fps=12,scale=480:-1:flags=lanczos" in gif


def test_build_ffmpeg_args_trim_window():
    args = build_ffmpeg_args(
        "in.mp4", "out.mp3", "mp3", start=1.5, end=3.0, ffmpeg="ffmpeg"
    )
    assert args[args.index("-ss") + 1] == "1.500"
    assert args[args.index("-to") + 1] == "3.000"
    assert args.index("-ss") < args.index("-i")


def test_build_ffmpeg_args_rejects_bad_input():
    with pytest.raises(ConversionError):
        build_ffmpeg_args("in.mp4", "out.exe", "exe", ffmpeg="ffmpeg")
    with pytest.raises(ConversionError):
        build_ffmpeg_args("in.mp4", "out.mp3", "mp3", start=-1, ffmpeg="ffmpeg")
    with pytest.raises(ConversionError):
        build_ffmpeg_args("in.mp4", "out.mp3", "mp3", start=5, end=2, ffmpeg="ffmpeg")
