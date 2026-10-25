// gillphotos is a build of its own: LiteRT-LM's embedding engine (0.17+) is built with Kotlin 2.4, while gillspeak
// stays on Kotlin 2.2 with LiteRT-LM 0.16. Run it from android/ with `./gradlew -p gillphotos …`.
plugins {
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
