package dev.hkgill.gillspeak

/**
 * One entry of [Settings.recentLog], split for the Recent screens. Dictations have what was heard and what was typed;
 * side-button commands have what was heard and what was done; anything else (a failure, a microphone error) is a note.
 */
data class Recent(val stamp: String, val source: String, val heard: String, val result: String, val note: String, val failed: Boolean) {
    val isCommand get() = source == SIDE_BUTTON

    companion object {
        const val SIDE_BUTTON = "Side button"
        private val ENGINES = listOf("Local", "Gemini", "Groq") // the cloud engines are gone, but their entries can remain
        private val FIELD_RE = Regex("""^ {2}(heard|typed|did): (.*)$""")

        /** "Oct 10 14:02:11  Local 812 ms\n  heard: …\n  typed: …" → its parts. */
        fun parse(entry: String): Recent {
            val lines = entry.lines()
            val first = lines.first()
            val split = first.indexOf("  ")
            val stamp = if (split > 0) first.substring(0, split) else ""
            val head = if (split > 0) first.substring(split + 2).trim() else first.trim()
            // "  heard: …" starts a field; a dictation with "new paragraph" goes on over the following lines.
            val fields = mutableMapOf<String, String>()
            var current: String? = null
            for (line in lines.drop(1)) {
                val m = FIELD_RE.find(line)
                if (m != null) {
                    current = m.groupValues[1]
                    fields[current] = m.groupValues[2]
                } else if (current != null) {
                    fields[current] = fields[current] + "\n" + line
                }
            }
            fun field(name: String) = fields[name].orEmpty()
            return when {
                head.startsWith("Command: ") -> Recent(stamp, SIDE_BUTTON, head.removePrefix("Command: "), field("did"), "", false)
                ENGINES.any { head.startsWith("$it ") } ->
                    Recent(stamp, head.substringBefore(' '), field("heard"), field("typed"), "", false)
                else -> Recent(stamp, "", "", "", head, failed = true)
            }
        }
    }
}
