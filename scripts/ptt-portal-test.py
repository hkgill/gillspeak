#!/usr/bin/python3
"""Check whether GNOME's GlobalShortcuts portal reports key *release*, which hold-to-talk needs.

Registers one global shortcut through xdg-desktop-portal, then prints every
Activated (press) and Deactivated (release) signal with timings. GNOME shows a
one-time dialog asking you to confirm the shortcut.

Usage: scripts/ptt-portal-test.py [--trigger CTRL+ALT+h] [--seconds 60]

Uses the system python3 (needs python3-gobject), not the gillspeak venv.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import gi

gi.require_version("Gio", "2.0")
from gi.repository import Gio, GLib  # noqa: E402

APP_ID = "io.github.hkgill.gillspeak"
BUS = "org.freedesktop.portal.Desktop"
PATH = "/org/freedesktop/portal/desktop"
IFACE = "org.freedesktop.portal.GlobalShortcuts"
SHORTCUT_ID = "gillspeak-ptt-test"
DESKTOP_FILE = Path.home() / ".local/share/applications" / f"{APP_ID}.desktop"


def ensure_desktop_file() -> None:
    """The portal identifies host (non-Flatpak) apps by an app ID with a matching .desktop file."""
    if DESKTOP_FILE.exists():
        return
    DESKTOP_FILE.parent.mkdir(parents=True, exist_ok=True)
    DESKTOP_FILE.write_text(
        "[Desktop Entry]\nType=Application\nName=gillspeak\nComment=Dictation\n"
        f"Exec={Path.home()}/.local/bin/gillspeak status\nNoDisplay=true\nTerminal=false\n"
    )
    print(f"created {DESKTOP_FILE}")


class PortalTest:
    def __init__(self, trigger: str, seconds: int):
        self.trigger = trigger
        self.seconds = seconds
        self.loop = GLib.MainLoop()
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION)
        self.sender = self.bus.get_unique_name()[1:].replace(".", "_")
        self.session: str | None = None
        self.pressed_at: float | None = None
        self.presses = self.releases = self.repeats = 0
        self.token_n = 0

    # -- request/response plumbing ------------------------------------------
    def _token(self) -> str:
        self.token_n += 1
        return f"gillspeak_ptt_{self.token_n}"

    def _call(self, method: str, params: GLib.Variant, token: str, on_response) -> None:
        """Portal calls return a Request object; the real result arrives later as its Response signal."""
        req_path = f"{PATH}/request/{self.sender}/{token}"

        def handler(_conn, _sender, _path, _iface, _signal, args):
            self.bus.signal_unsubscribe(sub)
            code, results = args.unpack()
            on_response(code, results)

        # Subscribe before calling so a fast Response can't be missed.
        sub = self.bus.signal_subscribe(
            BUS, "org.freedesktop.portal.Request", "Response", req_path, None, Gio.DBusSignalFlags.NO_MATCH_RULE, handler
        )
        self.bus.call_sync(BUS, PATH, IFACE, method, params, None, Gio.DBusCallFlags.NONE, -1, None)

    # -- steps ---------------------------------------------------------------
    def run(self) -> int:
        try:
            self.bus.call_sync(
                BUS, PATH, "org.freedesktop.host.portal.Registry", "Register",
                GLib.Variant("(sa{sv})", (APP_ID, {})), None, Gio.DBusCallFlags.NONE, -1, None,
            )
            print(f"registered app ID {APP_ID}")
        except GLib.Error as e:
            print(f"note: Registry.Register failed ({e.message}); continuing without an app ID")

        for signal in ("Activated", "Deactivated"):
            self.bus.signal_subscribe(BUS, IFACE, signal, PATH, None, Gio.DBusSignalFlags.NONE, self.on_shortcut)

        token = self._token()
        self._call(
            "CreateSession",
            GLib.Variant("(a{sv})", ({
                "handle_token": GLib.Variant("s", token),
                "session_handle_token": GLib.Variant("s", "gillspeak_ptt_session"),
            },)),
            token,
            self.on_session,
        )
        GLib.timeout_add_seconds(self.seconds, self.finish)
        try:
            self.loop.run()
        except KeyboardInterrupt:
            self.finish()
        return self.report()

    def on_session(self, code: int, results: dict) -> None:
        if code != 0:
            print(f"CreateSession failed (response {code})")
            return self.finish()
        self.session = results["session_handle"]
        print(f"session {self.session}")
        token = self._token()
        shortcuts = [(SHORTCUT_ID, {
            "description": GLib.Variant("s", "gillspeak hold-to-talk (test)"),
            "preferred_trigger": GLib.Variant("s", self.trigger),
        })]
        print(f"binding {self.trigger}; confirm in the GNOME dialog if one appears")
        self._call(
            "BindShortcuts",
            GLib.Variant("(oa(sa{sv})sa{sv})", (self.session, shortcuts, "", {"handle_token": GLib.Variant("s", token)})),
            token,
            self.on_bound,
        )

    def on_bound(self, code: int, results: dict) -> None:
        if code != 0:
            print(f"BindShortcuts {'cancelled' if code == 1 else 'failed'} (response {code})")
            return self.finish()
        for sid, props in results.get("shortcuts", []):
            print(f"bound {sid}: {props.get('trigger_description', '?')}")
        print(f"\nHold the shortcut for ~2 s, release, repeat a few times. Ctrl+C or {self.seconds} s to finish.\n")

    def on_shortcut(self, _conn, _sender, _path, _iface, signal, args):
        session, sid, ts, _opts = args.unpack()
        if session != self.session or sid != SHORTCUT_ID:
            return
        now = time.monotonic()
        if signal == "Activated":
            if self.pressed_at is not None:
                self.repeats += 1
                print(f"  press   (repeat while held; portal ts {ts})")
                return
            self.presses += 1
            self.pressed_at = now
            print(f"▶ press   (portal ts {ts})")
        else:
            self.releases += 1
            held = f"{now - self.pressed_at:.2f} s" if self.pressed_at is not None else "?"
            self.pressed_at = None
            print(f"■ release after {held} (portal ts {ts})")

    def finish(self) -> bool:
        if self.session:
            try:
                self.bus.call_sync(BUS, self.session, "org.freedesktop.portal.Session", "Close",
                                   None, None, Gio.DBusCallFlags.NONE, -1, None)
            except GLib.Error:
                pass
        self.loop.quit()
        return False

    def report(self) -> int:
        # A key still held when the run ends has no release yet; don't count it against the portal.
        finished = self.presses - (self.pressed_at is not None)
        print(f"\npresses {self.presses}, releases {self.releases}, extra presses while held {self.repeats}")
        if finished and self.releases >= finished:
            print("RESULT: release events work, so hold-to-talk via the portal is feasible.")
            return 0
        if self.presses:
            print("RESULT: presses arrive but releases don't; the portal can't do hold-to-talk here.")
            return 1
        print("RESULT: no presses received (dialog cancelled, shortcut conflict, or not pressed).")
        return 2


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--trigger", default="CTRL+ALT+h", help="preferred trigger, XDG shortcut syntax (default CTRL+ALT+h)")
    p.add_argument("--seconds", type=int, default=60)
    a = p.parse_args()
    ensure_desktop_file()
    sys.exit(PortalTest(a.trigger, a.seconds).run())


if __name__ == "__main__":
    main()
