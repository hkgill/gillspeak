plugins {
    id("com.android.application")
}

// The agent watch face, in Watch Face Format: XML and resources only, no code (res/raw/watchface.xml).
// Its data comes from the gillspeak agents app (:watch) as complications.
android {
    namespace = "dev.hkgill.gillspeak.watchface"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hkgill.gillspeak.watchface"
        minSdk = 33 // Watch Face Format needs Wear OS 4
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}
