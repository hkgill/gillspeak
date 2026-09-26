import pytest

from murmur.config import Dictionary, RulesConfig
from murmur.rules import (
    RulesEngine,
    apply_dictionary,
    apply_spoken_commands,
    collapse_stutters,
    normalise_whitespace,
    remove_fillers,
)


def clean(text, **kw):
    d = kw.pop("dictionary", Dictionary())
    return RulesEngine(RulesConfig(**kw), d).apply(text)


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("Um, I think we should go.", "I think we should go."),
        ("I think, uh, we should go.", "I think, we should go."),
        ("So we're done, uh.", "So we're done."),
        ("Ummm hello there", "Hello there"),
        ("hmm let me see", "Let me see"),
        ("The umbrella is here", "The umbrella is here"),
        ("Her name is Erma", "Her name is Erma"),
        ("I like it, you know.", "I like it, you know."),
    ],
)
def test_fillers(raw, expected):
    assert clean(raw) == expected


def test_aggressive_fillers():
    assert clean("It was like really good, you know.", aggressive_fillers=True) == "It was really good."


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("I I think so", "I think so"),
        ("the the the plan", "the plan"),
        ("The the plan", "The plan"),
        ("I know that that is true", "I know that that is true"),
        ("She had had enough", "She had had enough"),
        ("version 2 2 ships", "version 2 2 ships"),
    ],
)
def test_stutters(raw, expected):
    assert collapse_stutters(raw) == expected


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("Hello there. New paragraph. How are you?", "Hello there.\n\nHow are you?"),
        ("first line new line second line", "First line\nSecond line"),
        ("Shopping list bullet point milk bullet point eggs", "Shopping list\n- Milk\n- Eggs"),
        ("Are you coming, question mark.", "Are you coming?"),
        ("See you then, period.", "See you then."),
        ("See you then full stop", "See you then."),
        ("The period of time was long", "The period of time was long"),
        ("A period, then more words", "A period, then more words"),
        ("Hello new paragraph", "Hello\n\n"),
    ],
)
def test_spoken_commands(raw, expected):
    assert clean(raw) == expected


def test_disabled_command():
    assert apply_spoken_commands("wait new line here", disabled=["new line"]) == "wait new line here"


def test_spoken_commands_off():
    assert clean("hello new line there", spoken_commands=False) == "Hello new line there"


def test_dictionary_longest_first_and_case():
    d = Dictionary(replace={"super base": "Supabase", "super base studio": "Supabase Studio", "get hub": "GitHub"})
    assert apply_dictionary("Open Super Base Studio on get hub.", d) == "Open Supabase Studio on GitHub."
    assert apply_dictionary("use super  base now", d) == "use Supabase now"
    assert apply_dictionary("superbase", d) == "superbase"


def test_dictionary_whole_phrase_only():
    d = Dictionary(replace={"cube control": "kubectl"})
    assert apply_dictionary("cube controls", d) == "cube controls"
    assert apply_dictionary("run cube control, then", d) == "run kubectl, then"


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("hello   world", "Hello world"),
        ("hello , world .", "Hello, world."),
        ("  hi there ", "Hi there"),
        ("wait...", "Wait..."),
        ("done..", "Done."),
        ("", ""),
        ("   ", ""),
    ],
)
def test_whitespace(raw, expected):
    assert normalise_whitespace(raw) == expected


def test_full_pipeline():
    d = Dictionary(replace={"super base": "Supabase"})
    raw = "Um, so the the super base migration is, uh, done. New paragraph. Can you check it, question mark"
    assert clean(raw, dictionary=d) == "So the Supabase migration is, done.\n\nCan you check it?"


def test_remove_fillers_keeps_words_containing_fillers():
    assert "humming" in remove_fillers("humming along")
    assert "ahead" in remove_fillers("go ahead")
