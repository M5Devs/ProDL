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
│       │                                              ├── MediaService ─► NewPipe │
│       │                                              │        │  (YouTube, pure  │
│       │                                              │        │   Java, no Python)│
│       │                                              │        └─► YtDlp (bundled  │
│       │                                              │             python, yt-dlp│
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

### Why the binaries are packaged "the old way"

youtubedl-android ships its payload as `libpython.zip.so` / `libffmpeg.zip.so`
and unpacks them on first launch. That only works when the `.so` files are
extracted to `nativeLibraryDir` at install time, so the build sets both:

```kotlin
packaging { jniLibs { useLegacyPackaging = true } }   // compress + extract
android:extractNativeLibs="true"                      // in <application>
```

With the modern (aligned, uncompressed) default the library cannot find a
runnable interpreter, `YoutubeDL.init()` throws, and `/api/health` reports
`yt-dlp failed` / `no ffmpeg` with `features.convert: false` — search and
download are dead even though the APK looks fine.

`YtDlp.initAsync()` initialises FFmpeg and yt-dlp in separate `try` blocks with
`applicationContext`, is idempotent across Activity re-creation, and stores the
exact exception message for the health payload:

- `yt_dlp.note` → `initialisation failed: <exception>` (search/download broken)
- `ffmpeg.note` → `initialisation failed: <exception>` (conversion disabled)
- `status` is `ok` only when both engines and the download dir are ready,
  otherwise `degraded` (per `API_SPEC.md`)
- `FfmpegRunner` caches only successful lookups, so a binary that appears after
  the first launch is picked up by the next health check.

## Two extraction engines

YouTube and the rest of the web are extracted by different code, so one broken
engine never takes the app down:

| Engine | Serves | Cost at start-up |
| --- | --- | --- |
| **NewPipeExtractor** `v0.27.1` (GPL-3.0) | YouTube: search, `/api/info`, direct stream URLs, downloads | none — pure Java, ready in milliseconds |
| **yt-dlp** (bundled CPython) | everything else: 1000+ sites, cookies, SponsorBlock cuts, subtitles, format merging | unpacks ~17 MB of Python on first launch |

Routing lives in `MediaService` and is documented in `API_SPEC.md` §4:
NewPipe answers YouTube requests first, yt-dlp answers everything else and
serves as the YouTube fallback when YouTube changes something the pinned
extractor version cannot parse. A `format_id` of `np:…` can only be served by
NewPipe, any other selector only by yt-dlp.

`/api/health` reports both engines separately (`newpipe`, `yt_dlp`) with a
`state` of `initialising` / `ready` / `failed`, so the UI can show "starting…"
while the payload unpacks instead of a false "failed". Search works as soon as
`newpipe` is ready, even if yt-dlp never starts.

The startup code is deliberately bounded: unpacking has a 180 s deadline and the
interpreter probe (`yt-dlp --version`) has 30 s, and whatever fails is named in
the health note (`unpacking the bundled Python failed: …`). With no deadline a
stuck unpack previously left the card on "initialising" forever with no error to
report.

### Downloads without Python

NewPipe streams are downloaded straight from `googlevideo.com` with OkHttp:

- progressive (`np:muxed:`) streams are saved as-is (MP4/WebM);
- video-only (`np:video:`) streams get the best matching audio track, joined by
  `ffmpeg -c copy` into MP4, WebM or MKV (chosen from the codec pair, never a
  re-encode);
- audio-only downloads keep the original track when the container matches, else
  FFmpeg extracts to MP3/M4A/Opus/FLAC/WAV.

Progress maps both tracks into one percentage (video 0–70 %, audio 70–100 %)
and then reports the mux, using the existing WebSocket/SSE pipeline.

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
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # -> one APK per CPU architecture
```

`assembleDebug` produces four split APKs instead of one fat 217 MB APK:

| APK | Size (debug) | Install if… |
| --- | --- | --- |
| `app-arm64-v8a-debug.apk` | ~68 MB | **Almost all modern phones** (64-bit ARM, Android 8+) |
| `app-armeabi-v7a-debug.apk` | ~61 MB | Older/cheap 32-bit phones |
| `app-x86_64-debug.apk` | ~71 MB | Standard emulator (`x86_64` system image) |
| `app-x86-debug.apk` | ~67 MB | Legacy 32-bit emulators, Chromebooks with 32-bit images |

Install the one that matches your device:
`adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Picking the
wrong one fails at install with `INSTALL_FAILED_NO_MATCHING_ABIS`.
Not sure which you need? `adb shell getprop ro.product.cpu.abi` prints it.

Each APK carries exactly one `lib/<abi>/` tree and gets its own version code,
`baseVersionCode * 10 + abiCode` (`200001` arm64, `200002` armv7, `200003`
x86_64, `200004` x86) so stores and `adb` treat them as distinct builds. Bump
`baseVersionCode` in `app/build.gradle.kts` once per release.

The APKs are still ~60–70 MB because they are self-contained: bundled CPython
(~14 MB), ffmpeg (~36 MB) and yt-dlp per architecture. `-PprodlUniversal` builds
the single fat APK as well; minification is opt-in:
`PRODL_MINIFY=1 ./gradlew :app:assembleRelease`.

CI uploads each architecture as its own artifact (`prodl-android-arm64-v8a`,
`prodl-android-armeabi-v7a`, `prodl-android-x86_64`, `prodl-android-x86`) so you
can download just the APK for your device from the Actions run.

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
shows only AndroidX, Media3, Ktor, Kotlin and the yt-dlp wrapper. Full
attribution for the bundled binaries lives in [NOTICE.md](NOTICE.md).

## Known limits

- YouTube sometimes demands a signed-in session ("Sign in to confirm you're not a bot"); the in-app **Link YouTube account** flow fixes that with cookies.
- A single foreground notification covers the server; per-task notifications are not implemented (progress is in the UI and over the API).
- Playlists are supported by yt-dlp but the SPA only exposes single-video flows today.
