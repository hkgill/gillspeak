"""Deterministic text clean-up applied before (and partly after) the LLM.

Order: fillers -> stutters -> spoken commands -> dictionary -> whitespace.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from .config import Dictionary, RulesConfig

BASIC_FILLERS = ["um", "uh", "erm", "er", "ah", "hmm", "mm"]
AGGRESSIVE_FILLERS = ["like", "you know"]

# Doubled words that are usually intentional.
STUTTER_ALLOWLIST = {"that", "had", "bye", "no", "very", "so", "ha", "knock", "tut", "ta"}

COMMAND_NAMES = ["new paragraph", "new line", "bullet point", "question mark", "full stop", "period"]

_PUNCT = r"[.,;:!?]"


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
    text = _capitalise_at(text, r"^([^\w\n]*)([a-z])")
    text = _capitalise_at(text, r"(\n(?:- )?)([a-z])")
    return text


@dataclass
class RulesEngine:
    cfg: RulesConfig = field(default_factory=RulesConfig)
    dictionary: Dictionary = field(default_factory=Dictionary)

    def apply(self, text: str) -> str:
        text = remove_fillers(text, self.cfg.aggressive_fillers)
        text = collapse_stutters(text)
        if self.cfg.spoken_commands:
            text = apply_spoken_commands(text, self.cfg.disabled_commands)
        text = apply_dictionary(text, self.dictionary)
        return normalise_whitespace(text)

    def apply_dictionary(self, text: str) -> str:
        return apply_dictionary(text, self.dictionary)
