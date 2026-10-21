import java.util.Properties

pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // LiveKit's client SDK depends on a fork of audioswitch published on JitPack (only that group is allowed).
    maven("https://jitpack.io") { content { includeGroup("com.github.davidliu") } }
  }
}

rootProject.name = "Samvad"

// Pure Kotlin/JVM logic: builds and tests anywhere with a JDK.
include(":core")

// The Android app needs the Android SDK. It is included whenever an SDK is configured
// (local.properties sdk.dir or ANDROID_HOME), and skipped otherwise so :core still builds.
val localProps = Properties().apply {
  val f = file("local.properties")
  if (f.exists()) f.inputStream().use { load(it) }
}
val sdkDir = localProps.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (sdkDir != null && file(sdkDir).exists()) {
  include(":app")
} else {
  logger.warn("Samvad: no Android SDK found (local.properties sdk.dir / ANDROID_HOME); building :core only.")
}
