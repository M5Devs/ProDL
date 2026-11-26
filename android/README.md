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
| `app/src/main/java/app/prodl/core/` | Platform-light domain code: API models, yt-dlp JSON mapping, progress parsing, Netscape cookies, format selection, the native media pipeline (`MediaPipeline`, `Media3Converter`, `NativeMuxer`, `AudioDecoder`, `Mp3Writer`, `WavWriter`, `MediaTargets`), cookie store, SponsorBlock client, MediaStore export |
| `app/src/main/java/app/prodl/server/` | Ktor server, routes, task manager, download/convert orchestration |
| `app/src/main/java/app/prodl/bridge/` | `@JavascriptInterface` bridge exposed as `window.ProDLNative` |
| `app/src/main/java/app/prodl/playback/` | Media3 session service, PiP player activity, SponsorBlock skipper |
| `app/src/main/java/app/prodl/service/` | Foreground service that owns the server lifetime |
| `app/src/test/java/app/prodl/` | JVM unit tests (no Android device required) |

`assets/frontend/` is **generated**: `syncFrontendAssets` copies `../frontend`
into the APK at build time. Nothing is duplicated into version control, so the
HTML/CSS/JS can never drift between web, desktop and Android.

### Why there is no bundled ffmpeg

Earlier builds shipped `youtubedl-android:ffmpeg` (35.6 MB per architecture).
On current Android releases its unpacked binary fails to start with
`CANNOT LINK EXECUTABLE` (missing `libav*.so` dependencies on modern NDK
layouts), and yt-dlp then hangs on "unpacking ffmpeg" — so every mux, audio
extraction and conversion path was dead, and startup took ~30 s longer.

It was replaced by a native pipeline that uses the codecs already on the phone:

| Job | Implementation |
| --- | --- |
| Join video + audio (same container/codecs) | `MediaMuxer` stream copy (`NativeMuxer`) — no re-encode, ~1 s |
| Join when codecs disagree (e.g. Opus in MP4) | Media3 `Transformer` (`Media3Converter`), hardware codecs |
| Trim / re-container / extract audio | Media3 `Transformer` with clipping + `setAudioMimeType` |
| MP3 encode | bundled [jump3r](https://github.com/Sciss/jump3r) (pure-Java LAME 3.100) + `Mp3Writer` |
| WAV output | `WavWriter` (PCM header + raw samples) |
| Any other PCM work | `MediaCodec` decode (`AudioDecoder`) |

`/api/health` now reports a `transcoder` component instead of `ffmpeg`
(`available`, `version`, `note`), and `features.convert` is derived from it.
Nothing is unpacked at first launch, so a conversion can start immediately.

### Why the Python payload is still packaged "the old way"

youtubedl-android ships its payload as `libpython.zip.so`
and unpacks them on first launch. That only works when the `.so` files are
extracted to `nativeLibraryDir` at install time, so the build sets both:

```kotlin
packaging { jniLibs { useLegacyPackaging = true } }   // compress + extract
android:extractNativeLibs="true"                      // in <application>
```

With the modern (aligned, uncompressed) default the library cannot find a
runnable interpreter, `YoutubeDL.init()` throws, and `/api/health` reports
`yt-dlp failed` with `features.search: false` — search and download are dead
even though the APK looks fine. (The NewPipe engine and the native transcoder
keep working regardless, which is why they are the primary path.)

`YtDlp.initAsync()` initialises yt-dlp in a `try` block with
`applicationContext`, is idempotent across Activity re-creation, and stores the
exact exception message for the health payload:

- `yt_dlp.note` → `initialisation failed: <exception>` (search/download broken)
- `status` is `ok` when an extraction engine, the media pipeline and the
  download directory are all ready, otherwise `degraded` (per `API_SPEC.md`)
- `YtDlp` caches only successful lookups, so a Python payload that appears after
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
  `MediaMuxer` (stream copy). Only when the pair is illegal for the container
  (Opus in MP4, VP8 in MP4) does Media3 re-encode the offending track — the
  plan comes from `MediaTargets.muxPlan(videoMime, audioMime)`, which is unit
  tested for every codec pair the extractor can hand us;
- audio-only downloads keep the original track when the container matches, else
  the pipeline writes the requested format: **MP3** (jump3r/LAME), M4A (AAC via
  Media3) or WAV. `opus`/`flac` requests fall back to MP3 instead of failing.

Progress maps both tracks into one percentage (video 0–70 %, audio 70–100 %)
and then reports the mux, using the existing WebSocket/SSE pipeline.

## API parity with `API_SPEC.md`

The Android build serves the same paths as the Python backend, so the SPA needs
no platform branch:

| `API_SPEC.md` | Android implementation |
| --- | --- |
| `GET /api`, `/api/health`, `/api/openapi.json` | `ApiRoutes` + `LocalServer.health()` (reports yt-dlp, `transcoder`, QuickJS, cookie state, feature flags) |
| `GET /api/search` | NewPipeExtractor (with `type=all\|videos\|channels\|playlists`), else `yt-dlp "ytsearchN:q" --flat-playlist`; no API key |
| `GET /api/info` | NewPipeExtractor or `yt-dlp -J` → formats, subtitles, `best_video_url`/`best_audio_url` (ad-free direct stream), `related` videos, optional SponsorBlock segments |
| `POST /api/download` | `MediaService.startDownload` → `202` + `task_id` |
| `WS /api/ws/progress/{task_id}`, SSE `GET /api/progress/{task_id}` | Same `ProgressEvent` schema; both close on the terminal event, `{"type":"ping"}` keep-alives |
| `GET/POST/DELETE /api/cookies`, `POST /api/cookies/upload` | `CookieStore` in app-private storage (never backed up) |
| `POST /api/convert`, `POST /api/convert/upload` | `MediaPipeline` (Media3 `Transformer` + LAME) with trim + progress events |
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

Measured on 2.1.0, per architecture (minified release):

| Build | Size | Engine |
| --- | --- | --- |
| default (debug) | ~47 MB | NewPipeExtractor + CPython/yt-dlp + native pipeline |
| default (`PRODL_MINIFY=1`) | **22 MB** | same, shrunk |
| `-PprodlSlim=true` (`PRODL_MINIFY=1`) | **4.0 MB** | NewPipeExtractor + native pipeline, no Python, no ffmpeg |

Removing the 35.6 MB ffmpeg payload cut the debug APK from 67 MB to 33 MB
(slim) / 47 MB (full); R8 takes the release builds to 4.0 MB / 22 MB.
`-PprodlUniversal` builds
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
- `MediaTargets` mux decisions for every codec pair (AVC/AAC, VP9/Opus, VP8/AAC, …), resolution labels (`1080p60`, `144p48`), size estimates
- a real MP3 encode: jump3r LAME is pure Java, so the test encodes a sine wave,
  parses the MPEG frame header back (128 kbps, 44.1 kHz, stereo) and checks the
  byte count; WAV headers are verified byte by byte
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
| `youtubedl-android` (junkfood02 fork) | GPLv3 | bundles yt-dlp (Unlicense) and CPython; the ffmpeg artifact is no longer used |
| `de.sciss:jump3r` | LGPL-2.1+ | pure-Java LAME 3.100 MP3 encoder, statically linked into the APK (see [NOTICE.md](NOTICE.md)) |
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
