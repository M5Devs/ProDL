"""Network tests. Run with PRODL_LIVE=1 pytest backend/tests/test_live.py"""

from __future__ import annotations

import os

import pytest

pytestmark = pytest.mark.skipif(
    os.environ.get("PRODL_LIVE") != "1",
    reason="set PRODL_LIVE=1 to run tests that hit YouTube / SponsorBlock",
)

SAMPLE = "https://www.youtube.com/watch?v=jNQXAC9IVRw"


def test_live_health(client):
    body = client.get("/api/health").json()
    assert body["status"] == "ok"
    assert body["yt_dlp"]["available"] is True


def test_live_search(client):
    response = client.get("/api/search", params={"q": "lofi hip hop", "limit": 3})
    assert response.status_code == 200
    body = response.json()
    assert body["count"] >= 1
    first = body["results"][0]
    assert first["url"].startswith("http")
    assert first["title"]


def test_live_info(client):
    response = client.get("/api/info", params={"url": SAMPLE, "sponsorblock": "true"})
    assert response.status_code == 200
    body = response.json()
    assert "zoo" in body["title"].lower()
    assert body["formats"], "expected at least one downloadable format"
    assert any(fmt["has_video"] for fmt in body["formats"])
    assert any(fmt["has_audio"] for fmt in body["formats"])
    assert body["best_video_url"] or body["best_audio_url"]
    assert body["sponsorblock"] is not None
