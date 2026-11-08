// GitHub's release-asset CDN answered 504 for hours on 2026-11-08, and Maven
// Central answers some Kotlin artifacts with a redirect to exactly that host
// (kotlin-compiler-embeddable came from github.com/JetBrains/kotlin/releases).
// The mirror proxies Central, so it is asked first; drop it once the CDN is
// healthy again.
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "OlcrtcClient"
include(":app")
