"""murmur-keyd: hold detection, keyboard detection, and murmurd's handling of hold events."""

import asyncio
import json

from murmur.keyd import KEY_A, KEY_RIGHTALT, KEY_RIGHTCTRL, HoldDetector, _bit, is_keyboard

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
    return out


def test_hold_then_release_starts_and_ends():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RC, 1), (0.05, RA, 1), (0.2,), (0.36,), (2.0, RA, 0), (2.01, RC, 0)]) == ["start", "end"]


def test_either_order_and_releasing_ctrl_first():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RA, 1), (0.0, RC, 1), (0.4,), (1.0, RC, 0), (1.1, RA, 0)]) == ["start", "end"]


def test_quick_tap_does_nothing():
    det = HoldDetector(hold_s=0.3)
    assert run(det, [(0.0, RC, 1), (0.0, RA, 1), (0.1, RA, 0), (0.1, RC, 0), (1.0,)]) == []


def test_autorepeat_is_ignored():
    det = HoldDetector(hold_s=0.3)
    steps = [(0.0, RC, 1), (0.0, RA, 1), (0.4,)] + [(0.5 + i * 0.03, RA, 2) for i in range(20)] + [(1.5, RA, 0)]
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
    assert run(det, [(3.0, RC, 1), (3.0, RA, 1), (3.4,), (4.0, RC, 0)]) == ["start", "end"]


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
    assert det.tick(1.3) == ["start"]
    assert det.deadline() is None
    assert det.reset() == ["cancel"]  # keyboard unplugged mid-hold
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


# -- murmurd side ----------------------------------------------------------------


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
    monkeypatch.setattr("murmur.daemon.KEYD_RETRY_S", 0.01)
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
