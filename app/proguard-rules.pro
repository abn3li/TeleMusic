# TDLib's JNI bridge constructs org.drinkless.tdlib.TdApi.* objects and invokes their
# fields/constructors reflectively from native code by class/field name. R8 renaming or
# removing anything in this package causes a silent native-side crash (NoSuchMethodError /
# ClassNotFoundException), so it must be kept in full, unobfuscated.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }

# Gson deserializes these response models by matching JSON keys to field names via
# reflection - none of them use @SerializedName, so obfuscating field names silently
# drops every field (nulls everywhere) instead of crashing.
-keep class com.abn3li.telemusic.data.remote.** { *; }

# Same reasoning as above, for KuGou's response models - these are private data classes
# nested inside LyricsRepository (parsed via a raw gson.fromJson(), not Retrofit) rather
# than living under data.remote, so the keep rule above doesn't reach them. Without this,
# every KuGou field comes back null in release builds only - which silently kills synced
# lyrics for exactly the songs LRCLIB doesn't have, since KuGou is the provider this app
# leans on most for those. $* keeps every nested class, since Kotlin compiles a private
# nested class to an inner JVM class named LyricsRepository$ClassName.
-keep class com.abn3li.telemusic.repository.LyricsRepository$* { *; }

# Room entities are read/written by generated DAO code but can also be inspected via
# reflection by some Room internals (type converters, POJO mapping) - keep field names.
-keep class com.abn3li.telemusic.data.local.*Entity { *; }
-keep class com.abn3li.telemusic.data.local.*Summary { *; }
-keep class com.abn3li.telemusic.data.local.*CrossRef { *; }
