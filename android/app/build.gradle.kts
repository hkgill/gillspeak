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

// Whistle's C API and ARM64 static engine, immutable revision and SHA256 pins.
// The archive has no network imports; call its native API without the Python telemetry wrapper.
val whistleRevision = "d6c7d6a2e9655505e0f8e23339356dd1705900eb"
val whistleDir = file("libs/whistle")
mapOf(
    "libneedle.a" to "7aa6de6132a22bf45f5e903425feb66f159ad46f47fa558921bc4fd02c3a2fa0",
    "needle.h" to "a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99",
).forEach { (name, checksum) ->
    val target = File(whistleDir, name)
    if (!target.exists() || sha256(target) != checksum) {
        whistleDir.mkdirs()
        val part = File(whistleDir, "$name.part")
        val url = "https://huggingface.co/Cactus-Compute/needle3/resolve/$whistleRevision/android-arm64/$name"
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(sha256(part) == checksum) { "Whistle $name checksum mismatch" }
        check(part.renameTo(target)) { "Cannot install Whistle $name" }
    }
}

android {
    namespace = "dev.hkgill.gillspeak"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.hkgill.gillspeak"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        // Every current phone is 64-bit ARM; the other ABIs would add ~70 MB of native code.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_static" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    // On-device Gemma. 0.16.x is the last release built with Kotlin 2.2; later ones need Kotlin 2.4 here too.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub in JVM unit tests
}
