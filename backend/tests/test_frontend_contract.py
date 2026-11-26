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


# ── Watch view & search filters (YouTube-style UI) ────────────────────────
def test_watch_view_is_wired(index_html, app_js):
    ids = element_ids(index_html)
    for name in (
        "view-watch",
        "watchThumb",
        "watchTitle",
        "watchChannel",
        "watchPlay",
        "watchPlayBtn",
        "watchDownload",
        "watchBack",
        "watchRelated",
        "watchRelatedHead",
    ):
        assert name in ids, f"missing watch element #{name}"
    # Every watch control must have a listener, or the view is dead markup.
    for handler in ("watchBack", "watchDownload", "watchPlay", "watchPlayBtn"):
        assert f"$('{handler}').addEventListener" in app_js, f"{handler} has no listener"
    assert "function openWatch(" in app_js
    assert "function renderWatch(" in app_js


def test_search_filters_exist_and_are_documented(index_html, app_js):
    chips = re.findall(r'data-type="([a-z]+)"', index_html)
    assert chips == ["all", "videos", "channels", "playlists"]
    assert 'id="searchFilters"' in index_html
    assert "$('searchFilters').addEventListener" in app_js
    # The filter must travel to the API, not only decorate the UI.
    assert re.search(r"api\.search\(\s*query,\s*12,\s*state\.searchType", app_js)
    spec = SPEC.read_text()
    assert "| `type` |" in spec
    for value in ("`all`", "`videos`", "`channels`", "`playlists`"):
        assert value in spec


def test_format_picker_groups_by_resolution(app_js):
    """Itags must be folded into one button per resolution, labelled by it."""
    body = re.search(r"function renderFormats\(formats\) \{(.*?)\n  \}", app_js, re.DOTALL)
    assert body, "renderFormats not found"
    source = body.group(1)
    assert "groups[" in source
    assert "res.textContent = group.label" in source
    # The old label was the raw stream note, which read "video only · avc1".
    assert "format.note || format.resolution" not in source


def test_related_items_are_documented():
    spec = SPEC.read_text()
    assert '"related"' in spec
    assert "kind" in spec and "item_count" in spec


def test_android_health_reports_a_transcoder_not_a_bundled_ffmpeg():
    spec = SPEC.read_text()
    assert "`transcoder`" in spec
    kotlin = ROOT / "android/app/src/main/java/app/prodl/server/LocalServer.kt"
    source = kotlin.read_text()
    assert "transcoder = ComponentStatus(" in source
    assert "ffmpeg = ComponentStatus(" not in source
