"""API key lookup order and storage: keyring first, then environment, then ~/.config/murmur/env (0600)."""

import io
import os
import stat
import sys
import types

import pytest

from murmur import paths, secrets


@pytest.fixture
def no_keyring(monkeypatch):
    monkeypatch.setattr(secrets, "_keyring_get", lambda user: None)


@pytest.fixture
def fake_keyring(monkeypatch):
    store = {}
    mod = types.SimpleNamespace(
        get_password=lambda svc, user: store.get((svc, user)),
        set_password=lambda svc, user, value: store.__setitem__((svc, user), value),
    )
    monkeypatch.setitem(sys.modules, "keyring", mod)
    return store


def test_lookup_order(monkeypatch, no_keyring):
    assert secrets.get_api_key() is None
    secrets.write_env_file("GEMINI_API_KEY", "from-file")
    assert secrets.get_api_key() == "from-file"
    monkeypatch.setenv("GEMINI_API_KEY", "from-env")
    assert secrets.get_api_key() == "from-env"
    monkeypatch.setattr(secrets, "_keyring_get", lambda user: "from-keyring")
    assert secrets.get_api_key() == "from-keyring"


def test_env_file_is_private_and_updates_in_place(no_keyring):
    secrets.write_env_file("GEMINI_API_KEY", "one")
    secrets.write_env_file("MURMUR_PROXY_TOKEN", "tok")
    secrets.write_env_file("GEMINI_API_KEY", "two")
    path = paths.env_file()
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    assert path.read_text() == "MURMUR_PROXY_TOKEN=tok\nGEMINI_API_KEY=two\n"
    assert secrets.get_api_key() == "two" and secrets.get_proxy_token() == "tok"


def test_env_file_tolerates_quotes_blanks_and_comments(no_keyring):
    paths.config_dir().mkdir(parents=True)
    paths.env_file().write_text("# comment\n\nGEMINI_API_KEY = 'quoted'\nOTHER=1\n")
    assert secrets.get_api_key() == "quoted"
    paths.env_file().write_text("GEMINI_API_KEY=\n")
    assert secrets.get_api_key() is None


def test_keyring_errors_fall_through(monkeypatch):
    def broken(svc, user):
        raise RuntimeError("no Secret Service")

    monkeypatch.setitem(sys.modules, "keyring", types.SimpleNamespace(get_password=broken))
    monkeypatch.setenv("GEMINI_API_KEY", "env")
    assert secrets.get_api_key() == "env"


def test_set_key_prefers_keyring(monkeypatch, fake_keyring):
    monkeypatch.setattr(sys, "stdin", io.StringIO("  sekrit \n"))
    assert secrets.set_key() == "keyring (libsecret)"
    assert fake_keyring[("murmur", "gemini_api_key")] == "sekrit"
    assert not paths.env_file().exists()


def test_set_key_falls_back_to_env_file(monkeypatch):
    def broken(*a):
        raise RuntimeError("locked")

    monkeypatch.setitem(sys.modules, "keyring", types.SimpleNamespace(set_password=broken, get_password=broken))
    monkeypatch.setattr(sys, "stdin", io.StringIO("sekrit\n"))
    assert secrets.set_key() == str(paths.env_file())
    assert paths.env_file().read_text() == "GEMINI_API_KEY=sekrit\n"


def test_set_key_rejects_empty(monkeypatch):
    monkeypatch.setattr(sys, "stdin", io.StringIO("\n"))
    with pytest.raises(ValueError, match="empty"):
        secrets.set_key()
