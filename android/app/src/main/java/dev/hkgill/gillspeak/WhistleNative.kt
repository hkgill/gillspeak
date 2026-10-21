package dev.hkgill.gillspeak

/** Calls are serialized by Whistle's AsrEngine: the upstream C API owns one global speech model. */
internal object WhistleNative {
    init { System.loadLibrary("gillspeak_whistle") }
    external fun load(path: String)
    external fun transcribe(samples: FloatArray, keywords: ByteArray): ByteArray
}
