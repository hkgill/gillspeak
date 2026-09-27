"""Upgrading from Murmur (the name before 0.6.0) keeps the user's folders and stored keys."""

import sys
import types

import pytest

from gillspeak import paths, secrets


def test_old_folders_are_moved_over(tmp_path, monkeypatch):
    for var, sub in (("XDG_CONFIG_HOME", "cfg"), ("XDG_DATA_HOME", "data"), ("XDG_CACHE_HOME", "cache")):
        monkeypatch.setenv(var, str(tmp_path / sub))
    (tmp_path / "cfg" / "murmur").mkdir(parents=True)
    (tmp_path / "cfg" / "murmur" / "dictionary.toml").write_text("[replace]\n")
    (tmp_path / "data" / "murmur" / "models").mkdir(parents=True)

    assert paths.dictionary_file().read_text() == "[replace]\n"
    assert paths.dictionary_file() == tmp_path / "cfg" / "gillspeak" / "dictionary.toml"
    assert (paths.data_dir() / "models").is_dir()
    assert not (tmp_path / "cfg" / "murmur").exists()
    assert paths.cache_dir() == tmp_path / "cache" / "gillspeak"  # nothing old to move


def test_new_folder_wins_over_an_old_one(tmp_path, monkeypatch):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path))
    (tmp_path / "murmur").mkdir()
    (tmp_path / "gillspeak").mkdir()
    assert paths.config_dir() == tmp_path / "gillspeak"
    assert (tmp_path / "murmur").exists()  # left alone, never merged or deleted


@pytest.fixture
def fake_keyring(monkeypatch):
    store = {}
    mod = types.SimpleNamespace(
        get_password=lambda svc, user: store.get((svc, user)),
        set_password=lambda svc, user, value: store.__setitem__((svc, user), value),
    )
    monkeypatch.setitem(sys.modules, "keyring", mod)
    return store


def test_key_stored_by_murmur_is_found_and_copied(fake_keyring, monkeypatch):
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    fake_keyring[("murmur", secrets.GEMINI_USER)] = "old-key"
    assert secrets.get_api_key() == "old-key"
    assert fake_keyring[("gillspeak", secrets.GEMINI_USER)] == "old-key"


def test_new_key_wins(fake_keyring):
    fake_keyring[("murmur", secrets.GEMINI_USER)] = "old-key"
    fake_keyring[("gillspeak", secrets.GEMINI_USER)] = "new-key"
    assert secrets.get_api_key() == "new-key"


def test_old_proxy_token_in_env_file(monkeypatch):
    monkeypatch.setattr(secrets, "_keyring_get", lambda user: None)
    monkeypatch.delenv("GILLSPEAK_PROXY_TOKEN", raising=False)
    secrets.write_env_file("MURMUR_PROXY_TOKEN", "tok")
    assert secrets.get_proxy_token() == "tok"
