import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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

// The face models from OpenCV Zoo, pinned to one commit and SHA256, fetched at build time and packed into the APK
// (10 MB together): YuNet finds faces (MIT), SFace tells people apart (Apache 2.0, the int8 build).
val zooRevision = "48b10fa505aeb51fbd127fc60a5c660040e823b7"
val faceModels = file("models")
mapOf(
    "face_detection_yunet/face_detection_yunet_2023mar.onnx" to "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    "face_recognition_sface/face_recognition_sface_2021dec_int8.onnx" to "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a",
).forEach { (path, checksum) ->
    val target = File(faceModels, path.substringAfter('/'))
    if (!target.exists() || sha256(target) != checksum) {
        faceModels.mkdirs()
        val part = File(target.path + ".part")
        val url = "https://media.githubusercontent.com/media/opencv/opencv_zoo/$zooRevision/models/$path"
        logger.lifecycle("Downloading $url")
        URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        check(sha256(part) == checksum) { "${target.name} checksum mismatch" }
        check(part.renameTo(target)) { "Cannot install ${target.name}" }
    }
}

android {
    namespace = "dev.hkgill.gillphotos"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hkgill.gillphotos"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Every current phone is 64-bit ARM; ONNX Runtime alone would add ~30 MB for the other ABIs.
        ndk { abiFilters += "arm64-v8a" }
    }

    sourceSets["main"].assets.srcDir(faceModels)

    packaging {
        // LiteRT-LM and ONNX Runtime may both carry the C++ runtime.
        jniLibs { pickFirsts += "**/libc++_shared.so" }
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
    implementation(project(":core"))
    // EmbeddingGemma 2 (EmbeddingEngine, 0.17+) and Gemma 4, on the GPU.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.1")
    // YuNet and SFace.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
}
