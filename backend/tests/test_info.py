from __future__ import annotations

import pytest

from app.schemas import InfoResponse
from app.services import media as media_service
from prodl_core.models import SubtitleInfo, VideoFormat, VideoInfo


def sample_info(**overrides) -> VideoInfo:
    payload = {
        "title": "Me at the zoo",
        "duration": 19,
        "thumbnail": "https://i.ytimg.com/vi/jNQXAC9IVRw/hq.jpg",
        "uploader": "jawed",
        "view_count": 300000000,
        "webpage_url": "https://www.youtube.com/watch?v=jNQXAC9IVRw",
        "extractor": "youtube",
        "formats": [
            VideoFormat(
                format_id="18",
                ext="mp4",
                resolution="640x360",
                filesize=1024,
                note="360p",
                has_video=True,
                has_audio=True,
                direct_url="https://rr1.googlevideo.com/videoplayback?itag=18",
            ),
            VideoFormat(
                format_id="140",
                ext="m4a",
                resolution="audio only",
                filesize=512,
                note="medium",
                has_video=False,
                has_audio=True,
                direct_url="https://rr1.googlevideo.com/videoplayback?itag=140",
            ),
        ],
        "subtitles": [SubtitleInfo(lang="en", lang_name="English", is_auto=False, ext="vtt")],
        "id": "jNQXAC9IVRw",
        "channel": "jawed",
        "is_live": False,
        "best_video_url": "https://rr1.googlevideo.com/videoplayback?itag=18",
        "best_audio_url": "https://rr1.googlevideo.com/videoplayback?itag=140",
    }
    payload.update(overrides)
    return VideoInfo(**payload)


def test_info_endpoint(client, monkeypatch):
    async def fake_info(settings, url, *, sponsorblock=False, categories=None):
        assert url == "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        return InfoResponse(**sample_info().model_dump())

    monkeypatch.setattr(media_service, "get_video_info", fake_info)

    response = client.get(
        "/api/info", params={"url": "https://www.youtube.com/watch?v=jNQXAC9IVRw"}
    )
    assert response.status_code == 200
    body = response.json()
    assert body["title"] == "Me at the zoo"
    assert body["extractor"] == "youtube"
    assert [f["format_id"] for f in body["formats"]] == ["18", "140"]
    assert body["formats"][0]["has_video"] is True
    assert body["subtitles"][0]["lang"] == "en"
    assert body["best_video_url"].startswith("https://rr1.googlevideo.com")
    assert body["best_audio_url"].endswith("itag=140")


def test_info_requires_url(client):
    assert client.get("/api/info").status_code == 422


def test_info_reports_extraction_errors(client, monkeypatch):
    async def boom(settings, url, *, sponsorblock=False, categories=None):
        raise ValueError("ERROR: [youtube] Unsupported URL")

    monkeypatch.setattr(media_service, "get_video_info", boom)
    response = client.get("/api/info", params={"url": "https://example.com/nope"})
    assert response.status_code == 400
    assert "Unsupported URL" in response.json()["detail"]


def test_info_parses_comma_separated_sponsor_categories(client, monkeypatch):
    seen = {}

    async def fake_info(settings, url, *, sponsorblock=False, categories=None):
        seen["sponsorblock"] = sponsorblock
        seen["categories"] = categories
        return InfoResponse(**sample_info().model_dump())

    monkeypatch.setattr(media_service, "get_video_info", fake_info)
    response = client.get(
        "/api/info",
        params={"url": "https://youtu.be/jNQXAC9IVRw", "sponsorblock": "true", "categories": "sponsor, intro"},
    )
    assert response.status_code == 200
    assert seen == {"sponsorblock": True, "categories": ["sponsor", "intro"]}


async def test_service_attaches_sponsorblock_segments(settings, monkeypatch):
    async def fake_get_info(url, *, cookies_file=None, include_direct_urls=True):
        return sample_info()

    async def fake_segments(video_id, categories=None, *, api=None, timeout=8.0):
        assert video_id == "jNQXAC9IVRw"
        return [
            {"category": "sponsor", "segment": [10, 20], "actionType": "skip", "UUID": "u1"},
            {"category": "intro", "segment": [0, 5], "actionType": "skip", "UUID": "u2"},
        ]

    monkeypatch.setattr(media_service.ProDLDownloader, "get_info", staticmethod(fake_get_info))
    monkeypatch.setattr(media_service, "get_segments", fake_segments)

    response = await media_service.get_video_info(
        settings, "https://www.youtube.com/watch?v=jNQXAC9IVRw", sponsorblock=True
    )
    assert response.sponsorblock is not None
    assert response.sponsorblock.video_id == "jNQXAC9IVRw"
    assert [segment.category for segment in response.sponsorblock.segments] == ["intro", "sponsor"]
    assert response.sponsorblock.segments[1].start == 10.0


async def test_service_sponsorblock_failure_is_not_fatal(settings, monkeypatch):
    async def fake_get_info(url, *, cookies_file=None, include_direct_urls=True):
        return sample_info()

    async def failing_segments(video_id, categories=None, *, api=None, timeout=8.0):
        raise RuntimeError("sponsor.ajay.app unreachable")

    monkeypatch.setattr(media_service.ProDLDownloader, "get_info", staticmethod(fake_get_info))
    monkeypatch.setattr(media_service, "get_segments", failing_segments)

    response = await media_service.get_video_info(
        settings, "https://www.youtube.com/watch?v=jNQXAC9IVRw", sponsorblock=True
    )
    assert response.sponsorblock is not None
    assert response.sponsorblock.segments == []
    assert "unreachable" in response.sponsorblock.error


async def test_service_skips_sponsorblock_for_non_youtube(settings, monkeypatch):
    async def fake_get_info(url, *, cookies_file=None, include_direct_urls=True):
        return sample_info(webpage_url="https://vimeo.com/12345", extractor="vimeo")

    monkeypatch.setattr(media_service.ProDLDownloader, "get_info", staticmethod(fake_get_info))

    response = await media_service.get_video_info(
        settings, "https://vimeo.com/12345", sponsorblock=True
    )
    assert response.sponsorblock is not None
    assert response.sponsorblock.video_id is None
    assert response.sponsorblock.segments == []


def test_legacy_info_alias_is_flat(client, monkeypatch):
    async def fake_info(settings, url, *, sponsorblock=False, categories=None):
        return InfoResponse(**sample_info().model_dump())

    monkeypatch.setattr(media_service, "get_video_info", fake_info)
    response = client.post("/info", json={"url": "https://youtu.be/jNQXAC9IVRw"})
    assert response.status_code == 200
    body = response.json()
    assert body["title"] == "Me at the zoo"
    assert "sponsorblock" not in body
    assert "cookies_used" not in body
