from __future__ import annotations

import json

from app.services import media as media_service
from helpers import wait_for_task
from prodl_core.models import FileRef, ProgressEvent


def install_fake_download(monkeypatch, *, payload: bytes = b"ID3fake-audio"):
    """Replace the blocking downloader with a deterministic progress script."""

    def fake_run(task, request, settings, manager):
        manager.publish(
            task.id,
            ProgressEvent(
                status="downloading",
                percent=10.0,
                downloaded_bytes=1,
                total_bytes=10,
                speed=1024.0,
                speed_str="1.00KiB/s",
                eta=9,
                title="Test title",
                message="Downloading…",
            ),
        )
        target = settings.download_dir / "test title.mp3"
        target.write_bytes(payload)
        manager.publish(
            task.id,
            ProgressEvent(status="postprocessing", percent=100.0, message="Merging…"),
        )
        task.meta["filepath"] = str(target)
        manager.finish(
            task.id,
            file=FileRef(
                filename=target.name,
                filesize=len(payload),
                download_url=f"/api/files/{task.id}",
                media_type="audio/mpeg",
            ),
            message="Download complete",
        )

    monkeypatch.setattr(media_service, "run_download", fake_run)


def test_download_returns_task_contract(client, monkeypatch):
    install_fake_download(monkeypatch)
    response = client.post(
        "/api/download",
        json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True},
    )
    assert response.status_code == 202
    body = response.json()
    assert body["kind"] == "download"
    assert body["status"] in {"queued", "downloading"}
    task_id = body["task_id"]
    assert body["websocket_url"] == f"/api/ws/progress/{task_id}"
    assert body["sse_url"] == f"/api/progress/{task_id}"
    assert body["task_url"] == f"/api/tasks/{task_id}"
    assert body["file_url"] == f"/api/files/{task_id}"


def test_download_validates_payload(client):
    assert client.post("/api/download", json={}).status_code == 422
    assert client.post("/api/download", json={"url": "x"}).status_code == 422
    bad_quality = client.post(
        "/api/download", json={"url": "https://youtu.be/x", "video_quality": "ultra"}
    )
    assert bad_quality.status_code == 422


def test_format_selector_prefers_explicit_quality():
    from app.schemas import DownloadRequest

    assert DownloadRequest(url="https://x/y").format_selector.startswith("bestvideo[ext=mp4]")
    assert DownloadRequest(url="https://x/y", audio_only=True).format_selector == "bestaudio/best"
    assert (
        DownloadRequest(url="https://x/y", video_quality="720p").format_selector
        == "bestvideo[height<=720]+bestaudio/best[height<=720]/best"
    )
    assert DownloadRequest(url="https://x/y", format_id="137+140").format_selector == "137+140"


def test_websocket_streams_progress_and_terminates(client, monkeypatch):
    install_fake_download(monkeypatch)
    task = client.post(
        "/api/download", json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True}
    ).json()

    events = []
    with client.websocket_connect(task["websocket_url"]) as socket:
        while True:
            message = socket.receive_json()
            if message.get("type") == "ping":
                continue
            events.append(message)
            if message["status"] in {"finished", "error", "canceled"}:
                break

    statuses = [event["status"] for event in events]
    assert "finished" in statuses
    downloading = [event for event in events if event["status"] == "downloading"]
    if downloading:
        assert downloading[0]["percent"] == 10.0
        assert downloading[0]["speed"] == 1024.0
    final = events[-1]
    assert final["file"]["filename"] == "test title.mp3"
    assert final["file"]["download_url"] == f"/api/files/{task['task_id']}"


def test_sse_streams_the_same_events(client, monkeypatch):
    install_fake_download(monkeypatch)
    task = client.post(
        "/api/download", json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True}
    ).json()

    response = client.get(task["sse_url"])
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    frames = [
        json.loads(line[len("data: "):])
        for line in response.text.splitlines()
        if line.startswith("data: ")
    ]
    assert frames
    assert frames[-1]["status"] in {"finished", "error", "canceled"}


def test_finished_file_is_downloadable(client, monkeypatch):
    payload = b"ID3fake-audio"
    install_fake_download(monkeypatch, payload=payload)
    task = client.post(
        "/api/download", json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True}
    ).json()

    snapshot = wait_for_task(client, task["task_id"])
    assert snapshot["status"] == "finished"

    response = client.get(task["file_url"])
    assert response.status_code == 200
    assert response.content == payload
    disposition = response.headers["content-disposition"]
    assert "attachment" in disposition
    assert "mp3" in disposition


def test_unknown_task_is_404(client):
    assert client.get("/api/tasks/nope").status_code == 404
    assert client.get("/api/files/nope").status_code == 404
    assert client.get("/api/progress/nope").status_code == 404


def test_task_snapshot_and_cancel(client, monkeypatch):
    import threading

    release = threading.Event()

    def slow_run(task, request, settings, manager):
        manager.publish(task.id, ProgressEvent(status="downloading", percent=1.0))
        release.wait(5)
        if task.cancel_event.is_set():
            manager.mark_canceled(task.id)
        else:
            manager.fail(task.id, "not canceled")

    monkeypatch.setattr(media_service, "run_download", slow_run)
    task = client.post(
        "/api/download", json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True}
    ).json()

    cancel_response = client.post(f"/api/tasks/{task['task_id']}/cancel")
    assert cancel_response.status_code == 200
    assert cancel_response.json()["status"] in {"queued", "downloading"}

    release.set()
    snapshot = wait_for_task(client, task["task_id"])
    assert snapshot["status"] == "canceled"


def test_delete_file_removes_task_and_artefact(client, monkeypatch, settings):
    install_fake_download(monkeypatch)
    task = client.post(
        "/api/download", json={"url": "https://youtu.be/jNQXAC9IVRw", "audio_only": True}
    ).json()

    wait_for_task(client, task["task_id"])
    response = client.delete(task["file_url"])
    assert response.status_code == 204
    assert client.get(f"/api/tasks/{task['task_id']}").status_code == 404
    assert not (settings.download_dir / "test title.mp3").exists()
