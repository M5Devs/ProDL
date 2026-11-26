// Top-level build file. Plugin versions are declared once here and applied in :app.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // Compose compiler ships with Kotlin 2.x; the plugin only pins the pairing.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
