"""Pipe the finished text through a command of the user's choice, such as a local AI model."""

from __future__ import annotations

import asyncio
import os
import signal


class PostProcessError(Exception):
    pass


async def run_command(command: str, text: str, timeout_s: float) -> str:
    """Text goes to the command's stdin and its stdout comes back. Errors never quote the text or stderr."""
    try:
        proc = await asyncio.create_subprocess_shell(
            command,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
            start_new_session=True,  # its own process group, so a timeout also stops anything it started
        )
    except OSError as e:
        raise PostProcessError(f"could not start ({e.strerror or e})") from e
    try:
        out, _ = await asyncio.wait_for(proc.communicate(text.encode("utf-8")), timeout_s)
    except TimeoutError as e:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        await proc.wait()
        raise PostProcessError(f"timed out after {timeout_s:g} s") from e
    if proc.returncode != 0:
        raise PostProcessError(f"exited with status {proc.returncode}")
    result = out.decode("utf-8", errors="replace").strip()
    if not result:
        raise PostProcessError("printed nothing")
    return result
