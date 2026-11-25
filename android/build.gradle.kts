plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Optional: keep build output somewhere else (-PescBuildRoot=<dir>). Useful
// when the project sits on a slow or exFAT drive.
providers.gradleProperty("escBuildRoot").orNull?.let { buildRoot ->
    allprojects {
        layout.buildDirectory.set(File(buildRoot, if (this == rootProject) "root" else name))
    }
}
