import pytest

from gillspeak.config import Dictionary, RulesConfig
from gillspeak.rules import (
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


SIGN_OFF = "Thanks,\n\nSam  Doe"


def test_snippet_said_alone_comes_out_exactly():
    d = Dictionary(snippets={"my sign off": SIGN_OFF})
    assert clean("My sign off.", dictionary=d) == SIGN_OFF


def test_snippet_inside_a_sentence():
    d = Dictionary(snippets={"my link": "https://cal.example.com/me"})
    assert clean("here is my link, ok", dictionary=d) == "Here is https://cal.example.com/me, ok"


def test_snippet_whole_phrase_only():
    d = Dictionary(snippets={"my link": "URL"})
    assert clean("those are my links", dictionary=d) == "Those are my links"


def test_dictionary_leaves_snippet_text_alone():
    d = Dictionary(snippets={"my tool": "the super base tool"}, replace={"super base": "Supabase"})
    assert clean("my tool", dictionary=d) == "the super base tool"


def test_snippet_at_the_start_does_not_capitalise_the_next_word():
    d = Dictionary(snippets={"my link": "URL"})
    assert clean("my link is here", dictionary=d) == "URL is here"


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("snake case user name", "user_name"),
        ("snake case User Name.", "user_name"),
        ("kebab case main menu item", "main-menu-item"),
        ("camel case user name", "userName"),
        ("pascal case http client", "HttpClient"),
        ("constant case max retry count", "MAX_RETRY_COUNT"),
        ("Set camel case user name, then save.", "Set userName, then save."),
        ("The snake case user name is required", "The user_name is required"),
        ("call snake case get the value end case right now", "Call get_the_value right now"),
        ("snake case first line new line second", "first_line\nSecond"),
    ],
)
def test_code_case(raw, expected):
    assert clean(raw) == expected


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("We prefer snake case.", "We prefer snake case."),
        ("Use snake case for names", "Use snake case for names"),
        ("camel case is fine", "Camel case is fine"),
        ("camel case names are bad", "Camel case names are bad"),
    ],
)
def test_talking_about_a_case_style_is_left_alone(raw, expected):
    assert clean(raw) == expected


def test_code_case_stops_after_the_word_limit():
    assert clean("snake case a b c d e f g") == "a_b_c_d_e_f g"


def test_code_case_can_be_turned_off():
    assert clean("snake case user name", disabled_commands=["code case"]) == "Snake case user name"
    assert clean("snake case user name", spoken_commands=False) == "Snake case user name"


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("Hello there. I want pizza, scratch that. I want pasta.", "Hello there. I want pasta."),
        ("I want pizza, no, scratch that, I want pasta", "I want pasta"),
        ("I want pizza. Scratch that. I want pasta.", "I want pasta."),
        ("Hello there. I want pizza. Scratch that. I want pasta.", "Hello there. I want pasta."),
        ("I want pizza scratch that", ""),
        ("line one new line line two scratch that", "Line one\n"),
        ("It costs 3.5 dollars, scratch that. It's free.", "It's free."),
        ("One thing. Scratch that. Another. Scratch that. Done.", "Done."),
    ],
)
def test_scratch_that(raw, expected):
    assert clean(raw) == expected


def test_scratch_that_as_ordinary_words_is_left_alone():
    assert clean("Just scratch that itch") == "Just scratch that itch"


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("Meet at 2, actually 3.", "Meet at 3."),
        ("Meet at 2... actually 3", "Meet at 3"),
        ("Meet at 3 pm sorry 4 pm", "Meet at 4 pm"),
        ("The meeting is on Thursday sorry Friday at 3", "The meeting is on Friday at 3"),
        ("Order two, actually three, boxes", "Order three, boxes"),
        ("twenty five actually thirty people", "Thirty people"),
        ("It costs $2 actually $3", "It costs $3"),
        ("It is 3.5, I mean 3.6", "It is 3.6"),
        ("Back in March, or rather April", "Back in April"),
    ],
)
def test_value_corrections(raw, expected):
    assert clean(raw) == expected


@pytest.mark.parametrize(
    "text",
    [
        "I actually went on Friday",
        "It costs 5. Actually 6 people came.",
        "Two, actually, is plenty",
        "Monday, actually March",
        "Sorry, 3 is fine",
    ],
)
def test_ordinary_actually_is_left_alone(text):
    assert clean(text) == text


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("Numbered list. First, buy milk. Second, call Sam. Third, send the report.", "1. Buy milk\n2. Call Sam\n3. Send the report"),
        ("Today's plan numbered list first buy milk second call Sam", "Today's plan\n1. Buy milk\n2. Call Sam"),
        ("numbered list buy milk next item call Sam next item send report", "1. Buy milk\n2. Call Sam\n3. Send report"),
        ("numbered list number one buy milk number two call Sam", "1. Buy milk\n2. Call Sam"),
        ("numbered list first buy milk and second call Sam", "1. Buy milk\n2. Call Sam"),
        ("numbered list first a thing second b thing end list and that is all", "1. A thing\n2. B thing\nAnd that is all"),
    ],
)
def test_numbered_list(raw, expected):
    assert clean(raw) == expected


@pytest.mark.parametrize(
    "text",
    ["A numbered list is useful", "Numbered list first buy milk", "We came first and they came second"],
)
def test_no_list_without_the_trigger_and_two_items(text):
    assert clean(text) == text


@pytest.mark.parametrize(
    "raw, name, expected",
    [
        ("Meet at 2, actually 3", "corrections", "Meet at 2, actually 3"),
        ("I want pizza, scratch that", "scratch that", "I want pizza, scratch that"),
        ("numbered list one next item two", "numbered list", "Numbered list one next item two"),
    ],
)
def test_editing_commands_can_be_turned_off(raw, name, expected):
    assert clean(raw, disabled_commands=[name]) == expected
    assert clean(raw, spoken_commands=False) == expected


def test_remove_fillers_keeps_words_containing_fillers():
    assert "humming" in remove_fillers("humming along")
    assert "ahead" in remove_fillers("go ahead")
