# FFmpegKit（之前踩过 NoClassDefFoundError 的坑，保险起见保留）
-keep class com.arthenica.** { *; }
-keep class dev.ffmpegkit.** { *; }
-dontwarn com.arthenica.**
-dontwarn dev.ffmpegkit.**

# Activity 由系统反射实例化，必须 keep
-keep public class * extends android.app.Activity
-keep public class * extends androidx.appcompat.app.AppCompatActivity