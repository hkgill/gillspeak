"""gillspeak-keyd: hold detection, keyboard detection, and gillspeakd's handling of hold events."""

import asyncio
import json

from gillspeak.keyd import KEY_A, KEY_RIGHTALT, KEY_RIGHTCTRL, HoldDetector, _bit, is_keyboard

from .test_ipc import make_daemon  # noqa: F401  (fixture)

RC, RA = KEY_RIGHTCTRL, KEY_RIGHTALT
KEY_T, KEY_LEFTSHIFT = 20, 42


def run(det, steps):
    """steps: (t, code, value) for key events or (t,) for a tick. Returns every emitted event."""
    out = []
    for step in steps:
        if len(step) == 1:
            out += det.tick(step[0])
        else:
            t, code, value = step
            out += det.key(code, value, t)
    return [e for e in out if e not in ("down", "up")]  # chord-state events have their own tests below


def test_hold_then_release_starts_and_ends():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RC, 1), (0.05, RA, 1), (0.2,), (0.36,), (2.0, RA, 0), (2.01, RC, 0)]) == ["start", "end"]


def test_either_order_and_end_waits_for_both_keys():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RA, 1), (0.0, RC, 1), (0.4,), (1.0, RC, 0)]) == ["start"]  # Alt still down
    assert run(det, [(1.1, RA, 0)]) == ["end"]


def test_quick_tap_does_nothing():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RC, 1), (0.0, RA, 1), (0.1, RA, 0), (0.1, RC, 0), (1.0,)]) == []


def test_autorepeat_is_ignored():
    det = HoldDetector(hold_s=0.3)
    steps = [(0.0, RC, 1), (0.0, RA, 1), (0.4,)] + [(0.5 + i * 0.03, RA, 2) for i in range(20)] + [(1.5, RA, 0), (1.5, RC, 0)]
    assert run(det, steps) == ["start", "end"]


def test_third_key_before_start_is_a_normal_shortcut():
    det = HoldDetector(hold_s=0.3)
    steps = [(0.0, RC, 1), (0.0, RA, 1), (0.1, KEY_T, 1), (0.15, KEY_T, 0), (0.5,), (1.0, RA, 0), (1.0, RC, 0)]
    assert run(det, steps) == []


def test_third_key_after_start_cancels_and_stays_quiet_until_released():
    det = HoldDetector(hold_s=0.3)
    steps = [(0.0, RC, 1), (0.0, RA, 1), (0.4,), (0.6, KEY_T, 1), (0.7, KEY_T, 0), (2.0,), (2.5, RA, 0), (2.5, RC, 0)]
    assert run(det, steps) == ["start", "cancel"]
    # Once both are released, the next hold works again.
    assert run(det, [(3.0, RC, 1), (3.0, RA, 1), (3.4,), (4.0, RC, 0), (4.0, RA, 0)]) == ["start", "end"]


def test_chord_pressed_while_another_key_is_held_does_not_arm():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, KEY_LEFTSHIFT, 1), (0.1, RC, 1), (0.1, RA, 1), (1.0,)]) == []


def test_left_modifiers_do_not_count():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, 29, 1), (0.0, 56, 1), (1.0,)]) == []  # Left Ctrl + Left Alt


def test_deadline_and_reset():
    det = HoldDetector(hold_s=0.3)
    assert det.deadline() is None
    det.key(RC, 1, 1.0)
    det.key(RA, 1, 1.0)
    assert det.deadline() == 1.3
    det.key(RA, 0, 1.1)  # let go of one before the hold counts, then press again
    assert det.deadline() is None
    det.key(RA, 1, 1.0)
    assert det.tick(1.3) == ["start"]
    assert det.deadline() is None
    assert det.reset() == ["cancel", "up"]  # keyboard unplugged mid-hold
    assert det.down == set() and not det.active


def test_bit_reads_sysfs_words():
    mask = "1 0"  # bit 64 set in the upper word
    assert _bit(mask, 64) and not _bit(mask, 0) and not _bit(mask, 200)
    assert _bit("402000000 3803078f800d001 feffffdfffefffff fffffffffffffffe", KEY_A)


def _fake_input(tmp_path, name, dev_name, keys):
    words = [0, 0]
    for k in keys:
        words[1 - k // 64] |= 1 << (k % 64)
    d = tmp_path / name / "device"
    (d / "capabilities").mkdir(parents=True)
    (d / "name").write_text(dev_name + "\n")
    (d / "capabilities" / "key").write_text(" ".join(f"{w:x}" for w in words) + "\n")


def test_is_keyboard(tmp_path):
    _fake_input(tmp_path, "event3", "AT Translated Set 2 keyboard", [KEY_A, RC, RA])
    _fake_input(tmp_path, "event2", "Power Button", [116])
    _fake_input(tmp_path, "event15", "ydotoold virtual device", [KEY_A, RC, RA])
    assert is_keyboard("event3", tmp_path)
    assert not is_keyboard("event2", tmp_path)
    assert not is_keyboard("event15", tmp_path)  # our own paste keystrokes
    assert not is_keyboard("event99", tmp_path)


# -- gillspeakd side ----------------------------------------------------------------


async def test_on_hold_records_then_pastes(make_daemon):  # noqa: F811
    d = make_daemon()
    await d.on_hold("start")
    assert d.state == "recording" and d.holding
    await d.on_hold("end")
    assert d.state == "processing" and not d.holding
    await d.process(d.queue.get_nowait())
    assert d.injector.inserted


async def test_on_hold_cancel_discards(make_daemon):  # noqa: F811
    d = make_daemon()
    await d.on_hold("start")
    await d.on_hold("cancel")
    assert d.state == "idle" and d.queue.empty() and not d.injector.inserted


async def test_hold_does_not_touch_a_ctrl_space_recording(make_daemon):  # noqa: F811
    d = make_daemon()
    await d.handle({"cmd": "toggle"})  # hands-free recording already running
    await d.on_hold("start")
    await d.on_hold("end")
    await d.on_hold("cancel")
    assert d.state == "recording" and not d.holding


async def test_stray_end_is_ignored(make_daemon):  # noqa: F811
    d = make_daemon()
    await d.on_hold("end")
    await d.on_hold("bogus")
    assert d.state == "idle"


async def test_listener_follows_socket_and_cancels_if_helper_dies(make_daemon, tmp_path, monkeypatch):  # noqa: F811
    monkeypatch.setattr("gillspeak.daemon.KEYD_RETRY_S", 0.01)
    d = make_daemon()
    sock = tmp_path / "keyd.sock"
    conns = []

    async def on_conn(reader, writer):
        conns.append(writer)

    server = await asyncio.start_unix_server(on_conn, path=str(sock))
    task = asyncio.create_task(d.keyd_listener(str(sock)))
    try:
        for _ in range(100):
            if conns:
                break
            await asyncio.sleep(0.01)
        w = conns[0]
        w.write((json.dumps({"event": "start"}) + "\nnot json\n").encode())
        await w.drain()
        for _ in range(100):
            if d.state == "recording":
                break
            await asyncio.sleep(0.01)
        assert d.state == "recording" and d.holding
        w.close()  # helper restarts mid-hold: the recording must not run on
        for _ in range(100):
            if d.state == "idle":
                break
            await asyncio.sleep(0.01)
        assert d.state == "idle" and d.queue.empty()
    finally:
        task.cancel()
        server.close()


# -- Server: real socket, a FIFO standing in for /dev/input/eventN ----------------

import os  # noqa: E402
import socket  # noqa: E402
import stat as stat_mod  # noqa: E402
import time  # noqa: E402

import pytest  # noqa: E402

from gillspeak import keyd  # noqa: E402


@pytest.fixture
def server(tmp_path):
    sys_root, input_dir = tmp_path / "sys", tmp_path / "input"
    input_dir.mkdir()
    _fake_input(sys_root, "event3", "AT Translated Set 2 keyboard", [KEY_A, RC, RA])
    _fake_input(sys_root, "event15", "ydotoold virtual device", [KEY_A, RC, RA])
    for name in ("event3", "event15"):
        os.mkfifo(input_dir / name)
    srv = keyd.Server(tmp_path / "keyd.sock", os.getuid(), os.getgid(), HoldDetector(hold_s=0.05),
                      input_dir=input_dir, sys_root=sys_root)
    writer = None

    def open_writer():
        nonlocal writer
        writer = os.open(input_dir / "event3", os.O_WRONLY | os.O_NONBLOCK)
        return writer

    srv.open_writer = open_writer
    yield srv
    if writer is not None:
        try:
            os.close(writer)
        except OSError:
            pass
    for c in list(srv.clients):
        c.close()
    srv.listener.close()


def _event(code, value, typ=keyd.EV_KEY):
    return keyd.EVENT.pack(0, 0, typ, code, value)


def _pump(srv, until, limit=2.0):
    """Run step() until `until()` is true (or fail)."""
    end = time.monotonic() + limit
    next_scan = time.monotonic() + 60  # scanning is driven explicitly by the tests
    while time.monotonic() < end:
        next_scan = srv.step(time.monotonic(), next_scan)
        if until():
            return
    raise AssertionError("condition not reached")


def _client(srv):
    c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    c.connect(str(srv.sock_path))
    _pump(srv, lambda: len(srv.clients) == 1)
    c.settimeout(2.0)
    return c


def test_socket_is_private(server):
    mode = os.stat(server.sock_path).st_mode
    assert stat_mod.S_ISSOCK(mode) and stat_mod.S_IMODE(mode) == 0o600
    assert os.stat(server.sock_path).st_uid == os.getuid()


def test_scan_watches_real_keyboards_only(server):
    server.scan()
    assert list(server.devices) == ["event3"]  # ydotoold's virtual device is skipped


def test_hold_is_broadcast_end_to_end(server):
    server.scan()
    w = server.open_writer()
    c = _client(server)
    os.write(w, _event(RC, 1) + _event(RA, 1) + _event(0, 0, typ=0))  # SYN event is ignored
    buf = b""
    end = time.monotonic() + 2
    while b"start" not in buf and time.monotonic() < end:
        server.step(time.monotonic(), time.monotonic() + 60)
        try:
            c.setblocking(False)
            buf += c.recv(4096)
        except BlockingIOError:
            pass
    assert b'{"event": "down"}\n{"event": "start"}\n' in buf
    os.write(w, _event(RA, 0) + _event(RC, 0))
    c.setblocking(True)
    _pump(server, lambda: not server.detector.chord_down)
    assert c.recv(4096) == b'{"event": "end"}\n{"event": "up"}\n'


def test_unplugged_keyboard_cancels_hold_and_does_not_spin(server):
    """Regression: EOF from a device used to leave it registered, spinning select() at 100% CPU."""
    server.scan()
    w = server.open_writer()
    c = _client(server)
    os.write(w, _event(RC, 1) + _event(RA, 1))
    _pump(server, lambda: server.detector.active)
    assert c.recv(4096) == b'{"event": "down"}\n{"event": "start"}\n'
    os.close(w)  # writer gone: the FIFO now reads EOF, as a vanished device would
    _pump(server, lambda: not server.devices)
    assert c.recv(4096) == b'{"event": "cancel"}\n{"event": "up"}\n'
    assert not server.detector.active and not server.detector.down


def test_disconnected_client_is_dropped(server):
    c = _client(server)
    c.close()
    _pump(server, lambda: not server.clients)
    server.emit(["start"])  # no error with zero clients


def test_stale_socket_file_is_replaced(tmp_path):
    path = tmp_path / "keyd.sock"
    path.write_text("stale")
    srv = keyd.Server(path, os.getuid(), os.getgid(), HoldDetector(), input_dir=tmp_path, sys_root=tmp_path)
    try:
        assert stat_mod.S_ISSOCK(os.stat(path).st_mode)
    finally:
        srv.listener.close()


def test_keyd_is_standalone():
    """It runs as root from a copy in /usr/local/libexec, so it must not import the gillspeak package."""
    from pathlib import Path

    src = Path(keyd.__file__).read_text()
    assert "from ." not in src and "import gillspeak" not in src and "from gillspeak" not in src


# -- chord down/up (0.5.1) -------------------------------------------------------------


def run_all(det, steps):
    """Like run(), but keep the down/up chord-state events."""
    out = []
    for step in steps:
        out += det.tick(step[0]) if len(step) == 1 else det.key(step[1], step[2], step[0])
    return out


def test_chord_down_and_up_bracket_every_hold():
    """Regression (Codex review #5): gillspeakd had no way to know the chord was still physically held
    after an auto-stop or cancel, so it could paste while Ctrl+Alt were down."""
    det = HoldDetector(hold_s=0.3)
    got = run_all(det, [(0.0, RC, 1), (0.0, RA, 1), (0.4,), (1.0, RC, 0), (1.1, RA, 0)])
    assert got == ["down", "start", "end", "up"]


def test_up_waits_for_both_keys_even_after_cancel():
    det = HoldDetector(hold_s=0.3)
    got = run_all(det, [(0.0, RC, 1), (0.0, RA, 1), (0.4,), (0.5, KEY_T, 1), (0.6, KEY_T, 0), (0.7, RC, 0)])
    assert got == ["down", "start", "cancel"]  # Right Alt still held
    assert run_all(det, [(0.8, RA, 0)]) == ["up"]


def test_quick_tap_reports_down_up_only():
    det = HoldDetector(hold_s=0.3)
    assert run_all(det, [(0.0, RC, 1), (0.0, RA, 1), (0.1, RA, 0), (0.1, RC, 0)]) == ["down", "up"]


def test_reset_releases_the_chord():
    det = HoldDetector(hold_s=0.3)
    run_all(det, [(0.0, RC, 1), (0.0, RA, 1), (0.4,)])
    assert det.reset() == ["cancel", "up"]
