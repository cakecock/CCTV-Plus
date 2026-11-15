-keep class com.myiptv.app.ReminderReceiver { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.**
-dontwarn okio.**
