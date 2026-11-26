import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release number; each APK gets `baseVersionCode * 10 + abiCode` so Play
// Store / F-Droid see unique, ordered version codes per architecture.
// Single source of truth for the extractor version: the dependency and the
// value `/api/health` reports both come from here.
val newPipeExtractorVersion = "v0.27.1"
val jump3rVersion = "1.0.5"

// `-PprodlSlim=true` drops the bundled Python/yt-dlp payload too, leaving a
// NewPipeExtractor-only APK (~8-12 MB) that still searches, plays and downloads
// YouTube. The default build keeps yt-dlp for the 1000+ other sites.
val prodlSlim = (findProperty("prodlSlim") as String?)?.toBoolean() ?: false

val baseVersionCode = 20010
val abiVersionCodes = linkedMapOf(
    "arm64-v8a" to 1,
    "armeabi-v7a" to 2,
    "x86_64" to 3,
    "x86" to 4,
)

android {
    namespace = "app.prodl"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.prodl"
        minSdk = 26
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "2.1.0"
        buildConfigField("String", "NEWPIPE_VERSION", "\"${newPipeExtractorVersion.removePrefix("v")}\"")
        buildConfigField("boolean", "SLIM", "$prodlSlim")
    }

    // One APK per CPU architecture: the bundled Python/yt-dlp/ffmpeg native
    // libraries are the bulk of the install, so a fat APK wastes ~130 MB.
    // `-PprodlUniversal` additionally builds the fat APK.
    splits {
        abi {
            isEnable = true
            reset()
            // Literals (not abiVersionCodes.keys) so lint can see ABI coverage; the
            // two lists must stay identical — a mismatch fails the build below.
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = providers.gradleProperty("prodlUniversal").isPresent
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets.getByName("main") {
        // The slim build has no youtubedl-android on the classpath, so the real
        // wrapper is swapped for a stub with the same surface.
        // The yt-dlp wrapper is the only source that touches youtubedl-android,
        // so it lives in a variant directory instead of src/main: the slim build
        // has no such dependency and gets a stub with the same surface.
        java.srcDir(if (prodlSlim) "src/slim/java" else "src/full/java")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            // Jackson (used by youtubedl-android) and Ktor are reflection-heavy;
            // minification is opt-in via PRODL_MINIFY=1.
            isMinifyEnabled = (System.getenv("PRODL_MINIFY") == "1")
            isShrinkResources = (System.getenv("PRODL_MINIFY") == "1")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time / java.util.stream / NIO APIs that only
        // exist on API 33+; desugar_jdk_libs_nio backports them to minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        // youtubedl-android ships its payload as `libpython.zip.so` /
        // `libffmpeg.zip.so`. With the default (aligned, uncompressed) packaging
        // the library cannot find a runnable binary on real devices, so yt-dlp
        // init fails and /api/health reports no ffmpeg. Legacy packaging copies
        // them into nativeLibraryDir, where the library extracts and exec's them.
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/INDEX.LIST",
            "META-INF/*.kotlin_module",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

// Every split APK needs its own versionCode, otherwise stores/`adb install`
// treat them as the same build. `base * 10 + abiCode` keeps ordering by release.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // The universal APK (`-PprodlUniversal`) has no ABI filter; it keeps
            // the base versionCode.
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
                ?: return@forEach
            val abiCode = abiVersionCodes[abi]
                ?: error("No versionCode offset for ABI '$abi'; update abiVersionCodes")
            output.versionCode?.set(baseVersionCode * 10 + abiCode)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val ktorVersion = "3.6.2"
    val media3Version = "1.11.1"
    val youtubedlVersion = "0.18.1"
    val okhttpVersion = "4.12.0"
    val composeBom = "2025.09.01"
    val coilVersion = "3.3.0"

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // Native UI: Jetpack Compose + Material 3. The WebView SPA is gone from
    // Android; `frontend/` still serves the Web/Desktop builds.
    implementation(platform("androidx.compose:compose-bom:$composeBom"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    // Async thumbnails with memory+disk cache, so the feed scrolls smoothly.
    implementation("io.coil-kt.coil3:coil-compose:$coilVersion")
    implementation("io.coil-kt.coil3:coil-network-okhttp:$coilVersion")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")

    // Embedded API server (bound to 127.0.0.1 only).
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-partial-content:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Native playback: background audio, MediaSession controls, PiP.
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Primary YouTube engine: NewPipeExtractor (GPL-3.0). Pure Java, no native
    // payload, no Python — it scrapes YouTube/PeerTube/SoundCloud directly.
    implementation("com.github.teamnewpipe:NewPipeExtractor:$newPipeExtractorVersion")

    // HTTP for NewPipeExtractor's Downloader and for saving direct media
    // streams (the same OkHttp + UA setup LibreTube ships).
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Muxing, trimming and transcoding without any bundled binary:
    // MediaMuxer copies tracks, Media3 Transformer uses the device's hardware
    // codecs, and jump3r is a pure-Java LAME port for MP3 (MediaCodec has no
    // MP3 encoder). Together they replace the 35 MB ffmpeg payload.
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("de.sciss:jump3r:$jump3rVersion")

    // Fallback engine for the 1000+ sites NewPipeExtractor does not cover:
    // yt-dlp on bundled CPython. No ffmpeg module: post-processing is native.
    if (!prodlSlim) {
        implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlVersion")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
