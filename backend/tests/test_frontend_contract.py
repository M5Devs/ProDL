"""Static contract checks between the SPA files and the documented API.

These catch the boring-but-fatal class of bugs: an element id renamed in the
HTML but still referenced in JS, a script loaded in the wrong order, a CDN
dependency sneaking into the "works offline in a WebView" bundle, or a client
route that no longer exists in API_SPEC.md.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
FRONTEND = ROOT / "frontend"
INDEX = FRONTEND / "index.html"
APP_JS = FRONTEND / "js" / "app.js"
API_JS = FRONTEND / "js" / "api.js"
CONFIG_JS = FRONTEND / "js" / "config.js"
SPEC = ROOT / "API_SPEC.md"


@pytest.fixture(scope="module")
def index_html() -> str:
    return INDEX.read_text()


@pytest.fixture(scope="module")
def app_js() -> str:
    return APP_JS.read_text()


def element_ids(html: str) -> set[str]:
    return set(re.findall(r'\bid="([^"]+)"', html))


def referenced_ids(source: str) -> set[str]:
    """Every string passed to $() plus the ids declared in the progress-ref maps."""
    found = set(re.findall(r"\$\('([^']+)'\)", source))
    for name in ("DOWNLOAD_PROGRESS", "CONVERT_PROGRESS"):
        block = re.search(rf"var {name} = \{{(.*?)\}};", source, re.DOTALL)
        if block:
            found |= set(re.findall(r"'([A-Za-z][\w-]*)'", block.group(1)))
    return {value for value in found if not value.startswith(("http", "/", "js/"))}


def test_every_referenced_element_id_exists(index_html, app_js):
    ids = element_ids(index_html)
    missing = sorted(referenced_ids(app_js) - ids)
    assert missing == [], f"app.js references missing element ids: {missing}"


def test_scripts_load_in_dependency_order(index_html):
    order = re.findall(r'<script src="(js/[^"]+)"', index_html)
    assert order == ["js/config.js", "js/api.js", "js/app.js"]


def test_frontend_has_no_external_asset_dependencies(index_html):
    # A WebView bundle must work offline: only the GitHub footer link may be remote.
    remote = [
        url
        for url in re.findall(r'(?:src|href)="(https?://[^"]+)"', index_html)
        if "github.com" not in url
    ]
    assert remote == [], f"unexpected remote assets: {remote}"
    assert "@import" not in index_html


def test_frontend_ships_no_inline_event_handlers(index_html):
    assert not re.search(r"\son(?:click|load|submit|change)=", index_html)


def test_api_client_routes_are_documented(app_js):
    source = API_JS.read_text() + app_js
    spec = SPEC.read_text()
    literals = set(re.findall(r"'/api[^']*'", source))
    undocumented = sorted(
        literal.strip("'") for literal in literals if literal.strip("'") not in spec
    )
    assert undocumented == [], f"client calls routes missing from API_SPEC.md: {undocumented}"


def test_only_config_module_knows_a_default_backend_url():
    for path in (APP_JS, API_JS):
        assert "127.0.0.1" not in path.read_text(), f"{path.name} must not hardcode a host"
    assert "127.0.0.1:8000" in CONFIG_JS.read_text()


def test_config_exposes_the_resolution_chain():
    source = CONFIG_JS.read_text()
    for marker in ("prodl.apiBase", "__PRODL_API_BASE__", "PRODL_CONFIG", "isFileOrigin"):
        assert marker in source


def test_cors_wildcard_default_is_documented():
    spec = SPEC.read_text()
    assert "PRODL_CORS_ORIGINS" in spec
    assert "Origin: null" in spec
