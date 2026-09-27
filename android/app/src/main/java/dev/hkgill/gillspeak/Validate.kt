package dev.hkgill.gillspeak

/** Sanity checks on LLM output, ported from gillspeak/validate.py. A rejection means the rules output is used instead. */
object Validate {
    val CORRECTION_RE = Regex(
        """\b(?:sorry|I mean|actually|scratch that|no wait|wait no|let me rephrase|correction|or rather|make that)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PREAMBLE_RE = Regex(
        """^(?:sure|here(?:'|’| i)s|okay|certainly|the (?:cleaned|corrected))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMBER_RE = Regex("""\d[\d,.:/-]*""")

    // A run of numbers separated by single spaces, spoken digit by digit or as a time: "4 8 2", "6 45".
    private val GROUP_RE = Regex("""\d[\d,.:/-]*(?: \d[\d,.:/-]*)*""")

    private fun numbers(text: String): Set<String> =
        NUMBER_RE.findAll(text).map { it.value.trimEnd(',', '.', ':', '/', '-').replace(",", "") }.toSet()

    private fun digits(s: String) = s.filter { it.isDigit() }

    /** True if a number from the input is gone or changed. Never a substring match: "250" is not in "1250". */
    private fun missingNumbers(inp: String, out: String): Boolean {
        val outTokens = numbers(out)
        val outJoined = GROUP_RE.findAll(out).map { digits(it.value) }.toSet() + outTokens.map(::digits)
        for (m in GROUP_RE.findAll(inp)) {
            if (numbers(m.value).all { it in outTokens }) continue
            if (digits(m.value) in outJoined) continue
            return true
        }
        return false
    }

    /** Returns "" when ok, otherwise a reason. Reasons never include dictated values. */
    fun check(inp: String, out: String, mode: String = "default"): String {
        val o = out.trim()
        if (o.isEmpty()) return "empty"
        val ratio = o.length.toDouble() / maxOf(inp.trim().length, 1)
        val maxRatio = if (mode == "formal") 2.5 else 1.6
        if (ratio < 0.4) return "too_short:%.2f".format(ratio)
        if (ratio > maxRatio) return "too_long:%.2f".format(ratio)
        if (PREAMBLE_RE.containsMatchIn(o) && !PREAMBLE_RE.containsMatchIn(inp.trim())) return "preamble"
        if (!CORRECTION_RE.containsMatchIn(inp) && missingNumbers(inp, o)) return "missing_number"
        return ""
    }
}
