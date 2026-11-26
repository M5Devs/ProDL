# ProDL for Android

ProDL runs **entirely on the phone**. The APK embeds the same static SPA as the
web build, runs the same JSON API on `127.0.0.1`, and streams media with the
phone's own IP address. No remote server, no accounts, no analytics, no ads, and
no Google Play Services / Firebase / ad SDK anywhere in the dependency graph.

```
┌───────────────────────────── app.prodl (single APK) ─────────────────────────────┐
│                                                                                  │
│  MainActivity ── WebView ──► assets/frontend/*  (the shared SPA, byte identical)  │
│       │                        │  window.__PRODL_API_BASE__ = http://127.0.0.1:P │
│       │                        ▼                                                 │
│       │                     fetch/WebSocket ──► LocalServer (Ktor + CIO)         │
│       │                                              │                           │
│       │                                              ├── MediaService ─► YtDlp    │
│       │                                              │        (bundled python + │
│       │                                              │         yt-dlp + ffmpeg)  │
│       │                                              ├── CookieStore (private)   │
│       │                                              └── SponsorBlockClient      │
│       │                                                                          │
│       ├── NativeBridge (window.ProDLNative) ─► PlayerActivity / MediaStore export│
│       └── ServerService (foreground) keeps the API + downloads alive             │
│                                                                                  │
│  PlaybackService (Media3) ── ExoPlayer, lockscreen controls, SponsorBlock skip    │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## Layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/app/prodl/core/` | Platform-light domain code: API models, yt-dlp JSON mapping, progress parsing, Netscape cookies, format selection, ffmpeg arguments, cookie store, SponsorBlock client, MediaStore export |
| `app/src/main/java/app/prodl/server/` | Ktor server, routes, task manager, download/convert orchestration |
| `app/src/main/java/app/prodl/bridge/` | `@JavascriptInterface` bridge exposed as `window.ProDLNative` |
| `app/src/main/java/app/prodl/playback/` | Media3 session service, PiP player activity, SponsorBlock skipper |
| `app/src/main/java/app/prodl/service/` | Foreground service that owns the server lifetime |
| `app/src/test/java/app/prodl/` | JVM unit tests (no Android device required) |

`assets/frontend/` is **generated**: `syncFrontendAssets` copies `../frontend`
into the APK at build time. Nothing is duplicated into version control, so the
HTML/CSS/JS can never drift between web, desktop and Android.

## API parity with `API_SPEC.md`

The Android build serves the same paths as the Python backend, so the SPA needs
no platform branch:

| `API_SPEC.md` | Android implementation |
| --- | --- |
| `GET /api`, `/api/health`, `/api/openapi.json` | `ApiRoutes` + `LocalServer.health()` (reports bundled yt-dlp/ffmpeg/QuickJS, cookie state, feature flags) |
| `GET /api/search` | `yt-dlp "ytsearchN:q" --flat-playlist`, no API key |
| `GET /api/info` | `yt-dlp -J` → formats, subtitles, `best_video_url`/`best_audio_url` (ad-free direct stream), optional SponsorBlock segments |
| `POST /api/download` | `MediaService.startDownload` → `202` + `task_id` |
| `WS /api/ws/progress/{task_id}`, SSE `GET /api/progress/{task_id}` | Same `ProgressEvent` schema; both close on the terminal event, `{"type":"ping"}` keep-alives |
| `GET/POST/DELETE /api/cookies`, `POST /api/cookies/upload` | `CookieStore` in app-private storage (never backed up) |
| `POST /api/convert`, `POST /api/convert/upload` | `FfmpegRunner` (bundled ffmpeg) with trim + `out_time` progress |
| `GET/DELETE /api/files/{task_id}` | Streams the finished file from the app's download directory |

Path-relative `download_url`/`websocket_url`/`sse_url` values and `{"detail": …}`
errors match the specification, so the same `frontend/js/api.js` drives both
backends.

## Android-only surface

The shared SPA does not know about Android. Anything platform-specific is
opt-in through `window.ProDLNative`:

| Method | Effect |
| --- | --- |
| `apiBase()`, `platform()`, `version()`, `serverStatus()` | Shell introspection |
| `playMedia(url, title, thumbnail, audioOnly, sponsorblockJson)` | Native Media3 playback (direct stream URL → ad-free) with PiP; SponsorBlock segments are skipped automatically |
| `openYouTubeLogin()` | In-app Google sign-in WebView; cookies are converted to Netscape format and stored on-device only |
| `saveToDownloads(taskId)` | Publishes to `Downloads/ProDL` via MediaStore (no storage permission on Android 10+) |
| `checkPermissions()`, `requestPermissions()` | Notification permission state |
| `toast(message)` | Native toast |

Download links (`/api/files/<id>`) are intercepted by the WebView so "Save link"
also lands in `Downloads/ProDL` without a network round-trip.

## Build

```bash
# Requirements: JDK 17–21, Android SDK (platform 36, build-tools 36)
export ANDROID_HOME="$HOME/android-sdk"      # or set sdk.dir in android/local.properties
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

cd android
./gradlew :app:testDebugUnitTest   # 39 JVM tests
./gradlew :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk (~186 MB)
```

The APK is large because it is self-contained: bundled Python, yt-dlp, ffmpeg
and aria2c for `arm64-v8a`, `armeabi-v7a` and `x86_64`. Drop ABIs in
`defaultConfig.ndk.abiFilters` to shrink it. Minification is opt-in:
`PRODL_MINIFY=1 ./gradlew :app:assembleRelease`.

## Tests

`./gradlew :app:testDebugUnitTest` covers the parts that are easy to get wrong
and are shared with the Python implementation's behaviour:

- yt-dlp progress lines (percent, sizes, speed, ETA, destinations, merge/extract)
- yt-dlp `-J` → `API_SPEC.md` models (formats, audio/video flags, subtitles, direct URLs, search entries)
- format selector output for audio-only, explicit format ids, height caps, cookies, SponsorBlock, subtitles
- ffmpeg argument construction, trimming, `out_time` progress
- Netscape cookie parsing/serialisation, dedupe, cookie-header conversion
- SponsorBlock skip decisions

## Privacy & security

- The API binds **127.0.0.1 only** on an ephemeral port; nothing on the LAN can reach it.
- CORS is open because the SPA is a `file://` origin inside the WebView; loopback binding is the actual access control.
- Cookies live in `filesDir/prodl/cookies.txt`, excluded from cloud backup and device transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
- No telemetry, no crash reporting, no third-party network calls except YouTube/SponsorBlock on the user's behalf.
- `usesCleartextTraffic` exists purely for `http://127.0.0.1`.

## Dependencies and licensing

ProDL is AGPLv3. The Android shell adds:

| Dependency | License | Notes |
| --- | --- | --- |
| `youtubedl-android` (junkfood02 fork) | GPLv3 | bundles yt-dlp (Unlicense), CPython, ffmpeg, aria2c |
| AndroidX core/activity/lifecycle, Media3 | Apache-2.0 | no Play Services needed |
| Ktor (CIO, WebSockets, CORS, ContentNegotiation) | Apache-2.0 | |
| kotlinx-serialization / coroutines | Apache-2.0 | |

No `com.google.android.gms`, no Firebase, no crash/ad SDKs: `./gradlew :app:dependencies`
shows only AndroidX, Media3, Ktor, Kotlin and the yt-dlp wrapper.

## Known limits

- YouTube sometimes demands a signed-in session ("Sign in to confirm you're not a bot"); the in-app **Link YouTube account** flow fixes that with cookies.
- A single foreground notification covers the server; per-task notifications are not implemented (progress is in the UI and over the API).
- Playlists are supported by yt-dlp but the SPA only exposes single-video flows today.
- `x86` (32-bit emulators) is not shipped.
