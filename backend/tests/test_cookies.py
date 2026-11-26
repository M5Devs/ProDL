from __future__ import annotations

import os
import stat

VALID = (
    "# Netscape HTTP Cookie File\n"
    ".youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tsecret-value\n"
    ".youtube.com\tTRUE\t/\tFALSE\t1893456000\tPREF\tpref-value\n"
)


def test_cookie_status_starts_empty(client):
    body = client.get("/api/cookies").json()
    assert body == {
        "configured": False,
        "cookie_count": 0,
        "updated_at": None,
        "storage": "local-file",
    }


def test_store_and_delete_cookies(client, settings):
    response = client.post("/api/cookies", json={"content": VALID})
    assert response.status_code == 200
    body = response.json()
    assert body["configured"] is True
    assert body["cookie_count"] == 2
    assert body["updated_at"]

    stored = settings.cookies_file.read_text()
    assert "SID" in stored
    assert stored.startswith("# Netscape HTTP Cookie File")

    if os.name == "posix":
        mode = stat.S_IMODE(settings.cookies_file.stat().st_mode)
        assert mode == 0o600

    assert client.get("/api/cookies").json()["cookie_count"] == 2

    deleted = client.delete("/api/cookies")
    assert deleted.status_code == 200
    assert deleted.json()["configured"] is False
    assert not settings.cookies_file.exists()


def test_cookie_payload_never_echoes_values(client):
    response = client.post("/api/cookies", json={"content": VALID})
    assert "secret-value" not in response.text
    assert "pref-value" not in response.text


def test_invalid_cookie_rows_are_rejected(client):
    response = client.post("/api/cookies", json={"content": "not-a-cookie-line\n"})
    assert response.status_code == 422
    assert "row 1" in response.json()["detail"]
    assert "not-a-cookie-line" not in response.json()["detail"]


def test_empty_cookie_payload_is_rejected(client):
    assert client.post("/api/cookies", json={"content": ""}).status_code == 422
    assert client.post("/api/cookies", json={"content": "# only comments\n"}).status_code == 422


def test_cookie_upload_endpoint(client, settings):
    response = client.post(
        "/api/cookies/upload",
        files={"file": ("cookies.txt", VALID.encode(), "text/plain")},
    )
    assert response.status_code == 200
    assert response.json()["cookie_count"] == 2
    assert settings.cookies_file.is_file()


def test_health_reflects_cookie_state(client):
    client.post("/api/cookies", json={"content": VALID})
    health = client.get("/api/health").json()
    assert health["cookies"]["configured"] is True
    assert health["cookies"]["cookie_count"] == 2


def test_oversized_cookie_upload_is_413(client):
    big = ("# Netscape HTTP Cookie File\n" + ("x" * (2 * 1024 * 1024))).encode()
    response = client.post(
        "/api/cookies/upload", files={"file": ("cookies.txt", big, "text/plain")}
    )
    assert response.status_code == 413
    assert "MiB" in response.json()["detail"]


def test_oversized_cookie_json_is_422(client):
    response = client.post("/api/cookies", json={"content": "x" * (2 * 1024 * 1024 + 10)})
    assert response.status_code == 422
