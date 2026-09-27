import pytest


@pytest.fixture(autouse=True)
def isolated_xdg(tmp_path, monkeypatch):
    """Never touch the real ~/.config, ~/.local/share or the real daemon socket."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    monkeypatch.setenv("GILLSPEAK_SOCKET", str(tmp_path / "gillspeak.sock"))
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
