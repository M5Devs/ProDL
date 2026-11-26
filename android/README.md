# ProDL for Android

ProDL runs **entirely on the phone**, with a **native Jetpack Compose UI**. There
is no WebView in the app shell: the screens call the Kotlin engines in-process,
so playback, search and downloads do not depend on a local HTTP server being up.
No remote server, no accounts, no analytics, no ads, and no Google Play Services
/ Firebase / ad SDK anywhere in the dependency graph.

```
┌────────────────────────────── app.prodl (single APK) ─────────────────────────────┐
│                                                                                   │
│  MainActivity (ComponentActivity) ──► ProDLApp ──► Navigation Compose             │
│                                          │                                        │
│      ┌───────────────────────────────────┼────────────────────────────┐           │
│      ▼               ▼                   ▼                ▼           ▼           │
│  Discover        Watch             Browse            Downloads     Convert        │
│  (search/feeds)  (player+related)  (channel/playlist) (task cards)  (ffmpeg-free) │
│      │               │                   │                │           │           │
│      └───────────────┴─────────┬─────────┴────────────────┴───────────┘           │
│                                ▼                                                  │
│                     ViewModels (StateFlow)                                        │
│                                │                                                  │
│   ┌────────────────────────────┼──────────────────────────────┐                   │
│   ▼                            ▼                              ▼                   │
│ NewPipeEngine            TaskManager                    MediaPipeline             │
│ (search/info/kiosk/      (task list, progress)          (MediaMuxer, Media3       │
│  channel/playlist)              │                        Transformer, LAME, WAV)  │
│   │                             ▼                              │                   │
│   │                      MediaService ──► YtDlp (bundled python, slim builds off) │
│   │                                                           │                   │
│   ├──► PlaybackService (Media3 ExoPlayer, HLS, lockscreen, SponsorBlock skip)     │
│   └──► CookieLoginActivity (isolated WebView, Google sign-in only)                │
│                                                                                   │
│  Optional: LocalServer (Ktor + CIO) serves the same JSON API to the bundled       │
│  web UI, a desktop client or a future Android local-server shell.                 │
└───────────────────────────────────────────────────────────────────────────────────┘
```

## Layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/app/prodl/core/` | Platform-light domain code: API models, yt-dlp JSON mapping, progress parsing, Netscape cookies, format selection, the native media pipeline (`MediaPipeline`, `Media3Converter`, `NativeMuxer`, `AudioDecoder`, `Mp3Writer`, `WavWriter`, `MediaTargets`), cookie store, SponsorBlock client, MediaStore export, and `InputParser` (link/search routing) |
| `app/src/main/java/app/prodl/ui/` | Compose UI: `ProDLApp` (nav shell), `theme/`, `components/`, `screens/` (Discover, Watch, Browse, Downloads, Convert, Settings, FormatSheet), `AppViewModels`/`TaskViewModels`, `PlayerHost` (Media3 session + fullscreen) |
| `app/src/main/java/app/prodl/server/` | Ktor server, routes, task manager, download/convert orchestration (used by the optional loopback API and the bundled web UI) |
| `app/src/main/java/app/prodl/playback/` | Media3 session service, PiP player activity, SponsorBlock skipper |
| `app/src/main/java/app/prodl/service/` | Foreground service that owns the server lifetime |
| `app/src/test/java/app/prodl/` | JVM unit tests (no Android device required) |

The APK ships **no HTML at all**: `frontend/` belongs to the Web and Desktop
builds. The optional loopback server answers `GET /` with the API index, so a
browser, a desktop client or a future Android local-server shell can drive the
phone over `http://127.0.0.1:<port>`.

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

## Native UI, engine-backed

Every screen is Compose and talks to the engines directly — no HTTP hop, no JS
bridge, no asset loading:

| Screen | Engines it drives |
| --- | --- |
| **Discover** | `NewPipeEngine.search()` with the all/videos/channels/playlists filter, `NewPipeEngine.kiosk()` for the Live / Gaming / Podcasts feeds, and `InputParser` so a pasted link jumps straight to the right screen |
| **Watch** | `NewPipeEngine.info()` → `playback_url` (progressive, else the HLS/DASH manifest), related videos, SponsorBlock segments handed to `PlaybackService`, `FormatSheet` → `DownloadsViewModel.startDownload()` |
| **Browse** | `NewPipeEngine.channel()` (the extractor's *videos* tab) and `playlist()` |
| **Downloads** | `TaskManager` `StateFlow` (percent, speed, ETA, output path) with cancel and "save to Downloads" via MediaStore |
| **Convert** | `ConvertViewModel` → `MediaPipeline` (MP3/M4A/WAV, trim) with live progress |
| **Settings** | Engine health (`/api/health` equivalent computed in-process), cookie linking via `CookieLoginActivity`, notification permission, and the optional loopback server toggle |

Playback detail worth knowing: YouTube often returns **no progressive stream**,
only separate video/audio tracks. A video-only URL would play silently, so
`NewPipeMapper.info()` exposes `playback_url` = progressive → HLS → DASH, and
the player sets `MimeTypes.APPLICATION_M3U8` explicitly because the manifest URL
has no `.m3u8` suffix for ExoPlayer to sniff.

`CookieLoginActivity` keeps the only WebView in the app: Google's sign-in page
refuses to run outside a real browser engine. Cookies are converted to Netscape
format and stored on-device only.

## API parity with `API_SPEC.md`

The loopback server is optional now, but it still serves the same paths as the
Python backend, so any client — the web build pointed at the phone, a desktop
app, a future local-server shell — needs no platform branch:

| `API_SPEC.md` | Android implementation |
| --- | --- |
| `GET /api`, `/api/health`, `/api/openapi.json` | `ApiRoutes` + `LocalServer.health()` (reports yt-dlp, `transcoder`, QuickJS, cookie state, feature flags) |
| `GET /api/search` | NewPipeExtractor (with `type=all\|videos\|channels\|playlists`), else `yt-dlp "ytsearchN:q" --flat-playlist`; no API key |
| `GET /api/info` | NewPipeExtractor or `yt-dlp -J` → formats, subtitles, `best_video_url`/`best_audio_url` (ad-free direct stream), `playback_url`/`hls_url`/`dash_url`, `related` videos, optional SponsorBlock segments |
| `POST /api/download` | `MediaService.startDownload` → `202` + `task_id` |
| `WS /api/ws/progress/{task_id}`, SSE `GET /api/progress/{task_id}` | Same `ProgressEvent` schema; both close on the terminal event, `{"type":"ping"}` keep-alives |
| `GET/POST/DELETE /api/cookies`, `POST /api/cookies/upload` | `CookieStore` in app-private storage (never backed up) |
| `POST /api/convert`, `POST /api/convert/upload` | `MediaPipeline` (Media3 `Transformer` + LAME) with trim + progress events |
| `GET/DELETE /api/files/{task_id}` | Streams the finished file from the app's download directory |

Path-relative `download_url`/`websocket_url`/`sse_url` values and `{"detail": …}`
errors match the specification, so `frontend/js/api.js` works against both
backends unchanged. The native UI never touches the server: Settings →
*Local server (optional)* only starts it for remote control.

## Build

```bash
# Requirements: JDK 17–21, Android SDK (platform 36, build-tools 36)
export ANDROID_HOME="$HOME/android-sdk"      # or set sdk.dir in android/local.properties
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

cd android
./gradlew :app:testDebugUnitTest   # 68 JVM tests (5 live ones self-skip)
./gradlew :app:lintDebug
./gradlew :app:assembleDebug       # -> one APK per CPU architecture
```

`assembleDebug` produces four split APKs instead of one fat APK:

| APK | Size (debug) | Install if… |
| --- | --- | --- |
| `app-arm64-v8a-debug.apk` | ~64 MB | **Almost all modern phones** (64-bit ARM, Android 8+) |
| `app-armeabi-v7a-debug.apk` | ~61 MB | Older/cheap 32-bit phones |
| `app-x86_64-debug.apk` | ~64 MB | Standard emulator (`x86_64` system image) |
| `app-x86-debug.apk` | ~64 MB | Legacy 32-bit emulators, Chromebooks with 32-bit images |

Debug builds are unminified: Compose alone adds ~90 MB of dex, so these numbers
say nothing about release size (see the table below).

Install the one that matches your device:
`adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`. Picking the
wrong one fails at install with `INSTALL_FAILED_NO_MATCHING_ABIS`.
Not sure which you need? `adb shell getprop ro.product.cpu.abi` prints it.

Each APK carries exactly one `lib/<abi>/` tree and gets its own version code,
`baseVersionCode * 10 + abiCode` (`200101` arm64, `200102` armv7, `200103`
x86_64, `200104` x86) so stores and `adb` treat them as distinct builds. Bump
`baseVersionCode` in `app/build.gradle.kts` once per release.

Measured on 2.1.0, arm64-v8a:

| Build | Size | Engine |
| --- | --- | --- |
| default (debug) | ~64 MB | NewPipeExtractor + CPython/yt-dlp + native pipeline |
| `-PprodlSlim=true` (debug) | ~48 MB | NewPipeExtractor + native pipeline, no Python |
| default (`PRODL_MINIFY=1`, release) | **22.8 MB** | same as default, R8-shrunk |
| `-PprodlSlim=true` (`PRODL_MINIFY=1`) | **5.2 MB** | no Python, R8-shrunk |

Removing the 35.6 MB ffmpeg payload is what made the slim variant possible at
all; the Compose UI that replaced the WebView SPA costs a couple of MB after R8.
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
- `InputParser`: search vs. video vs. channel vs. playlist routing for every URL
  shape users paste (`watch?v=`, `youtu.be`, `/shorts/`, `/live/`, `@handle`,
  `/channel/`, `/playlist?list=`, bare ids), plus scheme-less input
- `playback_url` selection: progressive wins, HLS/DASH beat a silent video-only URL

The extractor calls are covered by an **opt-in** live suite, because they need
YouTube to answer:

```bash
PRODL_LIVE=1 ./gradlew :app:testDebugUnitTest --tests '*NewPipeApiTest*'
```

It exercises search, every kiosk id the Discover chips offer, the channel
*videos* tab, a playlist taken from that channel, and the watch page (formats,
subtitles, HLS/DASH). Without `PRODL_LIVE=1` those five tests skip themselves, so
CI stays hermetic.

## Privacy & security

- The native UI needs no network listener at all; it calls the engines in-process.
- The optional API binds **127.0.0.1 only** on an ephemeral port; nothing on the LAN can reach it.
- CORS is open because the bundled SPA is a `file://` origin; loopback binding is the actual access control.
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
- Extractor 0.27.1's plain **Trending** kiosk is broken upstream (YouTube dropped the
  "Now/Videos" tabs it parses) and the music/movies kiosks return nothing, so
  Discover offers Live / Gaming / Podcasts instead. `DiscoverViewModel` still
  walks the feed list until one returns items.
- Download notifications are per-task; the server (when enabled) keeps one
  foreground notification of its own.
- Playlist *playback* is single-video: the playlist screen lists videos, and
  each one opens in Watch.
