import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
android {
    namespace = "dev.hkgill.gillspeak.watch"
    compileSdk = 36
    defaultConfig {
        // Data Layer requires identical application IDs and signing certificates on both devices.
        applicationId = "dev.hkgill.gillspeak"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2-watch-preview"
    }
    local.getProperty("debug.storeFile")?.let { signingConfigs.getByName("debug").storeFile = file(it) }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":wear-protocol"))
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation("junit:junit:4.13.2")
}
