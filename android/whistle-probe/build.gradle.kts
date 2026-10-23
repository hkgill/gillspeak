plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A Wear OS test app: can a Galaxy Watch run Whistle, the 16.9 MB speech model the phone app uses, on the watch
// itself, and how fast? It reuses the phone app's JNI bridge and Whistle's ARM64 engine, which :app downloads and
// checks (app/libs/whistle). See docs/whistle-on-watch.md. Not an app for everyday use.
evaluationDependsOn(":app")

android {
    namespace = "dev.hkgill.gillspeak.whistleprobe"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.hkgill.gillspeak.whistleprobe"
        minSdk = 30 // Wear OS 3
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Whistle only ships for 64-bit ARM. A watch that runs only 32-bit apps refuses to install this APK
        // (INSTALL_FAILED_NO_MATCHING_ABIS), which is itself the answer.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../app/src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    testImplementation("junit:junit:4.13.2")
}
