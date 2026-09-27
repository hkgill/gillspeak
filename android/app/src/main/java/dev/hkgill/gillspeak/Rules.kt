package dev.hkgill.gillspeak

/**
 * Deterministic text clean-up, ported from gillspeak's gillspeak/rules.py.
 *
 * Order: fillers -> stutters -> spoken commands -> dictionary -> whitespace.
 * Android's regex engine (ICU) already treats \w and \b as Unicode, as Python 3 does. Don't add (?U):
 * ICU rejects it, and the error surfaces only when this file is first loaded.
 */

private fun re(pattern: String, ignoreCase: Boolean = false): Regex =
    if (ignoreCase) Regex(pattern, RegexOption.IGNORE_CASE) else Regex(pattern)

private val BASIC_FILLERS = listOf("um", "uh", "erm", "er", "ah", "hmm", "mm")
private val AGGRESSIVE_FILLERS = listOf("like", "you know")

// Doubled words that are usually intentional.
private val STUTTER_ALLOWLIST = setOf("that", "had", "bye", "no", "very", "so", "ha", "knock", "tut", "ta")

private const val PUNCT = "[.,;:!?]"

private fun fillerRe(words: List<String>): Regex {
    val alts = words.sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") }
    // Each filler may be stretched ("ummm", "uhhh") and may carry an ASR comma.
    val stretched = alts.replace("um", "um+").replace("uh", "uh+").replace("hmm", "hm+").replace("mm", "mm+")
    return re("""(?<![\w'])(?:$stretched)(?![\w'])\s*,?""", ignoreCase = true)
}

private val BASIC_RE = fillerRe(BASIC_FILLERS)
private val AGGRESSIVE_RE = fillerRe(BASIC_FILLERS + AGGRESSIVE_FILLERS)

fun removeFillers(text: String, aggressive: Boolean = false): String =
    (if (aggressive) AGGRESSIVE_RE else BASIC_RE).replace(text, " ")

private val STUTTER_RE = re("""\b(\w+)(?:,?\s+\1\b)+""", ignoreCase = true)

fun collapseStutters(text: String): String = STUTTER_RE.replace(text) { m ->
    val word = m.groupValues[1]
    if (word.lowercase() in STUTTER_ALLOWLIST || word.all { it.isDigit() }) m.value else word
}

private fun cmd(phrase: String) = "\\b" + phrase.split(" ").joinToString("\\s+") + "\\b"

fun applySpokenCommands(input: String, disabled: Set<String> = emptySet()): String {
    val off = disabled.map { it.lowercase().trim() }.toSet()
    fun on(name: String) = name !in off
    var text = input
    if (on("new paragraph")) text = re("""[ \t]*${cmd("new paragraph")}$PUNCT*[ \t]*""", true).replace(text, "\n\n")
    if (on("new line")) text = re("""[ \t]*${cmd("new line")}$PUNCT*[ \t]*""", true).replace(text, "\n")
    if (on("bullet point")) text = re("""[ \t]*${cmd("bullet point")}$PUNCT*[ \t]*""", true).replace(text, "\n- ")
    if (on("question mark")) text = re("""[ \t]*[,.;:]?[ \t]*${cmd("question mark")}$PUNCT*""", true).replace(text, "?")
    // "full stop" / "period" only at the end of the utterance ("the period of time" stays).
    val ends = listOf("full stop", "period").filter(::on)
    if (ends.isNotEmpty()) {
        val alts = ends.joinToString("|") { cmd(it) }
        text = re("""[ \t]*[,.;:]?[ \t]*(?:$alts)$PUNCT*\s*$""", true).replace(text, ".")
    }
    return text
}

/** Spoken -> written replacements, plus preferred spellings sent to the LLM. */
data class Dictionary(val replace: Map<String, String> = emptyMap(), val bias: List<String> = emptyList()) {
    private val compiled: Pair<Regex, Map<String, String>>? by lazy {
        val lookup = replace.filterKeys { it.isNotBlank() }.mapKeys { (k, _) -> normKey(k) }
        if (lookup.isEmpty()) return@lazy null
        val alts = lookup.keys.sortedByDescending { it.length }
            .joinToString("|") { k -> k.split(" ").joinToString("\\s+") { Regex.escape(it) } }
        re("""(?<![\w-])(?:$alts)(?![\w-])""", ignoreCase = true) to lookup
    }

    fun apply(text: String): String {
        val (pattern, lookup) = compiled ?: return text
        return pattern.replace(text) { m -> lookup.getValue(normKey(m.value)) }
    }

    companion object {
        private fun normKey(k: String) = k.lowercase().trim().split(Regex("\\s+")).joinToString(" ")

        /** One "spoken = Written" per line; quotes (TOML-style) and # comments are allowed. */
        fun parse(replaceText: String, biasText: String): Dictionary {
            val replace = linkedMapOf<String, String>()
            for (raw in replaceText.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val k = line.substring(0, eq).trim().trim('"', '\'')
                val v = line.substring(eq + 1).trim().trim('"', '\'')
                if (k.isNotEmpty() && v.isNotEmpty()) replace[k] = v
            }
            val bias = biasText.split(',', '\n').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
            return Dictionary(replace, bias)
        }
    }
}

private fun capitaliseAt(text: String, pattern: Regex) =
    pattern.replace(text) { m -> m.groupValues[1] + m.groupValues[2].uppercase() }

private val WS_RULES = listOf(
    re("""[ \t]+""") to " ",
    re(""" *\n *""") to "\n",
    re("""\n{3,}""") to "\n\n",
    re(""" +([,.;:!?])""") to "$1",
    // Punctuation debris left by removed fillers/commands: ", ." -> ".", ",," -> ","
    re(""",\s*([.?!])""") to "$1",
    re(""",(\s*,)+""") to ",",
    re("""(?m)^[,;:]\s*""") to "",
    re("""\?\.""") to "?",
    re("""(?<!\.)\.\.(?!\.)""") to ".",
)
private val CAP_START = re("""^([^\w\n]*)([a-z])""")
private val CAP_LINE = re("""(\n(?:- )?)([a-z])""")

fun normaliseWhitespace(input: String): String {
    var text = input.replace("\r\n", "\n")
    for ((pattern, replacement) in WS_RULES) text = pattern.replace(text, replacement)
    text = if (text.isBlank()) "" else text.trim(' ', '\t')
    // Capitalise the first letter, and the first letter of each new line/bullet.
    text = capitaliseAt(text, CAP_START)
    return capitaliseAt(text, CAP_LINE)
}

class Rules(
    val dictionary: Dictionary = Dictionary(),
    private val aggressiveFillers: Boolean = false,
    private val spokenCommands: Boolean = true,
    private val disabledCommands: Set<String> = emptySet(),
) {
    fun apply(input: String): String {
        var text = removeFillers(input, aggressiveFillers)
        text = collapseStutters(text)
        if (spokenCommands) text = applySpokenCommands(text, disabledCommands)
        text = dictionary.apply(text)
        return normaliseWhitespace(text)
    }
}
