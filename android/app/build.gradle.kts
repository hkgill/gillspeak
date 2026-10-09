import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties is git-ignored: sdk.dir and the release.* signing settings live there.
// API keys never go in the build: users paste their own into the app.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// On-device speech recognition: the official sherpa-onnx Android library (same version as the desktop app),
// fetched from its GitHub release and pinned by SHA256. It's 50 MB, so it's downloaded, not committed.
val sherpaVersion = "1.13.8"
val sherpaSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")

fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val part = File(sherpaAar.path + ".part")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val actual = sha256(part)
    check(actual == sherpaSha256) { "sherpa-onnx AAR checksum mismatch: $actual" }
    part.renameTo(sherpaAar)
}

android {
    namespace = "dev.hkgill.gillspeak"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hkgill.gillspeak"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Every current phone is 64-bit ARM; the other ABIs would add ~70 MB of native code.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release builds are signed with a keystore kept outside the repo; see README "Release a signed APK".
    val releaseStore = local.getProperty("release.storeFile")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = local.getProperty("release.storePassword")
            keyAlias = local.getProperty("release.keyAlias")
            keyPassword = local.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
    implementation(files(sherpaAar))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub in JVM unit tests
}
