"""murmur-keyd: root helper that turns a held Right Ctrl + Right Alt into hold-to-talk events.

GNOME shortcuts can't bind a chord of modifiers only, so this reads the keyboards
through evdev. It is the only process that sees raw key events, and it passes on
nothing but three events, one JSON object per line, to clients of its socket:

    {"event": "start"}   both keys held, alone, for hold_ms
    {"event": "end"}     both released after "start": stop and paste
    {"event": "cancel"}  another key pressed after "start" (e.g. Ctrl+Alt+T): discard

The socket is owned by one user (mode 0600), so only that user's murmurd can listen.

Standalone on purpose: stdlib only, no imports from the murmur package, because
root runs a root-owned copy (/usr/local/libexec/murmur-keyd) rather than code from
the user's writable tool install.
"""

from __future__ import annotations

import argparse
import errno
import logging
import os
import selectors
import socket
import struct
import sys
import time
from pathlib import Path

log = logging.getLogger("murmur-keyd")

EV_KEY = 1
KEY_RIGHTCTRL = 97
KEY_RIGHTALT = 100
KEY_A = 30
EVENT = struct.Struct("llHHi")  # struct input_event on 64-bit: timeval, type, code, value
SKIP_DEVICES = ("ydotoold virtual device",)  # our own paste keystrokes come from here
RESCAN_S = 2.0


class HoldDetector:
    """Pure state machine: feed key events, get start/end/cancel.

    Arms when both chord keys go down with no other key held. Fires "start" after
    hold_s. Any other key pressed while a chord key is down poisons the hold (and
    cancels it if it had started) until both chord keys are released, so Ctrl+Alt+T
    and friends behave normally.
    """

    def __init__(self, keys: tuple[int, ...] = (KEY_RIGHTCTRL, KEY_RIGHTALT), hold_s: float = 0.3):
        self.keys = frozenset(keys)
        self.hold_s = hold_s
        self.down: set[int] = set()
        self.armed_at: float | None = None
        self.active = False
        self.poisoned = False

    def key(self, code: int, value: int, now: float) -> list[str]:
        """value: 1 press, 0 release, 2 autorepeat (ignored)."""
        if value == 2:
            return []
        out: list[str] = []
        if value == 1:
            self.down.add(code)
            if code in self.keys:
                if self.down == self.keys and not self.poisoned:
                    self.armed_at = now
            elif self.down & self.keys:
                self.poisoned = True
                self.armed_at = None
                if self.active:
                    self.active = False
                    out.append("cancel")
        else:
            self.down.discard(code)
            if code in self.keys:
                self.armed_at = None
                # "end" waits for *both* keys: murmurd pastes right after it, and a
                # still-held Alt would turn the paste chord into Ctrl+Alt+V.
                if not self.down & self.keys:
                    if self.active:
                        self.active = False
                        out.append("end")
                    self.poisoned = False
        return out

    def tick(self, now: float) -> list[str]:
        if self.armed_at is not None and not self.active and now - self.armed_at >= self.hold_s:
            self.armed_at = None
            self.active = True
            return ["start"]
        return []

    def deadline(self) -> float | None:
        return None if self.armed_at is None or self.active else self.armed_at + self.hold_s

    def reset(self) -> list[str]:
        """Device lost: forget held keys; end an active hold so murmurd doesn't record forever."""
        out = ["cancel"] if self.active else []
        self.__init__(tuple(self.keys), self.hold_s)
        return out


def _bit(mask_hex: str, n: int) -> bool:
    """/sys capabilities are space-separated hex unsigned longs (64-bit here), most significant first."""
    words = mask_hex.split()
    idx = len(words) - 1 - n // 64
    return 0 <= idx < len(words) and bool(int(words[idx], 16) >> (n % 64) & 1)


def is_keyboard(event_name: str, sys_root: Path = Path("/sys/class/input")) -> bool:
    dev = sys_root / event_name / "device"
    try:
        name = (dev / "name").read_text().strip()
        caps = (dev / "capabilities" / "key").read_text()
    except OSError:
        return False
    if name in SKIP_DEVICES:
        return False
    return all(_bit(caps, k) for k in (KEY_A, KEY_RIGHTCTRL, KEY_RIGHTALT))


class Server:
    def __init__(self, sock_path: Path, owner_uid: int, owner_gid: int, detector: HoldDetector):
        self.sock_path = sock_path
        self.detector = detector
        self.sel = selectors.DefaultSelector()
        self.devices: dict[str, int] = {}  # eventN -> fd
        self.clients: list[socket.socket] = []
        if sock_path.exists() or sock_path.is_symlink():
            sock_path.unlink()
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        old = os.umask(0o177)
        try:
            self.listener.bind(str(sock_path))
        finally:
            os.umask(old)
        os.chown(sock_path, owner_uid, owner_gid)
        self.listener.listen(4)
        self.listener.setblocking(False)
        self.sel.register(self.listener, selectors.EVENT_READ, "listener")

    def scan(self) -> None:
        try:
            names = {n for n in os.listdir("/dev/input") if n.startswith("event")}
        except OSError:
            names = set()
        for name in names - self.devices.keys():
            if not is_keyboard(name):
                continue
            try:
                fd = os.open(f"/dev/input/{name}", os.O_RDONLY | os.O_NONBLOCK | os.O_CLOEXEC)
            except OSError as e:
                log.warning("cannot open /dev/input/%s: %s", name, e)
                continue
            self.devices[name] = fd
            self.sel.register(fd, selectors.EVENT_READ, name)
            log.info("watching /dev/input/%s", name)

    def drop(self, name: str) -> None:
        fd = self.devices.pop(name)
        self.sel.unregister(fd)
        os.close(fd)
        log.info("lost /dev/input/%s", name)
        self.emit(self.detector.reset())

    def emit(self, events: list[str]) -> None:
        for ev in events:
            log.info("hold %s", ev)
            line = f'{{"event": "{ev}"}}\n'.encode()
            for c in list(self.clients):
                try:
                    c.send(line)
                except OSError:
                    self._close_client(c)

    def _close_client(self, c: socket.socket) -> None:
        self.clients.remove(c)
        self.sel.unregister(c)
        c.close()

    def read_device(self, name: str) -> None:
        fd = self.devices[name]
        try:
            data = os.read(fd, EVENT.size * 64)
        except OSError as e:
            if e.errno in (errno.EAGAIN, errno.EINTR):
                return
            return self.drop(name)  # ENODEV: unplugged
        now = time.monotonic()
        for off in range(0, len(data) - EVENT.size + 1, EVENT.size):
            _s, _us, typ, code, value = EVENT.unpack_from(data, off)
            if typ == EV_KEY:
                self.emit(self.detector.key(code, value, now))

    def run(self) -> None:
        next_scan = 0.0
        while True:
            now = time.monotonic()
            if now >= next_scan:
                self.scan()
                next_scan = now + RESCAN_S
            deadline = self.detector.deadline()
            timeout = max(0.0, min(next_scan, deadline or next_scan) - now)
            for key, _ in self.sel.select(timeout):
                if key.data == "listener":
                    try:
                        c, _ = self.listener.accept()
                    except OSError:
                        continue
                    c.setblocking(False)
                    self.clients.append(c)
                    self.sel.register(c, selectors.EVENT_READ, "client")
                elif key.data == "client":
                    try:
                        gone = not key.fileobj.recv(256)
                    except OSError:
                        gone = True
                    if gone:
                        self._close_client(key.fileobj)
                else:
                    self.read_device(key.data)
            self.emit(self.detector.tick(time.monotonic()))


def main() -> None:
    p = argparse.ArgumentParser(description="Right Ctrl + Right Alt hold-to-talk helper for murmurd")
    p.add_argument("--socket", default="/run/murmur-keyd/socket")
    p.add_argument("--owner", required=True, help="user name or UID allowed to connect")
    p.add_argument("--hold-ms", type=int, default=300)
    a = p.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s", stream=sys.stderr)
    import pwd

    pw = pwd.getpwuid(int(a.owner)) if a.owner.isdigit() else pwd.getpwnam(a.owner)
    Server(Path(a.socket), pw.pw_uid, pw.pw_gid, HoldDetector(hold_s=a.hold_ms / 1000)).run()


if __name__ == "__main__":
    main()
