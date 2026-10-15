import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.services)
}

// Debug builds talk to the Firebase Emulator Suite when -Psamvad.useEmulators=true
// (host 10.0.2.2 reaches the development machine from the Android emulator).
val useEmulators = (findProperty("samvad.useEmulators") as String?)?.toBoolean() ?: false
val emulatorHost = (findProperty("samvad.emulatorHost") as String?) ?: "10.0.2.2"

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

  buildTypes {
    debug {
      applicationIdSuffix = ".debug"
      buildConfigField("boolean", "USE_EMULATORS", useEmulators.toString())
      buildConfigField("String", "EMULATOR_HOST", "\"$emulatorHost\"")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      buildConfigField("boolean", "USE_EMULATORS", "false")
      buildConfigField("String", "EMULATOR_HOST", "\"\"")
    }
  }

  packaging {
    // Signal ships test-only native builds next to the real ones; they must never be in the app.
    jniLibs.excludes += "**/libsignal_jni_testing.so"
    jniLibs.excludes += "/lib/*/libsignal_jni_testing.so"
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
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.fragment.ktx)

  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.functions)
  implementation(libs.firebase.messaging)
  implementation(libs.firebase.database)
  implementation(libs.firebase.storage)
  implementation(libs.firebase.appcheck.playintegrity)
  debugImplementation(libs.firebase.appcheck.debug)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.play.services)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
