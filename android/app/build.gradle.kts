import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release number; each APK gets `baseVersionCode * 10 + abiCode` so Play
// Store / F-Droid see unique, ordered version codes per architecture.
// Single source of truth for the extractor version: the dependency and the
// value `/api/health` reports both come from here.
val newPipeExtractorVersion = "v0.27.1"

val baseVersionCode = 20000
val abiVersionCodes = linkedMapOf(
    "arm64-v8a" to 1,
    "armeabi-v7a" to 2,
    "x86_64" to 3,
    "x86" to 4,
)

// ---------------------------------------------------------------------------
// Shared UI: the APK ships the exact same SPA as the web build.  Nothing is
// copied into version control — `frontend/` is synced into a generated assets
// directory at build time, so HTML/CSS/JS can never drift between targets.
// ---------------------------------------------------------------------------
val frontendSource = rootProject.layout.projectDirectory.dir("../frontend")
val frontendAssets = layout.buildDirectory.dir("generated/frontendAssets")

val syncFrontendAssets by tasks.registering(Sync::class) {
    group = "build"
    description = "Copy the shared frontend/ SPA into the APK assets."
    from(frontendSource) {
        exclude("**/*.map", "**/.DS_Store", "**/*.md")
    }
    into(frontendAssets.map { it.dir("frontend") })
    doFirst {
        require(frontendSource.file("index.html").asFile.isFile) {
            "frontend/index.html not found — the shared SPA is required for the Android build"
        }
    }
}

android {
    namespace = "app.prodl"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.prodl"
        minSdk = 26
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "2.0.0"
        buildConfigField("String", "NEWPIPE_VERSION", "\"${newPipeExtractorVersion.removePrefix("v")}\"")
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
    }

    sourceSets.getByName("main") {
        assets.srcDir(frontendAssets)
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

// Assets must exist before the APK packaging step runs.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(syncFrontendAssets)
}
tasks.named("preBuild") {
    dependsOn(syncFrontendAssets)
}

dependencies {
    val ktorVersion = "3.6.2"
    val media3Version = "1.11.1"
    val youtubedlVersion = "0.18.1"
    val okhttpVersion = "4.12.0"

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
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
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Primary YouTube engine: NewPipeExtractor (GPL-3.0). Pure Java, no native
    // payload, no Python — it scrapes YouTube/PeerTube/SoundCloud directly.
    implementation("com.github.teamnewpipe:NewPipeExtractor:$newPipeExtractorVersion")

    // HTTP for NewPipeExtractor's Downloader and for saving direct media
    // streams (the same OkHttp + UA setup LibreTube ships).
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    // Fallback engine for the 1000+ sites NewPipeExtractor does not cover:
    // yt-dlp + ffmpeg, bundled and executed on-device. aria2c is deliberately
    // left out: nothing passes `--external-downloader`, and it costs ~7 MB/ABI.
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
