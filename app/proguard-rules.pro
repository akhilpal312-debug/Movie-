# JARVIS release rules.
# Keep the JSON command schema intact: it is parsed reflectively-free but the
# field names are part of the contract with Gemini, so no obfuscation hints needed.

-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn org.jetbrains.annotations.**

# OkHttp is used for the Gemini Live websocket.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room entities and DAOs are generated; keep generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# Hilt generated components.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Services bound by the system must keep their names.
-keep class com.jarvis.assistant.assistant.** { *; }
-keep class com.jarvis.assistant.device.JarvisNotificationListenerService { *; }
