from __future__ import annotations


def test_health_reports_capabilities(client):
    response = client.get("/api/health")
    assert response.status_code == 200
    body = response.json()

    assert body["status"] in {"ok", "degraded"}
    assert body["api_version"]
    assert body["version"]
    assert body["python"]
    assert body["download_dir_writable"] is True
    assert body["yt_dlp"]["available"] is True
    assert body["yt_dlp"]["version"]
    assert "ffmpeg" in body and "available" in body["ffmpeg"]
    assert body["features"]["search"] is True
    assert body["features"]["progress_websocket"] is True
    assert body["cookies"]["configured"] is False


def test_health_uptime_is_monotonic(client):
    first = client.get("/api/health").json()["uptime_seconds"]
    second = client.get("/api/health").json()["uptime_seconds"]
    assert second >= first >= 0


def test_api_index_lists_endpoints(client):
    body = client.get("/api").json()
    assert body["name"] == "ProDL"
    for key in ("health", "search", "info", "download", "progress_ws", "convert", "cookies"):
        assert key in body["endpoints"]


def test_unknown_api_route_returns_json_404(client):
    response = client.get("/api/does-not-exist")
    assert response.status_code == 404
    assert response.json() == {"detail": "Not found"}


def test_legacy_health_alias(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_cors_allows_cross_origin_webview(client):
    response = client.get("/api/health", headers={"Origin": "null"})
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "*"


def test_cors_preflight(client):
    response = client.options(
        "/api/download",
        headers={
            "Origin": "http://localhost:3000",
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] in {"*", "http://localhost:3000"}
