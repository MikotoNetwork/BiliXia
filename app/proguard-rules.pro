# 混淆规则
-obfuscationdictionary bt-proguard.txt
-classobfuscationdictionary bt-proguard.txt
-packageobfuscationdictionary bt-proguard.txt

# 跳过这些
# ffmpeg相关
-keep class com.arthenica.ffmpegkit.** { *; }
 # smartexception
-keep class com.arthenica.smartexception.** { *; }
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**
# ffmpegkit
-keep class dev.ffmpegkit.** { *; }
-dontwarn dev.ffmpegkit.**
# androidx
-keep class androidx.activity.result.** { *; }
-keep class top.misaknetwork.bilixia.** { *; }