import com.github.triplet.gradle.androidpublisher.ReleaseStatus
import com.github.triplet.gradle.androidpublisher.ResolutionStrategy

plugins {
    id("compassduel.android.application")
    id("compassduel.detekt")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.play.publisher)
}

android {
    namespace = "com.justb81.compassduel"

    defaultConfig {
        applicationId = "com.justb81.compassduel"
        // Android 15 (API 35) is the minimum supported version — no backward
        // compatibility below it is required.
        minSdk = 35
        targetSdk = 37

        // versionCode: CI sets VERSION_CODE (run_number); fallback to 1 locally.
        versionCode = providers.environmentVariable("VERSION_CODE")
            .orElse("1").get().toIntOrNull() ?: 1

        // versionName: release-please sets VERSION_NAME, fallback to hardcoded value.
        versionName = providers.environmentVariable("VERSION_NAME")
            .orElse("0.9.0").get() // x-release-please-version
    }

    // Drop the unused native libraries from the bundle. Each is dead weight on
    // this app's configuration, and shipping any of them makes Play Console flag
    // the bundle as "contains native code, no debug symbols uploaded". The
    // symbols can't be generated anyway (the prebuilt .so files are stripped), so
    // excluding them is the correct fix. Do NOT re-add ndk.debugSymbolLevel.
    packaging {
        jniLibs {
            // androidx.graphics:graphics-path (pulled in by Compose) ships
            // libandroidx.graphics.path.so purely as a pre-API-34 fast path; on
            // API 34+ it uses the platform android.graphics.PathIterator and
            // never loads the .so. minSdk is 35, so it is never loaded.
            excludes += "**/libandroidx.graphics.path.so"
            // androidx.datastore:datastore-core ships libdatastore_shared_counter.so
            // for its multi-process InterProcessCoordinator. The app uses a
            // single-process Preferences DataStore (see
            // AppModule.userPreferencesDataStore), which uses the pure-JVM
            // SingleProcessCoordinator, so the native counter is never loaded.
            excludes += "**/libdatastore_shared_counter.so"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // Activity / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Persistence (theme preference + last-used player name)
    implementation(libs.androidx.datastore.preferences)

    // Offline P2P transport (BLE + Wi-Fi)
    implementation(libs.play.services.nearby)

    // Compact payload (de)serialization for game-state sync
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.coroutines)
}

// Gradle Play Publisher — uploads the signed AAB to the Play Store.
// The release workflow stages the AAB into play-artifacts/; GPP's artifactDir
// mode uploads every AAB found there under a single edit, pairing each AAB with
// its sibling <name>.mapping.txt as the per-versionCode deobfuscation file.
play {
    serviceAccountCredentials.set(
        providers.environmentVariable("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE")
            .orElse("/dev/null")
            .map { path -> layout.projectDirectory.file(path) }
    )

    track.set(providers.gradleProperty("playTrack").orElse("internal"))

    releaseStatus.set(
        providers.gradleProperty("playStatus")
            .orElse("COMPLETED")
            .map { ReleaseStatus.valueOf(it) }
    )

    defaultToAppBundles.set(true)
    resolutionStrategy.set(ResolutionStrategy.FAIL)

    artifactDir.set(rootProject.layout.projectDirectory.dir("play-artifacts"))
}
