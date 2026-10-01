plugins {
    id("com.android.application")
}

// Wires the release signingConfig from NEXUS_RELEASE_KEYSTORE /
// NEXUS_RELEASE_KEYSTORE_PASSWORD / NEXUS_RELEASE_KEY_ALIAS when all three are
// present. Debug and unit-test builds need none of them.
apply(from = rootProject.file("gradle/plugin-release-signing.gradle"))

android {
    namespace = "com.beyondlevi.nexus.news"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.beyondlevi.nexus.news"
        // minSdk 30: the Nexus platform supports Android 11 (API 30). A minSdk-31
        // APK cannot be parsed at all on an API 30 phone and the Store install
        // fails there (plugins/AGENTS.md, docs/PLUGIN_SDK.md).
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "1.1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Rokid Nexus plugin SDK (bus-client); `shared` resolves transitively. Pinned
    // to a commit of the alvarosw/Rokid-Nexus fork, the only SDK with the widget
    // tile lease (onNexusTileActive/onNexusTileRefresh) and TileContent templates.
    implementation("com.github.alvarosw.Rokid-Nexus:bus-client:ed7ca9a1c4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    // org.json ships in the Android runtime but not in the JVM test runtime; the
    // typed surface models construct JSON when they are serialised.
    testImplementation("org.json:json:20240303")
}
