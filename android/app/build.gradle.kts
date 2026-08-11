import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing keystore lives OUTSIDE the repo (mediacenter/keys/) — same key as the old
// WebView-only tvapp, so this build installs as an in-place upgrade of com.mediacenter.tv.
val keystore = file("../../../keys/tvapp.jks")
val keystorePass = file("../../../keys/tvapp.pass").let { if (it.exists()) it.readText().trim() else "" }

android {
    namespace = "com.mediacenter.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mediacenter.tv"
        minSdk = 24
        targetSdk = 30
        versionCode = 54
        versionName = "4.0"
    }

    signingConfigs {
        create("release") {
            if (keystore.exists()) {
                storeFile = keystore
                storePassword = keystorePass
                keyAlias = "tvapp"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // targetSdk deliberately stays at 30: the box keeps its media library in shared storage,
    // and 33+ scoped storage would cut the agent off from it. We never ship through Google Play,
    // so its target-level requirement (which lint enforces as fatal) does not apply.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // The TV web UI (agent/tv/) ships inside the APK; HttpServer serves it from assets/tv/.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/tvassets"))
}

// Copy agent/tv/ into assets at build time — one source of truth, no duplication in git.
val syncTvAssets by tasks.registering(Copy::class) {
    from("../../tv") {
        exclude("app/**")            // the old APK itself does not belong inside the new APK
    }
    into(layout.buildDirectory.dir("generated/tvassets/tv"))
}
tasks.named("preBuild") { dependsOn(syncTvAssets) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.firebase:firebase-auth:23.0.0")
    implementation("com.google.firebase:firebase-firestore:25.1.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // BitTorrent for transfers/downloads; native libs per ABI
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-35")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-35")
}
