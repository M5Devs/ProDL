from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from app.config import load_settings
from app.main import create_app


@pytest.fixture
def settings(tmp_path):
    return load_settings(
        data_dir=tmp_path / "data",
        download_dir=tmp_path / "downloads",
        uploads_dir=tmp_path / "uploads",
        cookies_file=tmp_path / "cookies.txt",
        serve_frontend=False,
        max_workers=2,
        task_ttl_seconds=600,
    )


@pytest.fixture
def app(settings):
    return create_app(settings)


@pytest.fixture
def client(app):
    with TestClient(app) as test_client:
        yield test_client
