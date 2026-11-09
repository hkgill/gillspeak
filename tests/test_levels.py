"""Live mic levels for the on-screen animation, over a real Unix socket."""

import asyncio
import math
import os
import stat
import time

import numpy as np
import pytest

from gillspeak.levels import FRAME, MAX_BUFFERED_BYTES, MIN_DBFS, SAMPLES_PER_FRAME, LevelServer


@pytest.fixture
async def server(tmp_path):
    s = LevelServer(tmp_path / "levels.sock")
    await s.start()
    yield s
    s.close()


@pytest.fixture
async def client(server):
    reader, writer = await asyncio.open_unix_connection(str(server.path))
    while not server._clients:
        await asyncio.sleep(0)
    yield reader
    writer.close()


async def read_frames(reader, n):
    data = await asyncio.wait_for(reader.readexactly(n * FRAME.size), 2)
    return [FRAME.unpack_from(data, i * FRAME.size) for i in range(n)]


def window(**samples):
    block = np.zeros(SAMPLES_PER_FRAME, dtype=np.float32)
    block[3], block[7] = samples.get("low", 0.0), samples.get("high", 0.0)
    return block


async def test_frame_matches_the_visualizer_layout(server, client):
    server.publish(window(low=-0.5, high=0.25))
    seq, low, high, dbfs = (await read_frames(client, 1))[0]
    assert seq == 0
    assert (low, high) == (pytest.approx(-0.5), pytest.approx(0.25))
    assert dbfs == pytest.approx(20 * math.log10(0.5))
    assert FRAME.size == 16


async def test_windows_span_block_boundaries(server, client):
    server.publish(np.full(100, 0.1, dtype=np.float32))
    server.publish(np.full(220, 0.1, dtype=np.float32))
    assert [f[0] for f in await read_frames(client, 2)] == [0, 1]


async def test_overshoot_is_clipped_and_silence_has_a_floor(server, client):
    """The boosted mic can overshoot to 8.0 as it opens; the visualizer expects -1..1."""
    server.publish(window(low=-8.0, high=8.0))
    server.publish(window())
    loud, silent = await read_frames(client, 2)
    assert loud[1:] == (-1.0, 1.0, 0.0)
    assert silent[3] == MIN_DBFS


async def test_new_recording_breaks_the_sequence(server, client):
    """The visualizer only clears the last recording's waveform on a gap in seq."""
    server.publish(window())
    first = (await read_frames(client, 1))[0][0]
    server.new_recording()
    server.publish(window())
    assert (await read_frames(client, 1))[0][0] == first + 2


async def test_new_recording_discards_a_partial_window(server, client):
    server.publish(np.full(100, 0.9, dtype=np.float32))
    server.new_recording()
    server.publish(window(high=0.25))
    assert (await read_frames(client, 1))[0][2] == pytest.approx(0.25)


async def test_a_block_is_sent_paced_and_in_order(server, client):
    """100 ms arrives at once but must play out over time, or the animation stutters."""
    t0 = time.monotonic()
    server.publish(np.zeros(1600, dtype=np.float32))
    frames = await read_frames(client, 10)
    assert time.monotonic() - t0 >= 0.05
    assert [f[0] for f in frames] == list(range(10))


async def test_nothing_is_queued_without_a_client(server):
    server.publish(np.zeros(1600, dtype=np.float32))
    assert not server._pending


async def test_slow_client_is_dropped_and_others_keep_receiving(server, client):
    class Stuck:
        closed = False

        class transport:
            @staticmethod
            def get_write_buffer_size():
                return MAX_BUFFERED_BYTES + 1

        def is_closing(self):
            return False

        def close(self):
            self.closed = True

    stuck = Stuck()
    server._clients.add(stuck)
    server.publish(window())
    await read_frames(client, 1)
    assert stuck.closed and stuck not in server._clients


async def test_socket_is_private_and_removed_on_close(tmp_path):
    s = LevelServer(tmp_path / "levels.sock")
    await s.start()
    assert stat.S_IMODE(os.stat(s.path).st_mode) == 0o600
    s.close()
    assert not s.path.exists()


async def test_stale_socket_is_replaced(tmp_path):
    path = tmp_path / "levels.sock"
    path.write_text("left over from a crash")
    s = LevelServer(path)
    await s.start()
    assert stat.S_ISSOCK(os.stat(path).st_mode)
    s.close()
