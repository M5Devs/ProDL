# ProDL API Specification

Version `1.1.0` · Backend `2.1.0` · Last updated 2026-11-26

ProDL exposes a single JSON API over HTTP plus one WebSocket (with an SSE
fallback) for live progress. Everything the web UI does goes through this API —
there is no server-side template rendering, so any client (browser, Electron/Tauri
WebView, Android WebView against a local server, CLI) uses the same contract.

- Base URL: `http://<host>:<port>` — default `http://127.0.0.1:8000`
- API prefix: `/api`
- Content type: `application/json` (uploads use `multipart/form-data`)
- OpenAPI: `/api/openapi.json` · Swagger UI: `/api/docs` · ReDoc: `/api/redoc`

---

## Table of contents

1. [Conventions](#1-conventions)
2. [Errors](#2-errors)
3. [CORS & client configuration](#3-cors--client-configuration)
4. [`GET /api/health`](#4-get-apihealth)
5. [`GET /api/search`](#5-get-apisearch)
6. [`GET /api/info`](#6-get-apiinfo)
7. [`POST /api/download`](#7-post-apidownload)
8. [Progress: WebSocket, SSE, polling](#8-progress-websocket-sse-polling)
9. [`POST /api/convert`](#9-post-apiconvert)
10. [`POST /api/convert/upload`](#10-post-apiconvertupload)
11. [Files: download & delete](#11-files-download--delete)
12. [`/api/cookies`](#12-apicookies)
13. [`GET /api` (service index)](#13-get-api-service-index)
14. [Legacy endpoints](#14-legacy-endpoints)
15. [Environment variables](#15-environment-variables)
16. [Data, storage & privacy](#16-data-storage--privacy)

---

## 1. Conventions

| Item | Rule |
| --- | --- |
| Timestamps | Unix epoch seconds (`float`) unless a field name ends in `_at` in a human-facing payload, where an ISO-8601 UTC string is used |
| Sizes | Bytes (`int`) |
| Durations | Seconds (`float`) |
| Percentages | `0.0`–`100.0` (`float`), or `null` when unknown |
| Task identifiers | 32-char lowercase hex (`uuid4().hex`) |
| Unknown fields | Clients must ignore fields they do not know (additive evolution) |
| Async work | Long jobs return `202 Accepted` with a `task_id`; observe it via [progress](#8-progress-websocket-sse-polling) |
| Paths in responses | `download_url` values are **relative** (`/api/files/<task_id>`); clients join them with their configured base URL |

### Task lifecycle

```
queued ──► downloading ──► postprocessing ──► finished
   │            │                 │
   └────────────┴─────────────────┴──► error
   └────────────┴─────────────────┴──► canceled
```

`converting` replaces `downloading`/`postprocessing` for `/api/convert` tasks.
Terminal statuses: `finished`, `error`, `canceled`.

---

## 2. Errors

All errors use FastAPI's shape:

```json
{ "detail": "Human readable explanation" }
```

Validation errors (`422`) return a list in `detail`:

```json
{
  "detail": [
    { "type": "string_too_short", "loc": ["body", "url"], "msg": "String should have at least 4 characters", "input": "x" }
  ]
}
```

| Status | Meaning |
| --- | --- |
| `400` | The request was well-formed but the media/URL/state cannot be used (e.g. extraction failed, file missing) |
| `404` | Unknown task id, unknown file, or unknown `/api/*` route |
| `413` | Upload exceeds `PRODL_MAX_UPLOAD_MB` |
| `422` | Schema validation failed (bad body, bad enum, bad query parameter, oversized JSON payload) |
| `502` | Upstream failure while searching/extracting (YouTube, SponsorBlock, …) |
| `500` | Unexpected server error |

---

## 3. CORS & client configuration

The server allows cross-origin requests from the origins in `PRODL_CORS_ORIGINS`
(default `*`, which is required for `file://` WebViews that send
`Origin: null`). Credentials are only enabled when the wildcard is replaced by an
explicit origin list, because browsers reject `*` together with credentials.

The SPA resolves its backend in this order, so **no URL is ever hardcoded**:

1. `?api=https://host:port` query parameter
2. `localStorage["prodl.apiBase"]` (Settings dialog)
3. `window.__PRODL_API_BASE__` (injected by a desktop/Android shell)
4. `window.PRODL_CONFIG.apiBaseUrl` (`frontend/js/config.js`)
5. same origin, when the page was served over http(s)
6. `http://127.0.0.1:8000` when the page was loaded from `file://`

Example: a WebView loading `file:///android_asset/index.html` sets
`window.__PRODL_API_BASE__ = "http://127.0.0.1:8000"` before the app scripts run.

---

## 4. `GET /api/health`

Server status and capability probe. Used by the UI pill, container healthchecks
and client pre-flight checks.

**Response `200`**

```json
{
  "status": "ok",
  "api_version": "1.1.0",
  "version": "2.1.0",
  "uptime_seconds": 42.51,
  "platform": "Linux 6.8.0-1055-gke",
  "python": "3.13.15",
  "data_dir": "/home/user/.local/share/ProDL",
  "download_dir": "/home/user/.local/share/ProDL/downloads",
  "download_dir_writable": true,
  "yt_dlp": { "available": true, "version": "2026.11.23", "path": null, "note": "pip install -U yt-dlp if extraction starts failing" },
  "ffmpeg": { "available": true, "version": "ffmpeg version 7.1.5 ...", "path": "/usr/bin/ffmpeg", "note": null },
  "ffprobe": { "available": true, "version": "ffprobe version 7.1.5 ...", "path": "/usr/bin/ffprobe", "note": null },
  "js_runtime": { "available": true, "version": "v24.22.2", "path": "/usr/local/bin/node", "note": null },
  "cookies": { "configured": false, "cookie_count": 0, "updated_at": null, "storage": "local-file" },
  "features": {
    "search": true,
    "info": true,
    "download": true,
    "convert": true,
    "subtitles": true,
    "sponsorblock": true,
    "cookies": true,
    "progress_websocket": true,
    "progress_sse": true
  }
}
```

`status` is `ok` when at least one extraction engine is ready, ffmpeg is
available and the download directory is writable; otherwise `degraded` (the
`note` fields say what is missing). `features.<x>` is `false` when the
capability cannot work on this host (e.g. `convert` without ffmpeg).

### Extractor components

The reference backend reports one extraction engine. The Android app runs two
and reports both, so an engine that is still unpacking never blocks the other:

| Component | Present on | Meaning |
| --- | --- | --- |
| `yt_dlp` | all backends | yt-dlp (+ the bundled CPython on Android). Covers 1000+ sites, is the fallback for YouTube. |
| `newpipe` | Android only | NewPipeExtractor (GPL-3.0), the primary YouTube engine: search, metadata, direct stream URLs. Pure Java, so it is ready in milliseconds. |
| `ffmpeg` | Python backend only | Muxing, conversion, audio extraction. |
| `transcoder` | Android only | The native media pipeline: MediaMuxer for stream copies, Media3 `Transformer` for re-encodes, MediaCodec for PCM decode, and a bundled pure-Java LAME encoder for MP3. Replaces the 35 MB bundled `ffmpeg` binary, which failed to link on modern Android. |
| `ffprobe` / `js_runtime` | Python backend only | Media probing / JavaScript runtime for YouTube challenges. |

Each component carries an optional `state` field: `initialising`, `ready` or
`failed`. Clients must render `initialising` as in-progress, never as an error —
the mobile app unpacks its bundled payload on first launch:

```json
"yt_dlp": {
  "available": false,
  "version": null,
  "path": null,
  "note": "initialising: unpacking Python",
  "state": "initialising"
},
"newpipe": {
  "available": true,
  "version": "0.27.1",
  "path": null,
  "note": "primary YouTube engine · no Python required",
  "state": "ready"
}
```

`features.search`, `features.info` and `features.download` are `true` when
**either** engine is ready. `features.newpipe` and `features.yt_dlp` report the
individual engines.

Clients must read either `transcoder` (Android) or `ffmpeg` (desktop): both are
the same capability slot, and exactly one is present. A client that needs to
convert media checks `transcoder.available || ffmpeg.available`, and reports
`features.convert` — the server already combines them:

```json
"transcoder": {
  "available": true,
  "version": "MediaMuxer + Media3 1.11.1 + LAME 3.100",
  "path": null,
  "note": "hardware codecs; no bundled ffmpeg binary",
  "state": "ready"
}
```

Android also reports `features.mp3`, `features.mux` and `features.trim`, which
`convert` implies.

### Engine routing

| Request | Engine |
| --- | --- |
| `GET /api/search` for any query | NewPipeExtractor when ready and it returns results, else yt-dlp |
| `GET /api/search?type=channels\|playlists` | NewPipeExtractor only; yt-dlp reports `note` instead |
| `POST /api/download` `np:` format ids | NewPipeExtractor (stream copy) + the native pipeline, no Python |
| `GET /api/info` for a YouTube URL | NewPipeExtractor when ready, else yt-dlp |
| `GET /api/info` for any other URL | yt-dlp |
| `POST /api/download` with `format_id` `np:…` or omitted (YouTube URL) | NewPipeExtractor |
| `POST /api/download` with any other `format_id` | yt-dlp |

A yt-dlp failure never hides a working NewPipeExtractor and vice versa, so a
broken Python payload on a phone only costs non-YouTube support.

---

## 5. `GET /api/search`

Search videos without a YouTube Data API key. Backed by NewPipeExtractor's
YouTube search on Android and yt-dlp's `ytsearch` (InnerTube) everywhere else;
pasting a URL returns that single item.

**Query parameters**

| Name | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `q` | string | yes | — | Search terms, or a media URL. Blank strings are rejected |
| `limit` | int | no | `10` | `1…PRODL_SEARCH_LIMIT_MAX` (default max `50`) |
| `type` | string | no | `all` | Content filter: `all`, `videos`, `channels`, `playlists`. `video`/`channel`/`playlist` are accepted aliases |

**Response `200`**

```json
{
  "query": "lofi hip hop",
  "limit": 3,
  "count": 2,
  "type": "all",
  "note": null,
  "results": [
    {
      "id": "jfKfPfyJRdk",
      "title": "lofi hip hop radio 📚 beats to relax/study to",
      "url": "https://www.youtube.com/watch?v=jfKfPfyJRdk",
      "thumbnail": "https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg",
      "duration": 0,
      "uploader": "Lofi Girl",
      "view_count": 123456789,
      "is_live": true,
      "extractor": "youtube",
      "kind": "video",
      "item_count": null
    },
    {
      "id": "UCX6OQ3DkcsbYNE6H8uQQuVA",
      "title": "Lofi Girl",
      "url": "https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA",
      "thumbnail": "https://yt3.ggpht.com/…",
      "duration": null,
      "uploader": "Lofi Girl",
      "view_count": null,
      "is_live": false,
      "extractor": "youtube",
      "kind": "channel",
      "item_count": 14800000
    }
  ]
}
```

`count` equals `len(results)` and may be lower than `limit`. `type` echoes the
filter that was applied; `note` is set when the filter was accepted but could
not be honoured (see below).

**Result kinds**

| `kind` | Meaning | `item_count` |
| --- | --- | --- |
| `video` | A playable video. `duration` is seconds; `0` on live streams | — |
| `channel` | An uploader channel. Only returned by the on-device engine | Subscriber count |
| `playlist` | A playlist. Only returned by the on-device engine | Number of videos |

**Filter support**

| Engine | `all` | `videos` | `channels` | `playlists` |
| --- | --- | --- | --- | --- |
| NewPipeExtractor (Android) | yes | yes | yes | yes |
| yt-dlp `ytsearch` (desktop) | yes | yes | no | no |

An unsupported filter is not an error: the server answers `200` with
`results: []` and a `note` explaining that the on-device engine is needed.

Upstream failures return `502` with the extractor message.

```bash
curl "http://127.0.0.1:8000/api/search?q=lofi&limit=5"
```

---

## 6. `GET /api/info`

Full metadata, formats, subtitles, direct stream URLs, related videos and
optional SponsorBlock segments for one media URL.

**Query parameters**

| Name | Type | Required | Default | Notes |
| --- | --- | --- | --- | --- |
| `url` | string | yes | — | Any URL supported by yt-dlp (1000+ sites) |
| `sponsorblock` | bool | no | `false` | Fetch segments from `sponsor.ajay.app` |
| `categories` | string | no | `sponsor,intro,outro` | Comma-separated SponsorBlock categories |

**Response `200`**

```json
{
  "title": "Me at the zoo",
  "duration": 19,
  "thumbnail": "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg",
  "uploader": "jawed",
  "view_count": 350000000,
  "webpage_url": "https://www.youtube.com/watch?v=jNQXAC9IVRw",
  "extractor": "youtube",
  "id": "jNQXAC9IVRw",
  "channel": "jawed",
  "like_count": 1700000,
  "upload_date": "20050424",
  "description": "The first video on YouTube…",
  "is_live": false,
  "age_limit": 0,
  "formats": [
    {
      "format_id": "137",
      "ext": "mp4",
      "resolution": "1920x1080",
      "filesize": 20543402,
      "note": "1080p",
      "has_video": true,
      "has_audio": false,
      "vcodec": "avc1.640028",
      "acodec": "none",
      "fps": 25,
      "tbr": 4315.7,
      "abr": null,
      "protocol": "https",
      "direct_url": "https://rr1---sn-...googlevideo.com/videoplayback?..."
    },
    {
      "format_id": "140",
      "ext": "m4a",
      "resolution": "audio only",
      "filesize": 312771,
      "note": "medium",
      "has_video": false,
      "has_audio": true,
      "vcodec": "none",
      "acodec": "mp4a.40.2",
      "fps": null,
      "tbr": null,
      "abr": 129.5,
      "protocol": "https",
      "direct_url": "https://rr1---sn-...googlevideo.com/videoplayback?..."
    }
  ],
  "subtitles": [
    { "lang": "en", "lang_name": "English", "is_auto": false, "ext": "vtt" },
    { "lang": "ar", "lang_name": "Arabic", "is_auto": true, "ext": "vtt" }
  ],
  "best_video_url": "https://rr1---sn-...googlevideo.com/videoplayback?...",
  "best_audio_url": "https://rr1---sn-...googlevideo.com/videoplayback?...",
  "related": [
    {
      "id": "9bZkp7q19f0",
      "title": "PSY - GANGNAM STYLE(강남스타일) M/V",
      "url": "https://www.youtube.com/watch?v=9bZkp7q19f0",
      "thumbnail": "https://i.ytimg.com/vi/9bZkp7q19f0/hqdefault.jpg",
      "duration": 253,
      "uploader": "officialpsy",
      "view_count": 5200000000,
      "is_live": false,
      "extractor": "youtube",
      "kind": "video",
      "item_count": null
    }
  ],
  "sponsorblock": {
    "requested": true,
    "video_id": "jNQXAC9IVRw",
    "categories": ["sponsor", "intro", "outro"],
    "segments": [
      { "category": "intro", "start": 0.0, "end": 4.2, "action": "skip", "uuid": "b1c2…" }
    ],
    "error": null
  },
  "cookies_used": false
}
```

Notes

- `direct_url` / `best_video_url` / `best_audio_url` are CDN links intended for
  in-app playback (no ads, no player). They are short-lived and bound to the
  requesting IP — refresh by calling `/api/info` again.
- Single-file sources (generic direct links) return exactly one synthetic format
  with `format_id: "best"`.
- SponsorBlock failures never fail the request: `sponsorblock.error` carries the
  reason and `segments` is empty.
- `related` powers the Watch view. It is filled from the watch page itself (no
  extra request) by the on-device NewPipeExtractor engine, capped at 12 items;
  the yt-dlp engine returns `[]` because it does not fetch related items.
- `cookies_used` is `true` when a stored cookie file was attached to the request.

```bash
curl "http://127.0.0.1:8000/api/info?url=https://youtu.be/jNQXAC9IVRw&sponsorblock=true"
```

---

## 7. `POST /api/download`

Queue a download. Returns immediately with a task handle.

**Body** (`application/json`)

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `url` | string | — | **Required**, min length 4 |
| `format_id` | string | `null` | Exact `format_id` from `/api/info`: a yt-dlp selector (`"137+140"`, `"best"`) or a NewPipe id (`"np:video:137"`, `"np:muxed:18"`, `"np:audio-best"`). `np:` ids are served by NewPipeExtractor |
| `video_quality` | string | `null` | Target height, `"1080p"` / `"720"`; ignored when `format_id` is set |
| `audio_only` | bool | `false` | Extract audio and post-process with FFmpeg |
| `audio_format` | enum | `"mp3"` | `mp3` \| `m4a` \| `opus` \| `flac` \| `wav` |
| `audio_quality` | string | `"192"` | Target bitrate in kbps |
| `subtitles` | bool | `false` | Download subtitle files |
| `subtitle_langs` | string[] | `["en"]` | Language codes |
| `embed_subtitles` | bool | `false` | Mux subtitles into the container |
| `auto_subtitles` | bool | `true` | Include auto-generated tracks |
| `thumbnail` | bool | `false` | Download the cover image |
| `embed_thumbnail` | bool | `true` | Embed cover art (MP3/M4A) |
| `sponsorblock` | bool | `false` | Remove sponsor segments |
| `sponsorblock_action` | enum | `"cut"` | `cut` \| `mark` \| `skip` |
| `sponsorblock_categories` | string[] | `["sponsor","intro","outro"]` | |
| `use_cookies` | bool | `true` | Attach the stored cookie file if present |
| `max_filesize` | string | `null` | yt-dlp size limit, e.g. `"500M"` |
| `concurrent_fragments` | int | `1` | `1…16` |

Format selection precedence: `audio_only` → `bestaudio/best`; else `format_id`;
else `video_quality`; else `bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best`.

### NewPipe downloads (Android)

When the URL is YouTube and `format_id` is `np:…` or absent, the task is served
from the extractor's **direct stream URLs** instead of yt-dlp:

1. `/api/info` caches the resolved streams for ~10 minutes, so the download that
   follows does not scrape the page again.
2. A `np:muxed:` stream is a progressive MP4/WebM file: downloaded as-is.
3. A `np:video:` stream is video-only, so the best matching audio track is
   fetched too and joined with `ffmpeg -c copy`. The container is MP4 (AAC
   audio), WebM (Opus) or MKV when the pair cannot live in MP4 — never a
   re-encode.
4. `np:audio-best` / `audio_only` keeps the original track when the container
   already matches `audio_format`, otherwise FFmpeg extracts it.

`progress.percent` covers the whole task: a muxed download maps video to
0–70 % and audio to 70–100 %, then `postprocessing` reports the mux itself.
NewPipe downloads ignore `subtitles`, `thumbnail`, `sponsorblock_action` (cut)
and `use_cookies`: the extractor has no cookie jar and does not rewrite files.
Those options still apply to yt-dlp-served downloads.

**Response `202 Accepted`**

```json
{
  "task_id": "07925afa16774abeb12fbe554768ff4a",
  "kind": "download",
  "status": "queued",
  "created_at": 1795682700.41,
  "websocket_url": "/api/ws/progress/07925afa16774abeb12fbe554768ff4a",
  "sse_url": "/api/progress/07925afa16774abeb12fbe554768ff4a",
  "task_url": "/api/tasks/07925afa16774abeb12fbe554768ff4a",
  "file_url": "/api/files/07925afa16774abeb12fbe554768ff4a"
}
```

```bash
curl -X POST http://127.0.0.1:8000/api/download \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://youtu.be/jNQXAC9IVRw","audio_only":true,"audio_format":"mp3"}'
```

---

## 8. Progress: WebSocket, SSE, polling

Three interchangeable transports report the **same** event object. Clients
should prefer WebSocket, fall back to SSE, then to polling
(`frontend/js/api.js` does exactly this).

### 8.1 Progress event

```json
{
  "task_id": "07925afa16774abeb12fbe554768ff4a",
  "seq": 12,
  "status": "downloading",
  "percent": 45.2,
  "downloaded_bytes": 1048576,
  "total_bytes": 2320000,
  "speed": 1258291.2,
  "speed_str": "1.20MiB/s",
  "eta": 3,
  "elapsed": 1.8,
  "filename": "video.f137.mp4",
  "title": "Me at the zoo",
  "message": "Downloading video",
  "error": null,
  "timestamp": 1795682702.13,
  "file": null
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `task_id` | string | Present on streamed events |
| `seq` | int | Monotonic per task; use it to drop duplicates after a transport switch |
| `status` | enum | `queued` \| `downloading` \| `postprocessing` \| `converting` \| `finished` \| `error` \| `canceled` |
| `percent` | float? | `null` when unknown (e.g. live streams) |
| `downloaded_bytes` / `total_bytes` | int? | `total_bytes` may be an estimate |
| `speed` / `speed_str` | float? / string? | Bytes per second and its human form |
| `eta` | int? | Seconds remaining |
| `elapsed` | float? | Seconds since the task started |
| `filename` | string? | Current file being written |
| `title` | string? | Media title, when known |
| `message` | string? | Short human status, e.g. `Merging formats with ffmpeg` |
| `error` | string? | Populated when `status` is `error` |
| `file` | object? | Present on `finished`: `{filename, filesize, download_url, media_type}` |

### 8.2 `WS /api/ws/progress/{task_id}`

- On connect the server sends the **latest snapshot immediately**, so a client
  that connects late still renders the current state.
- Every message is one JSON progress event.
- `{"type":"ping"}` keep-alives are sent every 25 s; ignore them.
- The socket closes after a terminal event.
- Connecting to an unknown task sends `{"type":"error","error":"..."}` and closes.
- Connecting to an already finished task sends the terminal event and closes.

```js
const ws = new WebSocket("ws://127.0.0.1:8000/api/ws/progress/" + taskId);
ws.onmessage = (e) => {
  const event = JSON.parse(e.data);
  if (event.type === "ping") return;
  console.log(event.status, event.percent);
  if (["finished", "error", "canceled"].includes(event.status)) ws.close();
};
```

### 8.3 `GET /api/progress/{task_id}` (Server-Sent Events)

`Content-Type: text/event-stream`; each frame is `event: progress` with a
`data: <json>` line. Ends after the terminal event. `404` for unknown tasks.

```bash
curl -N http://127.0.0.1:8000/api/progress/07925afa16774abeb12fbe554768ff4a
```

### 8.4 `GET /api/tasks/{task_id}` (polling snapshot)

```json
{
  "task_id": "07925afa16774abeb12fbe554768ff4a",
  "kind": "download",
  "status": "downloading",
  "created_at": 1795682700.41,
  "updated_at": 1795682702.13,
  "error": null,
  "file": null,
  "last_event": { "...": "the last ProgressEvent" }
}
```

### 8.5 `POST /api/tasks/{task_id}/cancel`

Requests cancellation (best effort: yt-dlp and FFmpeg are stopped between
progress ticks). Returns the current snapshot (`200`), `404` if unknown.
Cancelling a finished task is a no-op.

---

## 9. `POST /api/convert`

Convert or trim media that already lives on the server (a finished download, or
a file inside the download directory).

**Body**

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `task_id` | string | `null` | Convert the artefact of a finished task |
| `path` | string | `null` | Filename inside the download directory (no absolute paths, no `..`) |
| `target_format` | enum | `"mp3"` | `mp3` \| `m4a` \| `opus` \| `wav` \| `flac` \| `mp4` \| `webm` \| `gif` |
| `audio_bitrate` | string | `"192k"` | `128k` \| `192k` \| `256k` \| `320k` |
| `video_crf` | int | `23` | `0…51` (lower = better quality) |
| `start` | float | `null` | Trim start, seconds (`>= 0`) |
| `end` | float | `null` | Trim end, seconds (`> start`) |

Exactly one of `task_id` / `path` must be supplied (`400` otherwise). A file with
no audio stream cannot be converted to an audio format — the task ends with
`status: "error"` and FFmpeg's message in `error`.

**Response `202 Accepted`** — same shape as `/api/download` with
`"kind": "convert"`.

```bash
curl -X POST http://127.0.0.1:8000/api/convert \
  -H 'Content-Type: application/json' \
  -d '{"task_id":"07925afa16774abeb12fbe554768ff4a","target_format":"mp3","start":0,"end":30}'
```

---

## 10. `POST /api/convert/upload`

Same as `/api/convert` but accepts a local file (`multipart/form-data`), so a
desktop/WebView client can convert without a prior download.

| Part | Type | Required | Notes |
| --- | --- | --- | --- |
| `file` | file | yes | Any media ffmpeg can read; capped at `PRODL_MAX_UPLOAD_MB` |
| `target_format` | text | no | Default `mp3` |
| `audio_bitrate` | text | no | Default `192k` |
| `video_crf` | text | no | Default `23` |
| `start` / `end` | text | no | Trim window in seconds |

Uploads are written to the uploads directory, converted into the download
directory, and the upload is deleted afterwards. Response `202` as above.

```bash
curl -X POST http://127.0.0.1:8000/api/convert/upload \
  -F "file=@song.m4a" -F "target_format=mp3" -F "audio_bitrate=320k"
```

---

## 11. Files: download & delete

### `GET /api/files/{task_id}`

Streams the finished artefact with
`Content-Disposition: attachment; filename*=utf-8''<name>` and a best-effort
`Content-Type`. `404` if the task is unknown or not finished. Files are only
served from the server download directory.

```bash
curl -OJ http://127.0.0.1:8000/api/files/07925afa16774abeb12fbe554768ff4a
```

### `DELETE /api/files/{task_id}`

Deletes the artefact and forgets the task. `204 No Content` on success,
`404` if unknown.

---

## 12. `/api/cookies`

Cookie support keeps age-restricted videos and private playlists working while
staying on-device: the file is written with `0600` permissions inside the local
data directory and is never returned to any client.

### `GET /api/cookies`

```json
{ "configured": true, "cookie_count": 2, "updated_at": "2026-11-26T09:56:14+00:00", "storage": "local-file" }
```

### `POST /api/cookies`

Body: `{ "content": "<Netscape cookie file text>" }`

- Must contain at least one valid `domain\tflag\tpath\tsecure\texpiry\tname\tvalue` row.
- Comments (`# …`) and blank lines are allowed; a missing
  `# Netscape HTTP Cookie File` header is added automatically.
- `200` with the new status, or `422` naming the offending **row number** —
  cookie values are never echoed back in error messages.
- `422` if `content` exceeds 2 MiB (the JSON body is validated like any other field).

```bash
curl -X POST http://127.0.0.1:8000/api/cookies \
  -H 'Content-Type: application/json' \
  --data-binary '{"content":"# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t1893456000\tSID\tabc\n"}'
```

### `POST /api/cookies/upload`

`multipart/form-data` with a `file` part holding the same Netscape text — handy
for "import cookies.txt" buttons. Same validation and response as above, plus
`413` when the uploaded file exceeds 2 MiB.

### `DELETE /api/cookies`

Deletes the stored file and returns the status (`configured: false`).

---

## 13. `GET /api` (service index)

Self-describing entry point, useful for clients that only know the host.

```json
{
  "name": "ProDL",
  "version": "2.1.0",
  "api_version": "1.1.0",
  "docs": "/docs",
  "openapi": "/openapi.json",
  "endpoints": {
    "health": "/api/health",
    "search": "/api/search?q=<query>&limit=<n>",
    "info": "/api/info?url=<url>",
    "download": "POST /api/download",
    "convert": "POST /api/convert",
    "convert_upload": "POST /api/convert/upload",
    "task": "/api/tasks/{task_id}",
    "cancel": "POST /api/tasks/{task_id}/cancel",
    "progress_ws": "WS /api/ws/progress/{task_id}",
    "progress_sse": "/api/progress/{task_id}",
    "file": "/api/files/{task_id}",
    "cookies": "/api/cookies"
  }
}
```

---

## 14. Legacy endpoints

Kept so pre-2.0 clients keep working; new code should use the `/api` routes.

| Endpoint | Behaviour |
| --- | --- |
| `GET /health` | `{"status": "ok"}` |
| `POST /info` | Body `{"url": "..."}` → flat `VideoInfo` (no SponsorBlock fields) |
| `POST /download` | Body `{url, format_id, audio_only}` → streams the finished file as an attachment. **Blocks** until the download completes; prefer `/api/download` + progress for anything user-facing |

`GET /` returns a small JSON banner when `PRODL_SERVE_FRONTEND=0`.

---

## 15. Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `PRODL_HOST` | `127.0.0.1` | Bind address for `python -m app` / `app.main:main()` |
| `PRODL_PORT` | `8000` | Bind port |
| `PRODL_DATA_DIR` | `~/.local/share/ProDL` (Linux), `~/Library/Application Support/ProDL` (macOS), `%LOCALAPPDATA%\ProDL` (Windows) | Root for all app data |
| `PRODL_DOWNLOAD_DIR` | `<data>/downloads` | Finished files |
| `PRODL_UPLOADS_DIR` | `<data>/uploads` | Temporary conversion uploads |
| `PRODL_COOKIES_FILE` | `<data>/cookies.txt` | Netscape cookie store |
| `PRODL_CORS_ORIGINS` | `*` | Comma-separated allowed origins |
| `PRODL_SERVE_FRONTEND` | `1` | Serve `frontend/` from the API origin |
| `PRODL_FRONTEND_DIR` | `<repo>/frontend` | Static SPA location |
| `PRODL_SEARCH_LIMIT` / `PRODL_SEARCH_LIMIT_MAX` | `10` / `50` | Search page size and ceiling |
| `PRODL_MAX_WORKERS` | `4` | Worker threads for downloads/conversions |
| `PRODL_MAX_TASKS` | `200` | Task registry cap (oldest are evicted) |
| `PRODL_TASK_TTL` | `3600` | Seconds a finished task stays queryable |
| `PRODL_MAX_UPLOAD_MB` | `512` | Upload ceiling for `/api/convert/upload` |
| `PRODL_SPONSORBLOCK_API` | `https://sponsor.ajay.app/api/skipSegments` | SponsorBlock endpoint |
| `PRODL_SPONSORBLOCK_CATEGORIES` | `sponsor,intro,outro` | Default categories |
| `PRODL_FFMPEG` / `PRODL_FFPROBE` | auto-detected | Explicit binary paths |
| `PRODL_DENO` / `PRODL_NODE` / `PRODL_BUN` / `PRODL_QUICKJS` | auto-detected | JavaScript runtimes for yt-dlp challenges |
| `PRODL_YTDLP_REMOTE_COMPONENTS` | *(empty)* | **Opt-in**: `ejs:github` lets yt-dlp download a challenge solver. Off by default because it fetches code |

---

## 16. Data, storage & privacy

- Everything happens on the device that runs the server: no account, no
  telemetry, no third-party analytics. The only outbound calls are to the media
  site you asked for and (optionally) `sponsor.ajay.app`.
- Downloads land in `PRODL_DOWNLOAD_DIR`; uploads are deleted after conversion.
- Cookies are stored with `0600` permissions in `PRODL_COOKIES_FILE` and are
  only ever read by yt-dlp. They are never included in an API response.
- The task registry is in-memory: restarting the server forgets task ids, but
  files on disk remain.
- When exposing the server beyond `127.0.0.1`, set `PRODL_CORS_ORIGINS` to your
  client origin(s) and put it behind a reverse proxy with TLS.
