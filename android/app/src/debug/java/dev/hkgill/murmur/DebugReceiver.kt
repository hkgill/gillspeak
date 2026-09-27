package dev.hkgill.murmur

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Test hooks for debug builds, from adb only (the receiver requires DUMP, which apps can't hold):
 *
 *     adb shell am broadcast -n dev.hkgill.murmur/.DebugReceiver --es selftest t.wav [--es engine local]
 *     adb shell am broadcast -n dev.hkgill.murmur/.DebugReceiver --ez download_model true
 *
 * The self-test runs a WAV from the app's files dir through the full pipeline; `engine` overrides the chosen
 * engine for that run only. Results go to `adb logcat -s Murmur` and the app's dictation log.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.getBooleanExtra("download_model", false)) {
            LocalAsr.download(app) { Log.i(Dictation.TAG, "model download: $it") }
        }
        val name = intent.getStringExtra("selftest") ?: return
        if (!isSafeTestFile(name)) {
            Log.w(Dictation.TAG, "selftest: refusing '$name' (a plain name ending in .wav, no paths)")
            return
        }
        val engine = intent.getStringExtra("engine")?.takeIf { it in ENGINES }
        val settings = Settings(app)
        val pending = goAsync()
        Thread {
            try {
                val wav = File(app.filesDir, name).readBytes()
                require(wav.size > 44 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "not a WAV file" }
                val out = Dictation.run(app, settings, wav, engine ?: settings.engine)
                Log.i(Dictation.TAG, "selftest ok: $out")
            } catch (e: Exception) {
                Dictation.logFailure(settings, e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        val ENGINES = setOf(Settings.ENGINE_GEMINI, Settings.ENGINE_GROQ, Settings.ENGINE_LOCAL)
    }
}
