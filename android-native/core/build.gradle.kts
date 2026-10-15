import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
  implementation(libs.libphonenumber)
  implementation(libs.kotlinx.coroutines.core)
  // Signal Protocol API. compileOnly here: the app supplies the Android build of the same classes at runtime.
  compileOnly(libs.libsignal.client)
  testImplementation(libs.libsignal.client)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
