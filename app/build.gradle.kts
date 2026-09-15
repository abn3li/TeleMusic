import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

// Release signing credentials live in local.properties (gitignored, machine-local) - never
// committed. Falls back to the debug key when they're absent, e.g. a fresh checkout that
// hasn't set these up yet, so the release build type still works out of the box for local
// testing without a real keystore.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile = localProperties.getProperty("release.storeFile")

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.abn3li.telemusic"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.abn3li.telemusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.5"
        // No Telegram credentials anywhere in the build - user enters them at runtime
        // (see ui/credentials/CredentialsScreen.kt + data/telegram/TelegramCredentialsStore.kt)

        ndk {
            // Chaquopy ships a real Python interpreter per ABI - restricted to the two ABIs
            // real devices actually ship (arm64 phones, x86_64 emulators) rather than all four,
            // since each one adds tens of MB to every APK variant.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }
    signingConfigs {
        getByName("debug") {}
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Real release keystore when local.properties has one configured (see above);
            // falls back to the debug key otherwise so this build type still works without it.
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        // Chaquopy needs a real Python on this machine at build time (to resolve/download the
        // pip packages baked into the APK) - separate from the interpreter it bundles into the
        // app itself for the device to run. Pointed at the exact install rather than relying on
        // its own PATH detection, which didn't pick up a install made mid-session.
        buildPython("C:\\Users\\abn3l\\AppData\\Local\\Python\\pythoncore-3.13-64\\python.exe")
        pip {
            // yt-dlp itself (not the GPL-3.0 youtubedl-android wrapper) - see data/download's
            // own doc. mutagen is yt-dlp's own optional dependency for writing ID3/MP4 tags
            // straight onto the downloaded file so a track has real metadata with no separate
            // tagging step.
            install("yt-dlp")
            install("mutagen")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Prebuilt TDLib for Android - no NDK build needed. Check
    // https://jitpack.io/#tdlibx/td for the latest tag if this one ever breaks.
    implementation("com.github.tdlibx:td:1.8.56")
}