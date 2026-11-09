"""Deterministic text clean-up applied before (and partly after) the LLM.

Order: fillers -> stutters -> spoken commands -> snippets -> code case -> dictionary -> whitespace.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .config import Dictionary, RulesConfig

BASIC_FILLERS = ["um", "uh", "erm", "er", "ah", "hmm", "mm"]
AGGRESSIVE_FILLERS = ["like", "you know"]

# Doubled words that are usually intentional.
STUTTER_ALLOWLIST = {"that", "had", "bye", "no", "very", "so", "ha", "knock", "tut", "ta"}

COMMAND_NAMES = ["new paragraph", "new line", "bullet point", "question mark", "full stop", "period", "code case"]

_PUNCT = r"[.,;:!?]"

# Text that must come out exactly as written (snippets, code identifiers) waits behind a private-use
# character while the rest of the transcript is tidied.
_HOLD_BASE = 0xE000
_HOLD_RANGE = "-"


class _Held:
    def __init__(self) -> None:
        self.items: list[str] = []

    def hold(self, value: str) -> str:
        self.items.append(value)
        return chr(_HOLD_BASE + len(self.items) - 1)

    def restore(self, text: str) -> str:
        # A cue said on its own shouldn't pick up the full stop ASR puts after it.
        if len(self.items) == 1 and text.strip(" \t.,;:!?") == chr(_HOLD_BASE):
            return self.items[0]
        for i, value in enumerate(self.items):
            text = text.replace(chr(_HOLD_BASE + i), value)
        return text


def _filler_re(words: list[str]) -> re.Pattern[str]:
    alts = "|".join(sorted((re.escape(w).replace(r"\ ", r"\s+") for w in words), key=len, reverse=True))
    # Each filler may be stretched ("ummm", "uhhh") and may carry an ASR comma.
    stretched = alts.replace("um", "um+").replace("uh", "uh+").replace("hmm", "hm+").replace("mm", "mm+")
    return re.compile(rf"(?<![\w'])(?:{stretched})(?![\w'])\s*,?", re.IGNORECASE)


_BASIC_RE = _filler_re(BASIC_FILLERS)
_AGGRESSIVE_RE = _filler_re(BASIC_FILLERS + AGGRESSIVE_FILLERS)


def remove_fillers(text: str, aggressive: bool = False) -> str:
    return (_AGGRESSIVE_RE if aggressive else _BASIC_RE).sub(" ", text)


_STUTTER_RE = re.compile(r"\b(\w+)(?:,?\s+\1\b)+", re.IGNORECASE)


def collapse_stutters(text: str) -> str:
    def repl(m: re.Match[str]) -> str:
        word = m.group(1)
        if word.lower() in STUTTER_ALLOWLIST or word.isdigit():
            return m.group(0)
        return word

    return _STUTTER_RE.sub(repl, text)


def _cmd(phrase: str) -> str:
    return r"\b" + r"\s+".join(phrase.split()) + r"\b"


def apply_spoken_commands(text: str, disabled: list[str] | None = None) -> str:
    off = {d.lower().strip() for d in (disabled or [])}

    def on(name: str) -> bool:
        return name not in off

    if on("new paragraph"):
        text = re.sub(rf"[ \t]*{_cmd('new paragraph')}{_PUNCT}*[ \t]*", "\n\n", text, flags=re.I)
    if on("new line"):
        text = re.sub(rf"[ \t]*{_cmd('new line')}{_PUNCT}*[ \t]*", "\n", text, flags=re.I)
    if on("bullet point"):
        text = re.sub(rf"[ \t]*{_cmd('bullet point')}{_PUNCT}*[ \t]*", "\n- ", text, flags=re.I)
    if on("question mark"):
        text = re.sub(rf"[ \t]*[,.;:]?[ \t]*{_cmd('question mark')}{_PUNCT}*", "?", text, flags=re.I)
    # "full stop" / "period" only at the end of the utterance ("the period of time" stays).
    ends = [n for n in ("full stop", "period") if on(n)]
    if ends:
        alts = "|".join(_cmd(n) for n in ends)
        text = re.sub(rf"[ \t]*[,.;:]?[ \t]*(?:{alts}){_PUNCT}*\s*$", ".", text, flags=re.I)
    return text


def _dictionary_re(replace: dict[str, str]) -> tuple[re.Pattern[str], dict[str, str]] | None:
    if not replace:
        return None
    lookup = {" ".join(k.lower().split()): v for k, v in replace.items() if k.strip()}
    keys = sorted(lookup, key=len, reverse=True)
    alts = "|".join(r"\s+".join(re.escape(w) for w in k.split()) for k in keys)
    return re.compile(rf"(?<![\w-])(?:{alts})(?![\w-])", re.IGNORECASE), lookup


_dict_cache: dict[tuple[tuple[str, str], ...], tuple[re.Pattern[str], dict[str, str]] | None] = {}


def apply_dictionary(text: str, dictionary: Dictionary) -> str:
    key = tuple(sorted(dictionary.replace.items()))
    if key not in _dict_cache:
        _dict_cache[key] = _dictionary_re(dictionary.replace)
    compiled = _dict_cache[key]
    if compiled is None:
        return text
    pattern, lookup = compiled
    return pattern.sub(lambda m: lookup[" ".join(m.group(0).lower().split())], text)


def hold_snippets(text: str, snippets: dict[str, str], held: _Held) -> str:
    key = tuple(sorted(snippets.items()))
    if key not in _dict_cache:
        _dict_cache[key] = _dictionary_re(snippets)
    compiled = _dict_cache[key]
    if compiled is None:
        return text
    pattern, lookup = compiled
    return pattern.sub(lambda m: held.hold(lookup[" ".join(m.group(0).lower().split())]), text)


_CODE_CASE_RE = re.compile(r"\b(snake|kebab|camel|pascal|constant)[ \t]+case\b", re.IGNORECASE)
_CODE_WORD_RE = re.compile(r"[ \t]*([A-Za-z0-9']+)")
_CODE_END_RE = re.compile(r"[ \t]+end[ \t]+case\b,?", re.IGNORECASE)
MAX_CODE_WORDS = 6
# An identifier runs up to punctuation, "end case", or one of these, so the rest of the sentence survives.
CODE_STOP_WORDS = frozenset(
    {
        "is", "are", "was", "were", "be", "and", "or", "but", "then", "should", "must", "will", "can", "could", "would",
        "has", "have", "had", "in", "on", "at", "to", "from", "with", "for",
    }
)


def _identifier(style: str, words: list[str]) -> str:
    if style == "snake":
        return "_".join(words)
    if style == "kebab":
        return "-".join(words)
    if style == "constant":
        return "_".join(words).upper()
    capitalised = [w.capitalize() for w in words]
    return "".join(capitalised) if style == "pascal" else words[0] + "".join(capitalised[1:])


def apply_code_case(text: str, held: _Held) -> str:
    """"snake case user name" -> user_name. Fewer than two words means they were only talking about the style."""
    out: list[str] = []
    pos = 0
    while m := _CODE_CASE_RE.search(text, pos):
        end = m.end()
        words: list[str] = []
        while len(words) < MAX_CODE_WORDS and not _CODE_END_RE.match(text, end):
            word = _CODE_WORD_RE.match(text, end)
            if word is None or word.group(1).lower() in CODE_STOP_WORDS:
                break
            words.append(word.group(1).replace("'", "").lower())
            end = word.end()
        if closing := _CODE_END_RE.match(text, end):
            end = closing.end()
        if len(words) < 2:
            out.append(text[pos : m.end()])
            pos = m.end()
            continue
        out += [text[pos : m.start()], held.hold(_identifier(m.group(1).lower(), words))]
        pos = end
    out.append(text[pos:])
    return "".join(out)


def _capitalise_at(text: str, pattern: str) -> str:
    return re.sub(pattern, lambda m: m.group(1) + m.group(2).upper(), text)


def normalise_whitespace(text: str) -> str:
    text = text.replace("\r\n", "\n")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r" *\n *", "\n", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    text = re.sub(r" +([,.;:!?])", r"\1", text)
    # Punctuation debris left by removed fillers/commands: ", ." -> ".", ",," -> ","
    text = re.sub(r",\s*([.?!])", r"\1", text)
    text = re.sub(r",(\s*,)+", ",", text)
    text = re.sub(r"(?m)^[,;:]\s*", "", text)
    text = re.sub(r"\?\.", "?", text)
    text = re.sub(r"(?<!\.)\.\.(?!\.)", ".", text)
    text = text.strip(" \t") if text.strip() else ""
    # Capitalise the first letter, and the first letter of each new line/bullet.
    text = _capitalise_at(text, rf"^([^\w\n{_HOLD_RANGE}]*)([a-z])")
    text = _capitalise_at(text, r"(\n(?:- )?)([a-z])")
    return text


@dataclass
class RulesEngine:
    cfg: RulesConfig = field(default_factory=RulesConfig)
    dictionary: Dictionary = field(default_factory=Dictionary)

    def _enabled(self, command: str) -> bool:
        return self.cfg.spoken_commands and command not in {d.lower().strip() for d in self.cfg.disabled_commands}

    def apply(self, text: str) -> str:
        held = _Held()
        text = remove_fillers(text, self.cfg.aggressive_fillers)
        text = collapse_stutters(text)
        if self.cfg.spoken_commands:
            text = apply_spoken_commands(text, self.cfg.disabled_commands)
        text = hold_snippets(text, self.dictionary.snippets, held)
        if self._enabled("code case"):
            text = apply_code_case(text, held)
        text = apply_dictionary(text, self.dictionary)
        return held.restore(normalise_whitespace(text))

    def apply_dictionary(self, text: str) -> str:
        return apply_dictionary(text, self.dictionary)
