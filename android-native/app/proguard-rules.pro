# Firebase and AndroidX ship their own consumer rules. Keep Kotlin metadata for reflection-free code only.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Calls and meetings (Phase 7): LiveKit and its WebRTC build reach Java classes from native code.
-keep class livekit.org.webrtc.** { *; }
-dontwarn livekit.org.webrtc.**
-keep class io.livekit.android.** { *; }
-dontwarn io.livekit.android.**

# Release builds drop verbose and debug logging, so a stray log line can never leak data.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
