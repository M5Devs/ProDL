from __future__ import annotations

import pytest

from app.services import media as media_service
from prodl_core.models import SearchResult


def _result(index: int) -> SearchResult:
    return SearchResult(
        id=f"id{index}",
        title=f"Video {index}",
        url=f"https://www.youtube.com/watch?v=id{index}",
        thumbnail=f"https://i.ytimg.com/vi/id{index}/hq.jpg",
        duration=60 + index,
        uploader="Channel",
        view_count=1000 * index,
    )


def test_search_returns_normalised_results(client, monkeypatch):
    calls = {}

    async def fake_search(settings, query, limit=None):
        calls["query"] = query
        calls["limit"] = limit
        return [_result(1), _result(2)]

    monkeypatch.setattr(media_service, "search", fake_search)

    response = client.get("/api/search", params={"q": "lofi", "limit": 5})
    assert response.status_code == 200
    body = response.json()
    assert body == {
        "query": "lofi",
        "limit": 5,
        "count": 2,
        "results": [
            {
                "id": "id1",
                "title": "Video 1",
                "url": "https://www.youtube.com/watch?v=id1",
                "thumbnail": "https://i.ytimg.com/vi/id1/hq.jpg",
                "duration": 61,
                "uploader": "Channel",
                "view_count": 1000,
                "is_live": False,
                "extractor": None,
            },
            {
                "id": "id2",
                "title": "Video 2",
                "url": "https://www.youtube.com/watch?v=id2",
                "thumbnail": "https://i.ytimg.com/vi/id2/hq.jpg",
                "duration": 62,
                "uploader": "Channel",
                "view_count": 2000,
                "is_live": False,
                "extractor": None,
            },
        ],
    }
    assert calls == {"query": "lofi", "limit": 5}


def test_search_defaults_to_configured_limit(client, monkeypatch):
    seen = {}

    async def fake_search(settings, query, limit=None):
        seen["limit"] = limit
        return []

    monkeypatch.setattr(media_service, "search", fake_search)
    response = client.get("/api/search", params={"q": "anything"})
    assert response.status_code == 200
    assert response.json()["count"] == 0
    assert seen["limit"] == 10


def test_search_rejects_missing_or_blank_query(client):
    assert client.get("/api/search").status_code == 422
    assert client.get("/api/search", params={"q": "   "}).status_code == 422


def test_search_rejects_oversized_limit(client):
    assert client.get("/api/search", params={"q": "x", "limit": 500}).status_code == 422


def test_search_surfaces_upstream_failure_as_502(client, monkeypatch):
    async def boom(settings, query, limit=None):
        raise RuntimeError("ERROR: [youtube] Sign in to confirm you're not a bot")

    monkeypatch.setattr(media_service, "search", boom)
    response = client.get("/api/search", params={"q": "x"})
    assert response.status_code == 502
    assert "Sign in to confirm" in response.json()["detail"]


@pytest.mark.parametrize(
    "query,expected",
    [("hello world", "ytsearch5:hello world"), ("https://youtu.be/abc", "https://youtu.be/abc")],
)
def test_search_target_building(monkeypatch, query, expected):
    """The keyless search builds a ytsearch target, unless a URL was pasted."""
    from prodl_core import search as search_module

    captured = {}

    class FakeYDL:
        def __init__(self, opts):
            captured["opts"] = opts

        def __enter__(self):
            return self

        def __exit__(self, *exc):
            return False

        def extract_info(self, target, download=False):
            captured["target"] = target
            return {"_type": "playlist", "entries": []}

    monkeypatch.setattr(search_module.yt_dlp, "YoutubeDL", FakeYDL)
    assert search_module.search_videos_sync(query, 5) == []
    assert captured["target"] == expected
