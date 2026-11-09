"""Pausing media while dictating, with the D-Bus calls faked."""

import asyncio

from gillspeak.media import MediaPauser

SPOTIFY = "org.mpris.MediaPlayer2.spotify"
BROWSER = "org.mpris.MediaPlayer2.chromium.instance42"


class FakeBus:
    def __init__(self, players):
        self.players = players  # name -> status
        self.commands = []

    async def __call__(self, argv, *, timeout):
        method, dest = argv[argv.index("--method") + 1], argv[argv.index("--dest") + 1]
        if method.endswith("ListNames"):
            names = ", ".join(f"'{n}'" for n in ["org.freedesktop.Notifications", *self.players])
            return 0, f"([{names}],)\n".encode(), b""
        if method.endswith("Properties.Get"):
            return 0, f"(<'{self.players[dest]}'>,)\n".encode(), b""
        self.commands.append((method.rsplit(".", 1)[1], dest))
        return 0, b"()\n", b""


async def test_only_players_that_were_playing_are_paused_and_resumed():
    bus = FakeBus({SPOTIFY: "Playing", BROWSER: "Paused"})
    media = MediaPauser(runner=bus)
    await media.pause()
    await media.resume()
    assert bus.commands == [("Pause", SPOTIFY), ("Play", SPOTIFY)]


async def test_resume_only_once():
    bus = FakeBus({SPOTIFY: "Playing"})
    media = MediaPauser(runner=bus)
    await media.pause()
    await media.resume()
    await media.resume()
    assert bus.commands.count(("Play", SPOTIFY)) == 1


async def test_disabled_touches_nothing():
    bus = FakeBus({SPOTIFY: "Playing"})
    media = MediaPauser(enabled=False, runner=bus)
    await media.pause()
    await media.resume()
    assert bus.commands == []


async def test_a_quick_stop_waits_for_the_pause():
    """Otherwise resume finds nothing to resume and the music stays paused."""
    bus = FakeBus({SPOTIFY: "Playing"})

    async def slow(argv, *, timeout):
        await asyncio.sleep(0.01)
        return await bus(argv, timeout=timeout)

    media = MediaPauser(runner=slow)
    await asyncio.gather(media.pause(), media.resume())
    assert bus.commands == [("Pause", SPOTIFY), ("Play", SPOTIFY)]


async def test_a_failing_bus_never_raises():
    async def broken(argv, *, timeout):
        raise TimeoutError

    media = MediaPauser(runner=broken)
    await media.pause()
    await media.resume()


async def test_a_player_that_refuses_is_not_resumed_later():
    bus = FakeBus({SPOTIFY: "Playing"})

    async def refuse_pause(argv, *, timeout):
        if argv[argv.index("--method") + 1].endswith(".Pause"):
            return 1, b"", b"no"
        return await bus(argv, timeout=timeout)

    media = MediaPauser(runner=refuse_pause)
    await media.pause()
    await media.resume()
    assert bus.commands == []
