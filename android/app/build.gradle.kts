import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// local.properties is git-ignored: sdk.dir, gemini.apiKey and groq.apiKey live there.
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun quoted(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
        buildConfigField("String", "GEMINI_API_KEY", quoted(local.getProperty("gemini.apiKey", "")))
        buildConfigField("String", "GROQ_API_KEY", quoted(local.getProperty("groq.apiKey", "")))
        // Every current phone is 64-bit ARM; the other ABIs would add ~70 MB of native code.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

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
