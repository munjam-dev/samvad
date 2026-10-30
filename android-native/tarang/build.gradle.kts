// Tarang: the bitchat-android Bluetooth mesh engine (GPL-3.0, combined into Samvad's AGPL-3.0 app).
// Copied sources keep package com.bitchat.android so upstream merges stay easy. See docs/TARANG_INTEGRATION.md.
plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.bitchat.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  lint {
    // Upstream code is kept close to its original form; only Samvad-owned code is held to lint.
    abortOnError = false
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.nordic.ble)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.androidx.security.crypto)
  implementation(libs.gson)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
