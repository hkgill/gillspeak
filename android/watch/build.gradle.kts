plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Wear OS app behind the agent watch face: it fetches the status from `gillspeak agents serve` on the
// computer and hands it to the face as complications. No model, no microphone, nothing from the phone app.
android {
    namespace = "dev.hkgill.gillspeak.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hkgill.gillspeak.watch"
        minSdk = 30 // Wear OS 3
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":agent-status"))
    implementation("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
}
