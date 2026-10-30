# Tarang (bitchat-android code): Gson and Parcelize read these model classes by field name, and the Noise
# implementation is reached through reflection-free static calls only, so keep models and the Noise protocol intact.
-keep class com.bitchat.android.model.** { *; }
-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.noise.southernstorm.** { *; }
-keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
-dontwarn org.bouncycastle.**
