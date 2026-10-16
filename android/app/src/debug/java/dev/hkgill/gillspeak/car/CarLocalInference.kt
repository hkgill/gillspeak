package dev.hkgill.gillspeak.car

import android.content.Context
import dev.hkgill.gillspeak.Dictation
import dev.hkgill.gillspeak.Gate
import dev.hkgill.gillspeak.LocalAsr
import dev.hkgill.gillspeak.LocalLlm
import dev.hkgill.gillspeak.Rules
import dev.hkgill.gillspeak.Settings
import dev.hkgill.gillspeak.Validate

/** Reuses the phone's verified model storage, locks, idle release and owner-scoped Gemma cancellation. */
internal class CarLocalInference(context: Context) : CarInference {
    private val context = context.applicationContext

    override fun transcribe(wav: ByteArray) = LocalAsr.transcribe(context, wav)

    override fun polish(text: String, owner: Any, cancelled: () -> Boolean): CarReply {
        val settings = Settings(context)
        val dictionary = settings.dictionary()
        val rules = Rules(dictionary).apply(text)
        if (cancelled()) return CarReply("", "Discarded")
        if (!settings.localPolish) return CarReply(rules, "Offline · rules only; Gemma polish is off.")
        if (!LocalLlm.isReady(context)) return CarReply(rules, "Offline · Gemma missing; rules only.")
        if (!Gate.decide(rules).first) return CarReply(rules, "Offline · rules only; short transcript.")
        return try {
            val reply = LocalLlm.ask(context, settings.cleanPrompt(), Dictation.cleanPayload(rules, dictionary.bias),
                maxTokens = 256, owner = owner)
            val cleaned = Dictation.stripEcho(reply.text, rules)
            if (Validate.check(rules, cleaned).isEmpty()) CarReply(dictionary.apply(cleaned), "Offline · Gemma polish.")
            else CarReply(rules, "Offline · polish rejected; rules only.")
        } catch (_: Exception) {
            CarReply(rules, "Offline · Gemma unavailable; rules only.")
        }
    }

    override fun cancel(owner: Any) { LocalLlm.cancel(owner) }
}
