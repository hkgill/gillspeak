// Plain Kotlin, no Android: finding and grouping faces, the photo vectors and reading a search. Kept off Android so
// its tests run on any JVM.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // The app brings ONNX Runtime's Android build, which has the same Java API as this desktop one.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.0")
    testImplementation("junit:junit:4.13.2")
}
