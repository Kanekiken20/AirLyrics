# Keep release crash reports retraceable while avoiding real source file names
# in stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The Rust lyrics core uses JNI symbol names derived from these native method
# owners. Preserve native method names without disabling general shrinking.
-keepclasseswithmembernames class * {
    native <methods>;
}

# AirLyrics Models & Cache DTOs
-keep class com.andsi.airlyrics.cache.** { *; }
-keep class com.andsi.airlyrics.core.model.** { *; }
-keep class com.andsi.airlyrics.lyrics.** { *; }
-keep class com.andsi.airlyrics.lockscreen.** { *; }
-keep class com.andsi.airlyrics.floating.** { *; }
-keep class com.andsi.airlyrics.media.** { *; }

# Keep RemoteViews notification IDs and layout identifiers
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Android Manifest components (Services, Receivers)
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.service.notification.NotificationListenerService
