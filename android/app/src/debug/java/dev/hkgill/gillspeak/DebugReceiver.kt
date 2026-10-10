package dev.hkgill.gillspeak

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * Test hooks for debug builds, from adb only (the receiver requires DUMP, which apps can't hold):
 *
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --es selftest t.wav [--es engine local]
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --ez download_model true
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --es command "set up an alarm for three o'clock"
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --ez compare true   # or false
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --es polish "so um the meeting is on thursday sorry friday"
 *     adb shell am broadcast -n dev.hkgill.gillspeak/.DebugReceiver --ez keep_empty_audio true   # or false
 *
 * The self-test runs a WAV from the app's files dir through the full pipeline; `engine` overrides the chosen
 * engine for that run only. `command` runs a sentence through the command rules and Gemma and logs both, without
 * doing anything; `compare` turns the rules-versus-Gemma log for spoken commands on or off (it's off by default,
 * as it runs Gemma for every command); `polish` runs a sentence through the rules and Gemma's polish, as if Parakeet had heard it. Results go to `adb logcat -s gillspeak` and the app's dictation log.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.getBooleanExtra("download_model", false)) {
            LocalAsr.download(app)
        }
        if (intent.hasExtra("compare")) {
            Settings(app).compareLog = intent.getBooleanExtra("compare", false)
            Log.i(Dictation.TAG, "compare log ${if (Settings(app).compareLog) "on" else "off"}")
        }
        if (intent.hasExtra("keep_empty_audio")) {
            val on = intent.getBooleanExtra("keep_empty_audio", false)
            Settings(app).keepEmptyAudio = on
            if (!on) File(app.filesDir, "empty.wav").delete()
            Log.i(Dictation.TAG, "keep empty audio ${if (on) "on" else "off"}")
        }
        intent.getStringExtra("command")?.let { text ->
            val pending = goAsync()
            Thread {
                try {
                    val rules = Commands.parse(text)
                    val reply = LocalLlm.ask(app, ModelCommands.PROMPT, text)
                    Log.i(Dictation.TAG, "commandtest \"$text\": rules=${rules ?: "none"} gemma=${ModelCommands.toCommand(reply.text) ?: "none"} " +
                        "raw=${reply.text} ${reply.ms - reply.loadMs} ms (load ${reply.loadMs} ms)")
                } catch (e: Throwable) {
                    Log.e(Dictation.TAG, "commandtest failed", e)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        intent.getStringExtra("polish")?.let { text ->
            val pending = goAsync()
            Thread {
                try {
                    val settings = Settings(app)
                    val rules = Rules(settings.dictionary()).apply(text)
                    val (out, why) = Dictation.polish(app, settings, rules)
                    Log.i(Dictation.TAG, "polishtest: rules=\"$rules\" -> \"$out\" ($why)")
                } catch (e: Throwable) {
                    Log.e(Dictation.TAG, "polishtest failed", e)
                } finally {
                    pending.finish()
                }
            }.start()
            return
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
