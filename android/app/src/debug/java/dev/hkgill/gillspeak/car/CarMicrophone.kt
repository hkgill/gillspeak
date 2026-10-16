package dev.hkgill.gillspeak.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import dev.hkgill.gillspeak.LocalAsr

/** Only constructed after an explicit Speak action. Never falls back to the phone microphone. */
internal class CarMicrophone(private val context: CarContext, private val interrupted: () -> Unit) : CarAudioInput {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var recorder: CarAudioRecord? = null
    private var stopped = false
    private var focused = false
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
        .setOnAudioFocusChangeListener({ change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) interrupted()
        }, Handler(Looper.getMainLooper()))
        .build()

    @Synchronized
    @SuppressLint("MissingPermission") // Rechecked immediately here, as well as in the screen's availability gate.
    override fun start() {
        check(!stopped)
        check(context.carAppApiLevel >= 5)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focused = true
        try {
            val car = CarAudioRecord.create(context)
            recorder = car // retain it for cleanup even if startRecording throws
            car.startRecording()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    override fun read(buffer: ByteArray): Int {
        val current = synchronized(this) { recorder } ?: return -1
        return current.read(buffer, 0, buffer.size)
    }

    @Synchronized
    override fun stop() {
        stopped = true
        recorder?.let { runCatching { it.stopRecording() } }
        recorder = null
        if (focused) runCatching { manager.abandonAudioFocusRequest(focus) }
        focused = false
    }
}

internal fun carProblem(context: CarContext): String? = when {
    context.carAppApiLevel < 5 -> "Car microphone needs host API 5 or later."
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
        "Microphone permission missing. Open GillSpeak on the phone only when parked."
    !LocalAsr.isReady(context) -> "Speech model missing. Download in GillSpeak on the phone only when parked. Offline use needs an installed model."
    else -> null
}
