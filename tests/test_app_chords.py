"""Choosing the paste chord from the focused window on Hyprland."""

import json

import pytest

from gillspeak import inject
from gillspeak.config import Config, ConfigError, InjectConfig, from_dict
from gillspeak.inject import CHORD_KEYS, WaylandInjector
from tests.test_inject import FakeRun


class FakeHyprland(FakeRun):
    def __init__(self, window=None, hyprctl_rc=0):
        super().__init__()
        self.window, self.hyprctl_rc = window, hyprctl_rc

    async def __call__(self, argv, stdin=None, *, capture=True, timeout=3.0, env=None):
        if argv[0] == "hyprctl":
            self.calls.append({"argv": argv})
            return self.hyprctl_rc, json.dumps(self.window).encode(), b""
        return await super().__call__(argv, stdin, capture=capture, timeout=timeout, env=env)


@pytest.fixture
def paste(tmp_path, monkeypatch):
    sock = tmp_path / "ydotool.sock"
    sock.touch()
    monkeypatch.setenv("YDOTOOL_SOCKET", str(sock))
    monkeypatch.setenv("HYPRLAND_INSTANCE_SIGNATURE", "test")

    async def run(fake, chord=None, **kw):
        monkeypatch.setattr(inject, "run", fake)
        injector = WaylandInjector(InjectConfig(settle_ms=0, restore_clipboard=False, **kw))
        await injector.insert("hello", chord)
        return fake.argvs("ydotool")[-1]["argv"][2:]

    return run


@pytest.mark.parametrize("app", ["foot", "Alacritty", "com.mitchellh.ghostty", "org.omarchy.btop", "TUI.float", "org.wezfurlong.wezterm"])
async def test_terminals_paste_with_ctrl_shift_v(paste, app):
    assert await paste(FakeHyprland({"class": app})) == CHORD_KEYS["ctrl+shift+v"]


async def test_other_apps_use_the_default_chord(paste):
    assert await paste(FakeHyprland({"class": "chromium"})) == CHORD_KEYS["ctrl+v"]


async def test_an_explicit_chord_wins(paste):
    assert await paste(FakeHyprland({"class": "foot"}), chord="ctrl+v") == CHORD_KEYS["ctrl+v"]


async def test_a_custom_table_replaces_the_list(paste):
    keys = await paste(FakeHyprland({"class": "myeditor"}), app_chords={"myeditor": "ctrl+shift+v"})
    assert keys == CHORD_KEYS["ctrl+shift+v"]


@pytest.mark.parametrize("fake", [FakeHyprland({}), FakeHyprland(None), FakeHyprland({"class": "foot"}, hyprctl_rc=1)])
async def test_no_answer_from_hyprland_falls_back(paste, fake):
    assert await paste(fake) == CHORD_KEYS["ctrl+v"]


async def test_outside_hyprland_nothing_is_asked(paste, monkeypatch):
    monkeypatch.delenv("HYPRLAND_INSTANCE_SIGNATURE")
    fake = FakeHyprland({"class": "foot"})
    assert await paste(fake) == CHORD_KEYS["ctrl+v"]
    assert not fake.argvs("hyprctl")


def test_defaults_cover_terminals():
    assert Config().inject.app_chords["foot"] == "ctrl+shift+v"


def test_a_bad_chord_is_a_config_error():
    with pytest.raises(ConfigError, match="app_chords"):
        from_dict({"inject": {"app_chords": {"foot": "ctrl+x"}}})
