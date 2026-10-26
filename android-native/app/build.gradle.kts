import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.services)
  alias(libs.plugins.ksp)
}

// Debug builds talk to the Firebase Emulator Suite when -Psamvad.useEmulators=true
// (host 10.0.2.2 reaches the development machine from the Android emulator).
val useEmulators = (findProperty("samvad.useEmulators") as String?)?.toBoolean() ?: false
val emulatorHost = (findProperty("samvad.emulatorHost") as String?) ?: "10.0.2.2"

// End-to-end encryption (libsignal, AGPL-3.0). On in debug builds. In release it is OFF unless -Psamvad.e2ee=true,
// so a release that must exclude the encrypted-chat code paths (see docs/security/E2EE_DECISION.md, release gate)
// still builds. NOTE: this switches the feature off at run time; libsignal is still linked. To ship a build with no
// AGPL code at all, also remove the libsignal-android dependency (docs/security/E2EE_DESIGN.md, section Release).
val e2eeInRelease = (findProperty("samvad.e2ee") as String?)?.toBoolean() ?: false

android {
  namespace = "com.samvad.messenger"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.samvad.messenger"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "FUNCTIONS_REGION", "\"asia-south1\"")
    buildConfigField("int", "CONSENT_VERSION", "1")
    buildConfigField("String", "TERMS_URL", "\"https://samvad-messenger.web.app/terms\"")
    buildConfigField("String", "PRIVACY_URL", "\"https://samvad-messenger.web.app/privacy\"")
  }

  // Release signing comes only from environment variables or Gradle properties and is absent by default,
  // so the release output is unsigned unless the owner supplies them (see docs/RELEASE_CHECKLIST.md).
  fun signingValue(name: String): String? =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }
  val releaseStoreFile = signingValue("SAMVAD_RELEASE_STORE_FILE")
  val releaseSigningConfigured = releaseStoreFile != null &&
    signingValue("SAMVAD_RELEASE_STORE_PASSWORD") != null &&
    signingValue("SAMVAD_RELEASE_KEY_ALIAS") != null &&
    signingValue("SAMVAD_RELEASE_KEY_PASSWORD") != null
  if (releaseSigningConfigured) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseStoreFile!!)
        storePassword = signingValue("SAMVAD_RELEASE_STORE_PASSWORD")
        keyAlias = signingValue("SAMVAD_RELEASE_KEY_ALIAS")
        keyPassword = signingValue("SAMVAD_RELEASE_KEY_PASSWORD")
      }
    }
  }

  // -Psamvad.abiSplits=true builds one release APK per ABI instead of one APK holding both ABIs.
  val abiSplits = (findProperty("samvad.abiSplits") as String?)?.toBoolean() ?: false
  if (abiSplits) {
    splits {
      abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a")
        isUniversalApk = false
      }
    }
  }

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      // Real phones (arm64) and the x86_64 emulator only; 32-bit ARM ships in release.
      if (!abiSplits) ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
      buildConfigField("boolean", "USE_EMULATORS", useEmulators.toString())
      buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
      buildConfigField("boolean", "E2EE_ENABLED", "true")
      buildConfigField("boolean", "VAULT_BACKUP_ENABLED", "false")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
      if (!abiSplits) ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      buildConfigField("boolean", "USE_EMULATORS", "false")
      buildConfigField("String", "EMULATOR_HOST", "\"\"")
      buildConfigField("boolean", "E2EE_ENABLED", e2eeInRelease.toString())
      buildConfigField("boolean", "VAULT_BACKUP_ENABLED", "false")
    }
  }

  packaging {
    // libsignal-android also ships desktop JVM natives (macOS .dylib and Windows .dll, about 110 MB
    // uncompressed) as plain Java resources at the archive root, plus test-only builds of every native
    // library. None of them can run on a phone, so none may be packaged.
    jniLibs.excludes += "**/libsignal_jni_testing.so"
    resources.excludes += listOf(
      "/*.dylib",
      "/*.dll",
      "/libsignal_jni_testing*",
      "/signal_jni_testing*",
      "/lib/**/libsignal_jni_testing*",
    )
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    isCoreLibraryDesugaringEnabled = true
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Room writes its schema JSON here so that schema changes are reviewed and migrations can be tested.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Without google-services.json the app still builds; it then runs only against the emulators
// (debug) or shows a "setup required" screen.
googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(project(":core"))

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.core.splashscreen)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.navigation.compose)
  // Signal Protocol, AGPL-3.0: read docs/security/E2EE_DECISION.md (release gate) before any distribution
  implementation(libs.libsignal.android)
  // Safety-number QR code: drawing (zxing core, Apache-2.0) and scanning (Google code scanner, needs no camera permission).
  implementation(libs.zxing.core)
  implementation(libs.play.services.code.scanner)
  implementation(libs.mlkit.translate)
  implementation(libs.mlkit.language.id)
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment.ktx)
  // Personal Vault metadata (titles are encrypted before they reach the database).
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  // Vault backup to the hidden Google Drive app-data folder (scope drive.appdata only).
  implementation(libs.androidx.work.runtime)
  implementation(libs.gms.play.services.auth)

  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.functions)
  implementation(libs.firebase.messaging)
  implementation(libs.firebase.database)
  implementation(libs.firebase.storage)
  implementation(libs.firebase.appcheck.playintegrity)
  debugImplementation(libs.firebase.appcheck.debug)

  // Calls and meetings (Phase 7): LiveKit client SDK (Apache-2.0) with its bundled WebRTC.
  implementation(libs.livekit.android)

  // GIFs and stickers: Coil loads them from the provider CDN (animated through coil-gif; OkHttp does the network).
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.coil.network.okhttp)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.espresso.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
