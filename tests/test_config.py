import tomllib

import pytest

from murmur import paths
from murmur.config import DEFAULT_CONFIG_TOML, Config, ConfigError, from_dict, load, load_dictionary


def test_default_toml_matches_dataclass_defaults():
    cfg = from_dict(tomllib.loads(DEFAULT_CONFIG_TOML))
    default = Config()
    default.llm.instructions = cfg.llm.instructions  # the TOML ships an example instruction
    assert cfg == default


def test_first_run_creates_files():
    cfg = load()
    assert paths.config_file().exists()
    assert paths.dictionary_file().exists()
    assert cfg.gate.min_words == 12
    d = load_dictionary()
    assert d.replace["super base"] == "Supabase"
    assert "Supabase" in d.bias


def test_partial_override_and_int_to_float():
    cfg = from_dict({"llm": {"timeout_s": 3, "model": "gemini-x"}, "modes": {"pirate": "Talk like a pirate."}})
    assert cfg.llm.timeout_s == 3.0 and isinstance(cfg.llm.timeout_s, float)
    assert cfg.llm.model == "gemini-x"
    assert cfg.llm.connect_timeout_s == 1.0
    assert set(cfg.modes) >= {"default", "formal", "casual", "pirate"}


@pytest.mark.parametrize(
    "data",
    [
        {"gate": {"min_words": "twelve"}},
        {"audio": {"keep_mic_open": 1}},
        {"gate": {"min_words": True}},
        {"llm": {"provider": "openai"}},
        {"llm": {"extra_generation_config": "{not json"}},
        {"inject": {"paste_chord": "ctrl+insert"}},
        {"llm": {"provider": "proxy"}},
        {"audio": "loud"},
    ],
)
def test_invalid(data):
    with pytest.raises(ConfigError):
        from_dict(data)


def test_unknown_key_warns(caplog):
    from_dict({"gate": {"min_wordz": 3}})
    assert "gate.min_wordz" in caplog.text


def test_bad_toml(tmp_path):
    p = tmp_path / "c.toml"
    p.write_text("[audio\n")
    with pytest.raises(ConfigError):
        load(p)


def test_extra_generation_config_parsed():
    cfg = from_dict({"llm": {"extra_generation_config": '{"thinkingConfig": {"thinkingLevel": "minimal"}}'}})
    assert cfg.llm.extra_generation_dict() == {"thinkingConfig": {"thinkingLevel": "minimal"}}


def test_shipped_defaults_are_the_tested_setup():
    """Regression: defaults shipped an alias model (behaviour could change silently) and an always-open mic."""
    cfg = Config()
    assert not cfg.llm.model.endswith("latest") and cfg.llm.model == "gemini-3.5-flash-lite"
    assert cfg.audio.keep_mic_open is False
    assert cfg.hotkey.hold_to_talk is True and cfg.hotkey.keyd_socket == "/run/murmur-keyd/socket"


def test_hotkey_section_types_are_checked():
    with pytest.raises(ConfigError):
        from_dict({"hotkey": {"hold_to_talk": "yes"}})
    assert from_dict({"hotkey": {"hold_to_talk": False}}).hotkey.hold_to_talk is False
