plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    // Bundles a real Python runtime so app/src/main/python can run yt-dlp's actual package
    // (Unlicense/public domain) directly - see data/download's own doc for why this, rather
    // than the GPL-3.0 youtubedl-android wrapper library, is what keeps this repo MIT.
    id("com.chaquo.python") version "17.0.0" apply false
}
