# Firebase and AndroidX ship their own consumer rules. Keep Kotlin metadata for reflection-free code only.
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# Release builds drop verbose and debug logging, so a stray log line can never leak data.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
