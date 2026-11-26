"""Shared yt-dlp option helpers.

yt-dlp only enables the Deno JavaScript runtime by default.  When Deno is not
installed (very common on desktop/Android shells) YouTube extraction degrades
and some formats go missing, so we declare every runtime we can actually find.
"""

from __future__ import annotations

import os
from typing import Any

from .ffmpeg import find_binary

JS_RUNTIMES = ("deno", "node", "bun", "quickjs")


def detect_js_runtimes() -> dict[str, dict[str, str]]:
    runtimes: dict[str, dict[str, str]] = {}
    for name in JS_RUNTIMES:
        path = find_binary(name, f"PRODL_{name.upper()}")
        if path:
            runtimes[name] = {"path": path}
    return runtimes


def remote_components() -> list[str] | None:
    """Opt-in yt-dlp remote components, e.g. ``PRODL_YTDLP_REMOTE_COMPONENTS=ejs:github``.

    Left empty by default: this downloads code from a remote host, so it is
    only enabled when the operator explicitly asks for it.
    """
    raw = os.environ.get("PRODL_YTDLP_REMOTE_COMPONENTS", "")
    items = [item.strip() for item in raw.split(",") if item.strip()]
    return items or None


def base_opts(**overrides: Any) -> dict[str, Any]:
    opts: dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "cachedir": False,
    }
    runtimes = detect_js_runtimes()
    if runtimes:
        opts["js_runtimes"] = runtimes
    components = remote_components()
    if components:
        opts["remote_components"] = components
    opts.update(overrides)
    return opts
