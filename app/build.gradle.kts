import java.util.Properties
import groovy.json.JsonOutput

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
// A fresh checkout can use its own Python installation without changing source.
val ownerPython = "C:\\Users\\abn3l\\AppData\\Local\\Python\\pythoncore-3.13-64\\python.exe"
val buildPythonExecutable = providers.environmentVariable("TELEMUSIC_BUILD_PYTHON").orNull
    ?: ownerPython.takeIf { file(it).isFile }
    ?: "python3"

kotlin {
    jvmToolchain(17)
}

android {
    namespace = "com.abn3li.telemusic"
    compileSdk = 36
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "com.abn3li.telemusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "2.3"
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
    sourceSets.getByName("main") {
        java.srcDir(rootProject.file("third_party/media3-ffmpeg/src/main/java"))
        assets.srcDir(layout.buildDirectory.dir("generated/openSourceNotices"))
    }
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
        // Preserve upstream license/NOTICE files even when several jars supply them.
        resources.merges.addAll(listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt"))
        jniLibs.useLegacyPackaging = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(buildPythonExecutable)
        pip {
            // Pin the packages so the release's supplied source and notices stay in sync.
            install("yt-dlp==2026.8.19")
            install("mutagen==1.48.1")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("io.coil-kt:coil-compose:2.6.0")


    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")
    // The decoder's Java/JNI source is vendored; native binaries have a verified build recipe.
    compileOnly("org.checkerframework:checker-qual:3.13.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Prebuilt TDLib for Android - no NDK build needed. Check
    // https://jitpack.io/#tdlibx/td for the latest tag if this one ever breaks.
    implementation("com.github.tdlibx:td:1.8.56")
}

val licensingInventory = rootProject.layout.buildDirectory.file("licensing/release-components.json")
val licensingAssets = layout.buildDirectory.dir("generated/openSourceNotices/licenses")
val prepareOpenSourceNotices = tasks.register<Exec>("prepareOpenSourceNotices") {
    doFirst {
        val artifacts = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts.map { artifact ->
            mapOf("group" to artifact.moduleVersion.id.group, "name" to artifact.moduleVersion.id.name,
                "version" to artifact.moduleVersion.id.version, "classifier" to artifact.classifier,
                "file" to artifact.file.absolutePath, "extension" to artifact.extension)
        }
        licensingInventory.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.prettyPrint(JsonOutput.toJson(artifacts)))
        }
    }
    commandLine(buildPythonExecutable, rootProject.file("tools/licensing/release.py"),
        "--inventory", licensingInventory.get().asFile, "--assets", licensingAssets.get().asFile)
}
tasks.named("preBuild") { dependsOn(prepareOpenSourceNotices) }

val packageCorrespondingSource = tasks.register<Exec>("packageCorrespondingSource") {
    dependsOn("assembleRelease")
    onlyIf {
        val assembled = tasks.getByName("assembleRelease").state
        assembled.executed && assembled.failure == null
    }
    commandLine(buildPythonExecutable, rootProject.file("tools/licensing/release.py"),
        "--inventory", licensingInventory.get().asFile, "--assets", licensingAssets.get().asFile,
        "--apk", layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile)
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(packageCorrespondingSource) }
