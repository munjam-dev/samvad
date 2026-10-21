# Firebase and AndroidX ship their own consumer rules. Keep Kotlin metadata for reflection-free code only.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Calls and meetings (Phase 7): LiveKit and its WebRTC build reach Java classes from native code.
-keep class livekit.org.webrtc.** { *; }
-dontwarn livekit.org.webrtc.**
-keep class io.livekit.android.** { *; }
-dontwarn io.livekit.android.**

# LiveKit's Rust core is reached through UniFFI, which uses JNA: both look classes up by name at runtime.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep class uniffi.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
# LiveKit signalling messages are protobuf-lite and rely on field names.
-keep class livekit.LivekitModels** { *; }
-keep class livekit.LivekitRtc** { *; }
-keep class livekit.LivekitTemporary** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# libsignal (AGPL, see docs/security/E2EE_DECISION.md): its native library calls back into Java by name.
-keep class org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**

# Release builds drop verbose and debug logging, so a stray log line can never leak data.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
