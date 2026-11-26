"""ProDL API application factory.

The backend is a pure JSON/WebSocket API.  When ``PRODL_SERVE_FRONTEND`` is on
(the default) the same process also serves the static SPA from ``frontend/``,
so a single origin is enough.  The SPA also works standalone (desktop WebView,
Android WebView, ``python -m http.server``) because every call goes through the
configurable ``API_BASE_URL``.
"""

from __future__ import annotations

import os
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .config import Settings, get_cached_settings
from .routers import cookies as cookies_routes
from .routers import health as health_routes
from .routers import legacy as legacy_routes
from .routers import media as media_routes
from .routers import search as search_routes
from .routers import tasks as tasks_routes
from .schemas import ApiIndex
from .tasks import TaskManager

API_VERSION = "1.1.0"

DESCRIPTION = """
Free, open-source video and audio downloader API.

* `GET /api/health` – server, yt-dlp, ffmpeg and cookie readiness
* `GET /api/search` – key-less search (no YouTube Data API key)
* `GET /api/info` – metadata, formats, subtitles, direct stream URLs, SponsorBlock
* `POST /api/download` – queue a download, returns a `task_id`
* `WS /api/ws/progress/{task_id}` / `GET /api/progress/{task_id}` – live progress
* `POST /api/convert` – FFmpeg conversion / trimming
* `POST /api/cookies` – store local Netscape cookies

See API_SPEC.md in the repository root for full schemas.
"""


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_cached_settings()
    settings.ensure_directories()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        yield
        app.state.tasks.shutdown()

    app = FastAPI(
        title="ProDL API",
        version=__version__,
        description=DESCRIPTION,
        lifespan=lifespan,
        docs_url="/api/docs",
        redoc_url="/api/redoc",
        openapi_url="/api/openapi.json",
    )

    app.state.settings = settings
    app.state.tasks = TaskManager(
        max_tasks=settings.max_tasks,
        ttl_seconds=settings.task_ttl_seconds,
        max_workers=settings.max_workers,
    )
    app.state.started_at = time.time()

    app.add_middleware(
        CORSMiddleware,
        allow_origins=list(settings.cors_origins),
        allow_credentials=settings.allow_credentials,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["*"],
        expose_headers=["Content-Disposition", "Content-Length"],
        max_age=600,
    )

    prefix = settings.api_prefix
    for router in (
        health_routes.router,
        search_routes.router,
        media_routes.router,
        tasks_routes.router,
        cookies_routes.router,
    ):
        app.include_router(router, prefix=prefix)
    app.include_router(legacy_routes.router)

    @app.get(prefix, response_model=ApiIndex, tags=["meta"], summary="API index")
    def api_index() -> ApiIndex:
        return ApiIndex(
            name="ProDL",
            version=__version__,
            api_version=API_VERSION,
            docs="/api/docs",
            openapi="/api/openapi.json",
            endpoints={
                "health": f"{prefix}/health",
                "search": f"{prefix}/search?q=<query>&limit=<n>",
                "info": f"{prefix}/info?url=<url>",
                "download": f"{prefix}/download",
                "progress_ws": f"{prefix}/ws/progress/{{task_id}}",
                "progress_sse": f"{prefix}/progress/{{task_id}}",
                "task": f"{prefix}/tasks/{{task_id}}",
                "file": f"{prefix}/files/{{task_id}}",
                "convert": f"{prefix}/convert",
                "cookies": f"{prefix}/cookies",
            },
        )

    @app.api_route(
        f"{prefix}/{{path:path}}",
        methods=["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"],
        include_in_schema=False,
    )
    async def api_not_found(path: str) -> JSONResponse:
        return JSONResponse({"detail": "Not found"}, status_code=404)

    index_file = settings.frontend_dir / "index.html"
    serve_frontend = settings.serve_frontend and index_file.is_file()

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse | FileResponse:
        path = request.url.path
        wants_html = "text/html" in request.headers.get("accept", "")
        if (
            exc.status_code == 404
            and serve_frontend
            and not path.startswith(prefix)
            and wants_html
        ):
            return FileResponse(index_file)
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)

    if serve_frontend:
        app.mount(
            "/", StaticFiles(directory=str(settings.frontend_dir), html=True), name="frontend"
        )
    else:

        @app.get("/", include_in_schema=False)
        def root() -> dict:
            return {
                "message": "ProDL API",
                "version": __version__,
                "docs": "/api/docs",
                "frontend": "disabled (set PRODL_SERVE_FRONTEND=1)",
            }

    return app


app = create_app()


def main() -> None:  # pragma: no cover - process entry point
    import uvicorn

    host = os.environ.get("PRODL_HOST", "127.0.0.1")
    port = int(os.environ.get("PRODL_PORT", "8000"))
    uvicorn.run("app.main:app", host=host, port=port, reload=False)


if __name__ == "__main__":  # pragma: no cover
    main()
