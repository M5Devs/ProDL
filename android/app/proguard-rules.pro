# youtubedl-android: Jackson is reflection based
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
# Ktor
-dontwarn io.ktor.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
