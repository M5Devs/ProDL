# Third-party notices — ProDL for Android

ProDL is licensed under the GNU Affero General Public License v3.0
(see [../LICENSE](../LICENSE)). The Android application bundles or links the
components below. Nothing here is shipped as a proprietary blob or requires a
Google account, Play Services, Firebase or an ad network.

## Bundled at runtime (inside the APK)

| Component | Version | Licence | Source |
| --- | --- | --- | --- |
| yt-dlp | pinned by the wrapper | The Unlicense | <https://github.com/yt-dlp/yt-dlp> |
| CPython | 3.x, packaged for Android | PSF License 2.0 | <https://www.python.org/> |
| ffmpeg | build from the wrapper | LGPL-2.1-or-later (default build) | <https://ffmpeg.org/> |
| aria2c | optional external downloader | GPL-2.0 | <https://aria2.github.io/> |
| youtubedl-android (`io.github.junkfood02`) | `0.18.1` | GPL-3.0 | <https://github.com/JunkFood02/youtubedl-android> |
| QuickJS (js runtime for yt-dlp challenges) | as packaged by the wrapper | MIT | <https://bellard.org/quickjs/> |

Because the APK ships GPL-3.0 code (youtubedl-android, aria2c) and is itself
AGPL-3.0, the complete corresponding source of this app — including the exact
`android/` sources and the `frontend/` bundle it embeds — is published in this
repository, as required.

## Linked libraries

| Component | Licence |
| --- | --- |
| **NewPipeExtractor** (`com.github.teamnewpipe`, JitPack) | **GPL-3.0-or-later** — <https://github.com/TeamNewPipe/NewPipeExtractor> |
| AndroidX Core, Activity, Lifecycle, Media3 (ExoPlayer, Session, UI) | Apache-2.0 |
| Ktor server (CIO, WebSockets, CORS, ContentNegotiation, PartialContent) | Apache-2.0 |
| kotlinx-serialization, kotlinx-coroutines | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| Jackson (YAML/JSON for yt-dlp's config) | Apache-2.0 |
| jsoup (HTML parsing, via NewPipeExtractor) | MIT |
| Mozilla Rhino (JavaScript interpreter, via NewPipeExtractor) | MPL-2.0 |
| protobuf-javalite (YouTube response protos, via NewPipeExtractor) | BSD-3-Clause |
| nanojson (via NewPipeExtractor) | Apache-2.0 / MIT |
| desugar_jdk_libs_nio (Java 8+ APIs on API 26) | Apache-2.0 |

### NewPipeExtractor (GPL-3.0-or-later)

ProDL's primary YouTube engine is
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), the library
behind NewPipe and LibreTube, used unmodified from its JitPack release
(`v0.27.1`). It is GPL-3.0-or-later; combined with this AGPL-3.0 application the
whole work stays free software, and the complete corresponding source of the
Android app (including this directory) is published in this repository.

The integration follows the pattern shipped by
[LibreTube](https://github.com/libre-tube/LibreTube): `NewPipe.init(downloader)`
with an OkHttp-backed `Downloader`, and
[JunkFood02/Seal](https://github.com/JunkFood02/Seal) for the bundled
yt-dlp/ffmpeg handling. No NewPipeExtractor source is patched.

## Network services used on the user's behalf

| Service | Purpose |
| --- | --- |
| YouTube (`youtube.com`, `googlevideo.com` CDN) | metadata, search, media streams |
| `sponsor.ajay.app` | SponsorBlock segment lookup (optional, can be disabled) |

No data is sent anywhere else; there is no telemetry, crash reporting or
analytics SDK in the application.

## Trademarks

YouTube is a trademark of Google LLC. ProDL is not affiliated with, endorsed by
or sponsored by Google or YouTube.
