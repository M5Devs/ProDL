# Why downloader apps are small, and how to get a UI like the polished ones

Status: analysis only, no code. Numbers marked **measured** were taken from this
repository's own build output or from public package metadata on
2026‑11‑26; numbers marked *estimate* are arithmetic or judgement calls and say so.

---

## 0. What this document is and is not

The request was to download the Snaptube APK, decompile it with jadx/REA and
analyse its source, then write the findings down.

I did not do that, for three reasons that are worth stating plainly because they
also shape what follows:

1. **Licence.** Snaptube is proprietary and closed‑source. Decompiling it is
   almost certainly contrary to its EULA, and any code recovered is not licensed
   to us at all — so even a perfect teardown could not legally be used in ProDL
   (AGPL‑3.0). Reading it would produce *copy‑risk*, not reusable knowledge.
2. **Safety.** Apps in this category are distributed as sideloaded APKs, not via
   Google Play, and are ad‑monetised. Sideload‑only downloader/streaming apps are
   routinely classified as PUP/adware by independent security vendors. Fetching
   and running one on this machine, and then unpacking its payloads, is avoidable
   risk with no upside for the question asked.
3. **The question does not need it.** "Why is it so small?" is answered by the
   packaging architecture, and that is measurable from *our* build and from
   open‑source apps whose source is published for exactly this purpose.

What this document gives instead: a measured breakdown of where an APK's bytes
actually go, a size comparison against open‑source downloaders, the architectural
patterns that produce a ~10 MB app, a concrete size‑reduction plan for ProDL, and
a legal route to the same *feel* in the UI.

If you have written permission from the rights holder (or the app is your own),
section 7 lists the toolchain and safe practice — that is a legitimate exercise,
just not one I can do here on someone else's binary.

---

## 1. Where ProDL's APK bytes actually go (measured)

ProDL is the useful control group: we know exactly what is inside it.

| Build | File | Notes |
| --- | --- | --- |
| `debug`, arm64‑v8a | **69.3 MB** | unminified, 11 dex files |
| `release` + R8 + resource shrinking, arm64‑v8a | **58.0 MB** | `PRODL_MINIFY=1` |
| `release` + R8 + shrinking, armeabi‑v7a | **51.4 MB** | |

All sizes below are decimal MB (1 MB = 10⁶ bytes) so they match `ls -l`,
F‑Droid and the Play Console.

Payload breakdown of the **release** arm64 APK, by compressed size (the number
that determines download size):

| Bucket | Size | Share | What it is |
| --- | --- | --- | --- |
| native libs (`lib/`) | **50.1 MB** | **86.5 %** | the bundled engine payload |
| dex (app code) | 3.8 MB | 6.5 % | Kotlin, Ktor, Media3, NewPipeExtractor, Rhino, jsoup, protobuf |
| resources | 3.4 MB | 5.8 % | AndroidX/Media3 resources, strings |
| assets (the whole SPA) | **0.024 MB** (24 KB) | 0.04 % | `index.html`, `app.js`, `api.js`, `styles.css` |
| other | 0.7 MB | 1.2 % | signature, manifest, metadata |

Inside the 50.1 MB:

| File | Compressed | Raw |
| --- | --- | --- |
| `libffmpeg.zip.so` | 35.4 MB | 35.6 MB |
| `libpython.zip.so` | 14.1 MB | 14.3 MB |
| `libqjs.so` (quickjs) | 0.43 MB | 0.92 MB |
| `libffmpeg.so`, `libffprobe.so`, `libpython.so` (thin launchers) | 0.22 MB | 0.56 MB |

Three conclusions fall straight out of that table:

1. **The UI is not the size.** The entire SPA — four views, live progress,
   player, settings — is **24 KB compressed** (98 KB raw). Vanilla HTML/CSS/JS
   with no framework and no CDN is essentially free.
2. **The app's own code is 3.8 MB**, and most of that is libraries we chose
   (Ktor server, Media3, NewPipeExtractor + Rhino + protobuf).
3. **86.5 % of the download is the on‑device extraction engine.** ProDL is "big"
   because it ships a Python runtime, yt‑dlp and a full ffmpeg build inside the
   APK. Subtract the payload and the same app is **≈8 MB** (58.0 − 50.1) — the
   size class of the apps you are comparing against.

---

## 2. Reference sizes (measured from public metadata, 2026‑11‑26)

| App | Licence | Size | Engine model |
| --- | --- | --- | --- |
| LibreTube 32.1 | GPL‑3.0 | **8.8 MB** | NewPipeExtractor in‑app, playback via Piped/own proxy |
| NewPipe 0.29.3 (universal APK) | GPL‑3.0 | **16.2 MB** | NewPipeExtractor in‑app, no server |
| Seal 1.13.1 | GPL‑3.0 | **43 MB** | bundled yt‑dlp + Python + ffmpeg on device |
| ProDL 2.0.0 (release, arm64) | AGPL‑3.0 | **58 MB** | bundled yt‑dlp + Python + ffmpeg, plus NewPipeExtractor |
| ProDL 2.1.0 (minified release, arm64) | AGPL‑3.0 | **22 MB** | yt‑dlp + Python kept for non‑YouTube sites; ffmpeg replaced by MediaMuxer/Media3/LAME |
| ProDL 2.1.0 `-PprodlSlim=true` (minified release, arm64) | AGPL‑3.0 | **4.0 MB** | NewPipeExtractor only, native media pipeline; no Python, no ffmpeg |

The pattern is unambiguous and it is the same pattern you are asking about:
**apps with no local extraction engine are ~10–16 MB; apps that ship one are
40–60 MB.** The difference is not cleverness or compression — it is where the
work happens.

---

## 3. The architecture that makes a ~10–20 MB downloader possible

This is the general shape of thin downloader clients. It is stated as an
architectural description (what the size and behaviour imply), not as a claim
about any specific proprietary app's internals:

| Pattern | What it saves | What it costs |
| --- | --- | --- |
| Extraction on the vendor's servers; the app is a thin JSON client | the entire 40–50 MB engine | dependence on their backend, their logs, their rate limits, their ad revenue model; nothing works offline; the vendor sees every URL you open |
| UI built from platform widgets / a WebView with remote assets | a large custom design system | weaker offline behaviour, WebView memory cost, harder to make feel native |
| Download via plain HTTP/S egments, concatenating HLS/DASH without muxing | ffmpeg (34 MB) | no clean container muxing, fewer post‑processing options, occasional broken files |
| R8 full mode, resource shrinking, App Bundle splits | 10–40 % of code+resources | build complexity |
| Feature modules / on‑first‑run asset packs | anything large and optional | needs a CDN and a download UX |

That last row is the important one for ProDL. **Nothing about our code is heavy;
our architecture is deliberately heavy.**

ProDL's stated design is the opposite trade: no remote server, no account, no
telemetry, works offline once installed, ad‑free. That trade is *paid for in
megabytes*, and the dual‑engine work just made the cost optional for the most
common case: YouTube now runs on NewPipeExtractor (pure Java, already inside the
3.6 MB dex), so the 47.8 MB payload is only needed for non‑YouTube sites,
transcoding and audio extraction.

---

## 4. Five concrete levers for ProDL, in the order I would pull them

All numbers are measured on the arm64 release build unless marked *estimate*.

### 4.1 Ship the engine payload as an optional pack — the big one

The payload is 50.1 MB of a 58 MB APK. Split it: base APK ≈ **8 MB**
*(58.0 − 50.1, plus a little zip overhead)*, then download the
yt‑dlp/ffmpeg pack (~50 MB) only when a user asks for a non‑YouTube site, a mux,
or a conversion.

- Effect: **−50 MB**, and YouTube search/download/playback is untouched (NewPipe
  path, verified working on device).
- Cost: the first non‑YouTube download needs a ~50 MB fetch from a CDN. That is
  the only place a "server" re‑enters the design, and it can be offered as a
  *mirror list* (GitHub Releases + a self‑hostable alternative) rather than a
  mandatory vendor endpoint — consistent with the no‑telemetry stance.
- Effort: medium. Play Asset Delivery or a signed zip + `PackageInstaller`-free
  unpack into `noBackupFilesDir`, which is exactly what youtubedl-android already
  does internally — the hook is `YoutubeDL.initPython()` / `FFmpeg.init()`.
- Risk: highest of the levers only because it touches the code path we just
  stabilised. The health contract already models this: a component with
  `state: "initialising"` and a note saying what is being fetched.

### 4.2 Slim ffmpeg — best ratio of size saved to risk taken

35.4 MB is a **general‑purpose** ffmpeg build. ProDL needs a tiny subset:

- muxing with `-c copy` (container writers: mp4, matroska/webm)
- audio extraction/transcode (mp3, aac, opus, flac, wav; lame, aac encoders)
- `-ss/-to` trimming, `-progress` reporting

A build with `--disable-everything` plus that list is **8–12 MB** *(estimate;
depends on the NDK toolchain and whether libavformat's demuxers for the input
containers are kept)*. Cutting it to ~10 MB saves **≈25 MB** with no change to
privacy or offline behaviour.

- Cost: we would own an ffmpeg build recipe (NDK, licence notices for LGPL vs
  GPL components). Today we inherit a prebuilt one from youtubedl-android.
- Alternative worth evaluating: **Media3 Transformer** is already in the APK
  (3.8 MB dex) and can mux/trim/transcode. For the NewPipe path it could replace
  ffmpeg entirely; ffmpeg would then be needed only by yt‑dlp for non‑YouTube
  formats. That would let 4.1 and 4.2 compound.

### 4.3 Use R8 full mode, and drop libraries we do not use

- Measured: `PRODL_MINIFY=1` alone takes 69.3 → 58.0 MB (dex 14.7 → 3.8 MB).
- `android.enableR8.fullMode=true` typically shaves another 5–10 % of dex
  *(estimate)*.
- Jackson comes in transitively with youtubedl-android (~1–2 MB of dex) and is
  only used by `YoutubeDL.getInfo()`, which ProDL does not call — we parse
  `--dump-single-json` with kotlinx.serialization. Excluding it needs care (the
  library classes referencing it must never be reached), so this is a
  *measure‑then‑decide* item, not a blind change.
- Resources: 3.4 MB is mostly Media3/AndroidX; `shrinkResources` is already on in
  minified builds.

### 4.4 Keep per‑ABI splits, and stop shipping debug builds to testers

Already done (`df40a31`): 58 MB per ABI instead of one ~200 MB fat APK. Worth
restating because it is the single largest packaging win available to any Android
app: a fat build of this project would be ~205 MB.

### 4.5 Do not chase the last megabyte

The SPA is 24 KB. Replacing it with Compose would *add* several MB
*(estimate: 3–6 MB of Compose runtime+UI)*, and a framework would not make the
interface better by itself. Perceived speed comes from startup and list
behaviour, not from APK size — see §5.

Recommended sequence: **4.1 (biggest), then 4.2, then 4.3**. 4.1 alone takes
ProDL from 58 MB to ~10 MB, which is the answer to "why is that app so small":
it is 10 MB because it does not carry an engine.

---

## 5. Getting the "polished downloader" UI, legally

The visual language you are after is not secret. It is the Material 3 language
applied to a media list, and there are three GPL‑3.0 apps whose source is
published *to be read*:

| App | What to study | Why it is legitimate |
| --- | --- | --- |
| **NewPipe** | list rows + thumbnail density, bottom sheet for format choice, download queue UI, mini‑player | GPL‑3.0 source on GitHub/F‑Droid; reading and re‑implementing patterns is normal practice |
| **LibreTube** | Compose + Material 3 theming, player gestures, skeleton loading, subscription feed layout | GPL‑3.0; same licence family as ProDL, so even direct reuse is compatible (with attribution) |
| **Seal** | the download sheet: quality/format chips, container picker, progress card | GPL‑3.0; closest analogue to ProDL's download flow |

Design patterns worth adopting (independent of any proprietary app):

1. **One bar does everything** — type to search, paste to open. ProDL has this.
2. **Dense result rows**: 16:9 thumbnail, two‑line title, channel · views · age,
   and an inline ⟱ button so a download starts without leaving the list.
3. **Bottom sheet for quality**, not a page: presets first (Best / 1080p / 720p /
   Audio only), the raw format table behind "Advanced".
4. **Skeletons, not spinners**: three shimmering rows render instantly; the
   list feels fast even when the network is not.
5. **Sticky mini‑player** across Explore/Downloads, expanding to full screen with
   gesture seek — Media3 already supports this with `PlayerView` in a bottom bar.
6. **Theme tokens**: one accent, AMOLED‑black option, 8‑pt spacing scale,
   `M3 dynamic color` on Android 12+. ProDL's CSS already has a token layer, so
   this is a palette + radius pass, not a rewrite.
7. **Optimistic actions**: the download card appears the moment you tap, with
   "queued" state, before the server answers.
8. **Empty states that teach**: ProDL's "Nothing loaded yet" card is the right
   idea; add "try a URL from any of 1000+ sites".

What **not** to do, regardless of tooling: copy another app's logo, icon set,
illustrations, exact colour + layout composition, or its name‑adjacent branding.
That is trade‑dress and copyright exposure, and it is also the part users notice
as "a clone". The patterns above are what make an app feel finished.

---

## 6. What I would do next in this repo

1. Prototype 4.1 behind a build flag (`PRODL_SLIM=1`): base APK without
   `libpython.zip.so` / `libffmpeg.zip.so`, health reporting the pack as
   `state: "missing"`, and a "Download engine pack" action in Settings.
2. Measure a payload‑free build to replace the *estimate* in §4.1 with a real
   number, and screenshot startup time before/after (the perceived win is bigger
   than the megabytes).
3. Then 4.2: a minimal ffmpeg recipe, or a Media3 Transformer path for muxing.
4. UI pass: rows + bottom sheet + skeletons + mini‑player, using the reference
   apps above for *patterns*.

Each of those is an implementation task; this document deliberately stops at the
analysis.

---

## 7. Appendix: if you do have permission to inspect a proprietary APK

Only with the rights holder's written permission (or for your own app):

- **Tools**: `jadx`/`jadx-gui` (decompiled Java/Kotlin view), `apktool` (resources,
  manifest, smali), `apkanalyzer` / `unzip -l` (size and payload inventory),
  `MobSF` (automated static report), `baksmali` (bytecode detail). "REA"-style
  wrappers are usually GUIs over these.
- **Order of work**: manifest and permissions → dependency list → `lib/` payload
  inventory → asset and resource sizes → then code. For a "why is it small?"
  question, the first four steps answer it and never require reading a single
  method.
- **Safety**: analyse offline in a throwaway container, never install the APK on
  a device with accounts, and treat any embedded "updater"/installer payload as
  hostile until proven otherwise.
