import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

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
        versionCode = 20000
        versionName = "2.0.0"

        // yt-dlp/ffmpeg binaries are shipped per ABI; x86 (32-bit) is dropped.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
    }

    packaging {
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

    // yt-dlp + ffmpeg + aria2c, bundled and executed on-device.
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlVersion")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:$youtubedlVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
