import asyncio

import pytest

from murmur import inject
from murmur.config import InjectConfig
from murmur.inject import CHORD_KEYS, InjectError, WaylandInjector, X11Injector, is_typeable


class FakeRun:
    def __init__(self, clipboard=None, ydotool_rc=0, ydotool_err=b""):
        self.calls = []
        self.clipboard = clipboard
        self.ydotool_rc = ydotool_rc
        self.ydotool_err = ydotool_err

    async def __call__(self, argv, stdin=None, *, capture=True, timeout=3.0, env=None):
        self.calls.append({"argv": argv, "stdin": stdin, "capture": capture, "env": env})
        tool = argv[0]
        if tool == "wl-paste":
            if "--list-types" in argv:
                return (0, b"text/plain;charset=utf-8\nUTF8_STRING\n", b"") if self.clipboard is not None else (1, b"", b"")
            return 0, (self.clipboard or "").encode(), b""
        if tool == "wl-copy":
            self.clipboard = stdin.decode()
            return 0, b"", b""
        if tool == "ydotool":
            return self.ydotool_rc, b"", self.ydotool_err
        return 0, b"", b""

    def argvs(self, tool):
        return [c for c in self.calls if c["argv"][0] == tool]


@pytest.fixture
def ysock(tmp_path, monkeypatch):
    sock = tmp_path / "ydotool.sock"
    sock.touch()
    monkeypatch.setenv("YDOTOOL_SOCKET", str(sock))
    return sock


def cfg(**kw):
    base = dict(settle_ms=0, restore_delay_ms=0)
    base.update(kw)
    return InjectConfig(**base)


async def test_paste_goes_via_stdin_and_sends_ctrl_v(monkeypatch, ysock):
    fake = FakeRun(clipboard=None)
    monkeypatch.setattr(inject, "run", fake)
    await WaylandInjector(cfg()).insert("Hello — world")
    copy = fake.argvs("wl-copy")[0]
    assert copy["stdin"] == "Hello — world".encode()
    assert "Hello — world" not in " ".join(copy["argv"])
    assert copy["capture"] is False  # wl-copy forks; we must not wait on its pipes
    ydo = fake.argvs("ydotool")[0]
    assert ydo["argv"] == ["ydotool", "key", "29:1", "47:1", "47:0", "29:0"]
    assert ydo["env"]["YDOTOOL_SOCKET"] == str(ysock)


async def test_ctrl_shift_v_chord(monkeypatch, ysock):
    fake = FakeRun()
    monkeypatch.setattr(inject, "run", fake)
    await WaylandInjector(cfg()).insert("x", chord="ctrl+shift+v")
    assert fake.argvs("ydotool")[0]["argv"][2:] == ["29:1", "42:1", "47:1", "47:0", "42:0", "29:0"]
    assert CHORD_KEYS["ctrl+shift+v"] == ["29:1", "42:1", "47:1", "47:0", "42:0", "29:0"]


async def test_clipboard_restored(monkeypatch, ysock):
    fake = FakeRun(clipboard="previous")
    monkeypatch.setattr(inject, "run", fake)
    inj = WaylandInjector(cfg())
    await inj.insert("dictated")
    await inj._restore_task
    assert fake.clipboard == "previous"


async def test_clipboard_not_restored_if_user_copied_meanwhile(monkeypatch, ysock):
    fake = FakeRun(clipboard="previous")
    monkeypatch.setattr(inject, "run", fake)
    inj = WaylandInjector(cfg(restore_delay_ms=50))
    await inj.insert("dictated")
    fake.clipboard = "user copied this"
    await inj._restore_task
    assert fake.clipboard == "user copied this"


async def test_restore_disabled(monkeypatch, ysock):
    fake = FakeRun(clipboard="previous")
    monkeypatch.setattr(inject, "run", fake)
    await WaylandInjector(cfg(restore_clipboard=False)).insert("dictated")
    assert fake.clipboard == "dictated"
    assert not fake.argvs("wl-paste")


async def test_missing_ydotool_socket_leaves_text_on_clipboard(monkeypatch, tmp_path):
    monkeypatch.setenv("YDOTOOL_SOCKET", str(tmp_path / "nope"))
    fake = FakeRun()
    monkeypatch.setattr(inject, "run", fake)
    with pytest.raises(InjectError) as e:
        await WaylandInjector(cfg()).insert("keep me")
    assert e.value.copied is True
    assert fake.clipboard == "keep me"


async def test_ydotool_failure_detected(monkeypatch, ysock):
    fake = FakeRun(ydotool_err=b"failed to connect socket `/tmp/.ydotool_socket': Connection refused")
    monkeypatch.setattr(inject, "run", fake)
    with pytest.raises(InjectError) as e:
        await WaylandInjector(cfg()).insert("x")
    assert e.value.copied and e.value.kind == "ydotool"


async def test_type_method_short_ascii(monkeypatch, ysock):
    fake = FakeRun()
    monkeypatch.setattr(inject, "run", fake)
    await WaylandInjector(cfg(method="type")).insert("hello world")
    ydo = fake.argvs("ydotool")[0]
    assert ydo["argv"] == ["ydotool", "type", "--key-delay", "2", "--file", "-"]
    assert ydo["stdin"] == b"hello world"
    assert not fake.argvs("wl-copy")


async def test_type_method_falls_back_to_paste_for_unicode(monkeypatch, ysock):
    fake = FakeRun()
    monkeypatch.setattr(inject, "run", fake)
    await WaylandInjector(cfg(method="type")).insert("café")
    assert fake.argvs("wl-copy")


def test_is_typeable():
    assert is_typeable("plain ascii")
    assert not is_typeable("naïve")
    assert not is_typeable("two\nlines")
    assert not is_typeable("x" * 300)


async def test_x11(monkeypatch):
    fake = FakeRun()
    monkeypatch.setattr(inject, "run", fake)
    await X11Injector(cfg(restore_clipboard=False)).insert("hi")
    argvs = [c["argv"] for c in fake.calls]
    assert argvs[0] == ["xclip", "-selection", "clipboard", "-i"]
    assert fake.calls[0]["stdin"] == b"hi"
    assert argvs[1] == ["xdotool", "key", "--clearmodifiers", "ctrl+v"]


async def test_real_run_does_not_hang_on_forking_child():
    # A child that forks and keeps stdout open must not block run(capture=False).
    code, _, _ = await asyncio.wait_for(
        inject.run(["sh", "-c", "sleep 5 & exit 0"], stdin=b"", capture=False), timeout=2.0
    )
    assert code == 0


def test_session_type(monkeypatch):
    monkeypatch.setenv("XDG_SESSION_TYPE", "x11")
    assert inject.session_type() == "x11"
    assert isinstance(inject.make_injector(InjectConfig()), X11Injector)
    monkeypatch.setenv("XDG_SESSION_TYPE", "wayland")
    assert isinstance(inject.make_injector(InjectConfig()), WaylandInjector)


def test_ydotool_socket_discovery(monkeypatch, tmp_path):
    monkeypatch.delenv("YDOTOOL_SOCKET", raising=False)
    monkeypatch.setattr(inject, "SETUP_SOCKET", tmp_path / "missing")
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    assert str(inject.ydotool_socket()) == "/tmp/.ydotool_socket"
    (tmp_path / ".ydotool_socket").touch()
    assert inject.ydotool_socket() == tmp_path / ".ydotool_socket"
    setup = tmp_path / "setup.sock"
    setup.touch()
    monkeypatch.setattr(inject, "SETUP_SOCKET", setup)
    assert inject.ydotool_socket() == setup
    monkeypatch.setenv("YDOTOOL_SOCKET", "/custom")
    assert str(inject.ydotool_socket()) == "/custom"
