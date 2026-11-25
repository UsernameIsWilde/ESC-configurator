plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Production build of the upstream web app (run `yarn build` in ../web first).
val webBuildDir = rootProject.file("../web/build")
// Firmware pack downloaded by tools/fetch_firmware.py (optional).
val firmwarePackDir = rootProject.file("../firmware-pack")
val generatedAssetsDir = layout.buildDirectory.dir("generated/escAssets")

android {
    namespace = "app.offline.escconfigurator"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.offline.escconfigurator"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        // Tracks the upstream web app version that is bundled.
        versionName = "0.32.1-android1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the APK can be sideloaded as is.
            // Replace with your own signing config before publishing anywhere.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedAssetsDir)
}

/**
 * Copies the web build into the APK assets (www/) and injects the Android
 * bridge script so it runs before the web app's own bundle.
 */
val syncWebAssets = tasks.register<Sync>("syncWebAssets") {
    doFirst {
        if (!webBuildDir.resolve("index.html").exists()) {
            throw GradleException(
                "Web build not found at $webBuildDir - run `yarn build` in the web folder first."
            )
        }
    }

    into(generatedAssetsDir)

    from(webBuildDir) {
        into("www")
        // The native layer handles offline use, a service worker would only
        // get in the way. Source maps and social preview are dead weight.
        exclude("service-worker.js*", "**/*.map", "**/*.LICENSE.txt", "og-image.png", "robots.txt")
        filesMatching("index.html") {
            filter { line: String ->
                line.replaceFirst("<head>", "<head><script src=\"/android-bridge.js\"></script>")
            }
        }
    }

    from(firmwarePackDir) {
        into("offline")
    }
}

tasks.named("preBuild") {
    dependsOn(syncWebAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.github.mik3y:usb-serial-for-android:3.9.0")
}
