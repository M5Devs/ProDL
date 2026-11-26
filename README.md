<div align="center">
  <h1>ProDL 🚀</h1>
  <p>Free, open-source video &amp; audio downloader. YouTube, Twitter, TikTok, Instagram and 1000+ more sites.</p>
  <p>
    <img src="https://img.shields.io/badge/license-AGPL--v3-blue" alt="AGPL v3" />
    <img src="https://img.shields.io/badge/python-3.12+-green" alt="Python 3.12+" />
    <img src="https://img.shields.io/badge/powered%20by-yt--dlp-red" alt="yt-dlp" />
    <img src="https://img.shields.io/badge/API-documented-brightgreen" alt="API spec" />
  </p>
</div>

---

ProDL is a self-hostable downloader with a **pure JSON API** and a **dependency-free
web UI**. The same backend powers a browser app, a desktop shell (Electron/Tauri)
and, on the roadmap, an Android app that runs the server locally — the frontend
never assumes where the API lives.

- 🔎 **Search without an API key** — yt-dlp's InnerTube search, no Google quota
- 🎬 **Ad-free in-app playback** — `/api/info` returns direct stream URLs
- 🎚️ **Any quality, any format** — 144p → 4K, MP3/M4A/Opus/FLAC/WAV, subtitles, thumbnails
- ✂️ **SponsorBlock + FFmpeg** — cut sponsors while downloading, trim or convert afterwards
- 📡 **Live progress** — WebSocket, with SSE and polling fallbacks
- 🍪 **Cookies stay on device** — Netscape import for age-restricted / private content, `0600` on disk
- 🌍 **Cross-platform by construction** — `pathlib`, env config, no hardcoded URLs, ports or OS paths

---

## Table of contents

- [Architecture](#architecture)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [API](#api)
- [Cross-platform shells](#cross-platform-shells)
- [Development](#development)
- [Deploy options](#deploy-options)
- [Privacy](#privacy)
- [License](#license)

---

## Architecture

```
ProDL/
├── backend/                     FastAPI service (JSON + WebSocket/SSE)
│   ├── app/
│   │   ├── main.py              app factory: routers, CORS, static SPA
│   │   ├── config.py            env-driven settings (no hardcoded paths)
│   │   ├── schemas.py           request/response models
│   │   ├── tasks.py             task registry + progress fan-out
│   │   ├── deps.py              shared FastAPI dependencies
│   │   ├── services/media.py    yt-dlp / ffmpeg orchestration
│   │   └── routers/             health, search, media, tasks, cookies, legacy
│   ├── tests/                   pytest suite (hermetic + opt-in live)
│   ├── Dockerfile               multi-arch image, non-root, healthcheck
│   └── requirements.txt
├── frontend/                    static SPA — no build step, no CDN
│   ├── index.html
│   ├── css/styles.css
│   └── js/{config,api,app}.js   API_BASE_URL resolution, client, UI
├── packages/prodl-core/         reusable Python library (yt-dlp wrapper)
├── API_SPEC.md                  every endpoint, schema and error code
└── docker-compose.yml
```

**Why this shape?** The backend is a headless API; the frontend is a static bundle
that talks to it over HTTP. Nothing is rendered server-side, so:

- the browser app is served by the backend (`PRODL_SERVE_FRONTEND=1`, same origin), **or**
- hosted anywhere else (`python -m http.server`, S3, a WebView bundle) and pointed
  at the API with `?api=…` / `localStorage` / `window.__PRODL_API_BASE__`.

---

## Quick start

### Docker (recommended)

```bash
git clone https://github.com/M5Devs/ProDL
cd ProDL
docker compose up -d --build
```

Open <http://localhost:8000>. Data (downloads, cookies) lives in the `prodl-data`
volume. Publish on another port with `PRODL_PUBLISH_PORT=9000 docker compose up -d`.

### Local development

```bash
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r backend/requirements.txt
pip install -e ./packages/prodl-core

# ffmpeg is required for merging/audio extraction
sudo apt install ffmpeg        # macOS: brew install ffmpeg · Windows: winget install Gyan.FFmpeg

cd backend && python -m app     # http://127.0.0.1:8000
```

Optional but recommended: install a JavaScript runtime (`deno`, `node`, `bun` or
`quickjs`) so YouTube signature challenges can be solved — yt-dlp only enables
Deno by default, and ProDL auto-detects the others.

### Frontend standalone

```bash
cd frontend && python -m http.server 3000
# then open http://127.0.0.1:3000/?api=http://127.0.0.1:8000
```

Or open `frontend/index.html` directly from disk — from `file://` the app defaults
to `http://127.0.0.1:8000` and CORS (`*`) allows it.

---

## Configuration

Everything is an environment variable with a cross-platform default; see
[API_SPEC.md § Environment variables](API_SPEC.md#15-environment-variables) for the
full table. The ones you are most likely to touch:

| Variable | Default | Purpose |
| --- | --- | --- |
| `PRODL_DATA_DIR` | OS-specific app data dir | Downloads, uploads, cookies |
| `PRODL_DOWNLOAD_DIR` | `<data>/downloads` | Finished files |
| `PRODL_CORS_ORIGINS` | `*` | Allowed client origins (set explicitly if you expose the API) |
| `PRODL_SERVE_FRONTEND` | `1` | Serve `frontend/` from the API origin |
| `PRODL_MAX_WORKERS` | `4` | Concurrent download/convert jobs |
| `PRODL_MAX_UPLOAD_MB` | `512` | Upload ceiling for conversions |
| `PRODL_YTDLP_REMOTE_COMPONENTS` | *(empty)* | Opt-in `ejs:github` challenge solver (downloads code — off by default) |

---

## API

Full contract, schemas and examples: **[API_SPEC.md](API_SPEC.md)**.
Interactive docs: `/api/docs` (Swagger UI) and `/api/openapi.json`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/health` | Status + yt-dlp/ffmpeg/JS-runtime availability |
| `GET` | `/api/search?q=&limit=` | Key-less search |
| `GET` | `/api/info?url=&sponsorblock=` | Metadata, formats, subtitles, direct URLs, SponsorBlock |
| `POST` | `/api/download` | Queue a download → `task_id` |
| `WS` | `/api/ws/progress/{task_id}` | Live progress (SSE at `/api/progress/{task_id}`) |
| `GET` | `/api/tasks/{task_id}` | Snapshot / polling |
| `POST` | `/api/tasks/{task_id}/cancel` | Cancel |
| `GET`/`DELETE` | `/api/files/{task_id}` | Fetch or delete the artefact |
| `POST` | `/api/convert` · `/api/convert/upload` | FFmpeg convert / trim |
| `GET`/`POST`/`DELETE` | `/api/cookies` | Local Netscape cookie store |

```bash
# 1. find something
curl "http://127.0.0.1:8000/api/search?q=big+buck+bunny&limit=3"

# 2. inspect formats
curl "http://127.0.0.1:8000/api/info?url=https://youtu.be/jNQXAC9IVRw&sponsorblock=true"

# 3. download audio only
curl -X POST http://127.0.0.1:8000/api/download \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://youtu.be/jNQXAC9IVRw","audio_only":true,"audio_format":"mp3"}'

# 4. follow progress (or open the WebSocket)
curl -N http://127.0.0.1:8000/api/progress/<task_id>

# 5. save the result
curl -OJ http://127.0.0.1:8000/api/files/<task_id>
```

---

## Cross-platform shells

The frontend is a plain static bundle, so each shell only needs to (a) host the
files and (b) tell the page where the API is.

| Target | How |
| --- | --- |
| **Web** | Served by the backend, or any static host with `?api=https://your-server` |
| **Desktop (Electron/Tauri)** | Ship `frontend/`, run the backend as a sidecar on `127.0.0.1:<free port>`, inject `window.__PRODL_API_BASE__` before the app scripts |
| **Android (local server)** | Bundle the SPA in the APK, run the backend via Chaquopy/Termux-style sidecar on `127.0.0.1:8000`, load `file:///android_asset/index.html` — the default base URL already points there |
| **CLI / scripts** | Use the JSON API directly (see API_SPEC.md) |

Resolution order for the API base URL:
`?api=` → `localStorage` → `window.__PRODL_API_BASE__` → `window.PRODL_CONFIG.apiBaseUrl`
→ same origin (http) → `http://127.0.0.1:8000` (file://).

---

## Development

```bash
pip install -r backend/requirements.txt pytest pytest-asyncio
pip install -e ./packages/prodl-core

pytest                                           # hermetic suite (real ffmpeg conversion tests)
PRODL_LIVE=1 pytest backend/tests/test_live.py   # real YouTube + SponsorBlock calls
```

Tests use FastAPI's `TestClient` with a throwaway data directory; conversion tests
generate real media with ffmpeg. Nothing hits the network unless `PRODL_LIVE=1`.

Linting/formatting are not enforced by the repo; keep imports at the top, use
`pathlib` for paths, and never hardcode a host, port or OS-specific path.

---

## Deploy options

| Platform | Cost | Notes |
| --- | --- | --- |
| Self-hosted (Docker) | Free | `docker compose up -d --build` |
| Hugging Face Spaces | Free | Docker SDK, set `PRODL_SERVE_FRONTEND=1` |
| Koyeb / Fly.io | Free tier | Build from `backend/Dockerfile` |
| Behind a proxy | — | Set `PRODL_CORS_ORIGINS` to your origin and terminate TLS at the proxy |

---

## Privacy

No accounts, no analytics, no telemetry. Outbound traffic goes only to the media
site you requested (plus `sponsor.ajay.app` when SponsorBlock is enabled).
Downloads stay in `PRODL_DOWNLOAD_DIR`; cookies are stored locally with `0600`
permissions and are never returned by the API.

Only download content you have the right to download. You are responsible for
complying with the terms of service of the sites you use.

---

## License

GNU Affero General Public License v3.0 — see [LICENSE](LICENSE).
