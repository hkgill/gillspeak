package dev.hkgill.gillspeak.wear

/** Testable local-inference adapter. The Android caller supplies only its existing phone model. */
object AnswerTask {
    fun run(ready: () -> Boolean, cancelled: () -> Boolean,
            generate: (onReady: () -> Unit) -> String, emit: (Protocol.Kind, String) -> Unit) {
        fun send(kind: Protocol.Kind, text: String) { if (!cancelled()) emit(kind, text) }
        if (cancelled()) return
        if (!ready()) {
            send(Protocol.Kind.ERROR, "Gemma isn't ready. Open Speech and understanding on your phone to finish its download.")
            return
        }
        send(Protocol.Kind.STATUS, "Loading or waiting for Gemma on phone…")
        try {
            val answer = generate { send(Protocol.Kind.STATUS, "Gemma is answering on phone…") }
            if (answer.isBlank()) send(Protocol.Kind.ERROR, "Gemma returned an empty answer. Try rephrasing.")
            else send(Protocol.Kind.RESULT, answer.take(Protocol.MAX_TEXT))
        } catch (_: Exception) {
            send(Protocol.Kind.ERROR, "Gemma couldn't answer. Open GillSpeak on your phone and check the model.")
        }
    }
}
