# youtubedl-android: Jackson is reflection based
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**
# Ktor
-dontwarn io.ktor.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# NewPipeExtractor (GPL-3.0): reflection over its own model classes, plus
# Rhino (JS interpreter), jsoup (HTML) and protobuf-javalite (YouTube protos).
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-dontwarn javax.annotation.**
-dontwarn com.grack.nanojson.**
-dontwarn org.intellij.lang.annotations.**
-dontwarn org.jetbrains.annotations.**

# jump3r (LGPL-2.1): pure-Java LAME MP3 encoder. Only the low-level
# de.sciss.jump3r.mp3 classes are used; the `lowlevel` helpers depend on
# javax.sound.sampled, which Android does not ship.
-keep class de.sciss.jump3r.mp3.** { *; }
-keep class de.sciss.jump3r.mpg.** { *; }
-dontwarn de.sciss.jump3r.**
-dontwarn javax.sound.**
