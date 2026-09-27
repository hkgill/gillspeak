package dev.hkgill.murmur

/** Decides whether a transcript is worth an LLM round trip. Ported from murmur/gate.py. */
object Gate {
    private const val MIN_WORDS = 12
    private val WORD_RE = Regex("""[\w'’-]+""")
    private val LIST_RE = Regex("""\bfirst(?:ly)?\b.*\bsecond(?:ly)?\b|\bnumber one\b|\bbullet\b|^- """,
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))

    fun wordCount(text: String) = WORD_RE.findAll(text).count()

    /** Returns (callLlm, reason). The reason is logged for tuning. */
    fun decide(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "empty"
        val words = wordCount(text)
        return when {
            words >= MIN_WORDS -> true to "words:$words"
            Validate.CORRECTION_RE.containsMatchIn(text) -> true to "correction"
            LIST_RE.containsMatchIn(text) -> true to "list"
            else -> false to "short:$words"
        }
    }
}
