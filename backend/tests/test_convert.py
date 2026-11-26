from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

from helpers import wait_for_task
from prodl_core.ffmpeg import ffmpeg_info

pytestmark = pytest.mark.skipif(
    not ffmpeg_info().available, reason="ffmpeg is required for conversion tests"
)


@pytest.fixture
def sample_wav(settings) -> Path:
    """A real 1-second sine wave generated with ffmpeg."""
    target = settings.download_dir / "tone.wav"
    subprocess.run(
        [
            ffmpeg_info().path,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            str(target),
        ],
        check=True,
        timeout=60,
    )
    return target


def test_convert_requires_a_source(client):
    response = client.post("/api/convert", json={"target_format": "mp3"})
    assert response.status_code == 400
    assert "task_id" in response.json()["detail"]


def test_convert_rejects_unsupported_format(client, sample_wav):
    response = client.post(
        "/api/convert", json={"path": "tone.wav", "target_format": "exe"}
    )
    assert response.status_code == 422


def test_convert_rejects_path_traversal(client):
    response = client.post(
        "/api/convert", json={"path": "../../etc/passwd", "target_format": "mp3"}
    )
    assert response.status_code == 400
    assert "escapes" in response.json()["detail"] or "not found" in response.json()["detail"]


def test_convert_rejects_invalid_trim_window(client, sample_wav):
    response = client.post(
        "/api/convert",
        json={"path": "tone.wav", "target_format": "mp3", "start": 5, "end": 2},
    )
    assert response.status_code == 422


def test_convert_wav_to_mp3_produces_a_file(client, settings, sample_wav):
    response = client.post(
        "/api/convert",
        json={"path": "tone.wav", "target_format": "mp3", "audio_bitrate": "128k"},
    )
    assert response.status_code == 202
    task = response.json()
    assert task["kind"] == "convert"

    snapshot = wait_for_task(client, task["task_id"], timeout=60)
    assert snapshot["status"] == "finished", snapshot
    assert snapshot["file"]["filename"] == "tone.mp3"
    assert snapshot["file"]["filesize"] > 0

    produced = settings.download_dir / "tone.mp3"
    assert produced.is_file()
    assert produced.stat().st_size > 0

    download = client.get(task["file_url"])
    assert download.status_code == 200
    assert download.content[:2] in (b"ID", b"\xff\xfb", b"\xff\xf3")


def test_convert_upload_round_trip(client, sample_wav):
    with sample_wav.open("rb") as handle:
        response = client.post(
            "/api/convert/upload",
            files={"file": ("tone.wav", handle, "audio/wav")},
            data={"target_format": "m4a", "audio_bitrate": "128k"},
        )
    assert response.status_code == 202
    task = response.json()
    snapshot = wait_for_task(client, task["task_id"], timeout=60)
    assert snapshot["status"] == "finished", snapshot
    assert snapshot["file"]["filename"].endswith(".m4a")


def test_convert_trims_to_requested_window(client, settings, sample_wav):
    response = client.post(
        "/api/convert",
        json={"path": "tone.wav", "target_format": "wav", "start": 0.2, "end": 0.6},
    )
    assert response.status_code == 202
    task = response.json()
    snapshot = wait_for_task(client, task["task_id"], timeout=60)
    assert snapshot["status"] == "finished", snapshot

    produced = settings.download_dir / snapshot["file"]["filename"]
    assert produced.is_file()
    probe = subprocess.run(
        [
            shutil.which("ffprobe") or "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "format=duration",
            "-of",
            "csv=p=0",
            str(produced),
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    duration = float(probe.stdout.strip())
    assert 0.2 <= duration <= 0.6


def test_convert_unknown_source_file(client):
    response = client.post(
        "/api/convert", json={"path": "missing.wav", "target_format": "mp3"}
    )
    assert response.status_code == 400
