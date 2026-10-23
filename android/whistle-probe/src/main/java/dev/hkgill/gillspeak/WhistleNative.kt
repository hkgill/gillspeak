package dev.hkgill.gillspeak

/**
 * The phone app's JNI bridge (app/src/main/cpp/whistle_jni.cpp), built again for this probe. It has to keep this
 * package and name: the native functions are bound to `dev.hkgill.gillspeak.WhistleNative`.
 */
object WhistleNative {
    init { System.loadLibrary("gillspeak_whistle") }
    external fun load(path: String)
    external fun transcribe(samples: FloatArray, keywords: ByteArray): ByteArray
}
