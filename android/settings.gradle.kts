pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor and its nanojson fork are published through JitPack.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.teamnewpipe.newpipeextractor")
                includeGroup("com.github.TeamNewPipe")
                includeGroup("com.github.TeamNewPipe.NewPipeExtractor")
            }
        }
    }
}

rootProject.name = "ProDL"
include(":app")
