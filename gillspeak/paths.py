"""Filesystem locations. Stdlib-only: imported by the thin CLI."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path


def _xdg(var: str, fallback: str) -> Path:
    value = os.environ.get(var)
    return Path(value) if value else Path.home() / fallback


# gillspeak was called Murmur before 0.6.0. Its folders are moved over the first time they're needed, so an
# upgrade keeps the config, dictionary, history and models.
LEGACY_NAME = "murmur"


def _app_dir(base: Path) -> Path:
    new, old = base / "gillspeak", base / LEGACY_NAME
    if not new.exists() and old.is_dir():
        try:
            old.rename(new)
        except OSError:
            return old  # can't move it (another filesystem?): keep using it rather than start empty
    return new


def config_dir() -> Path:
    return _app_dir(_xdg("XDG_CONFIG_HOME", ".config"))


def data_dir() -> Path:
    return _app_dir(_xdg("XDG_DATA_HOME", ".local/share"))


def cache_dir() -> Path:
    return _app_dir(_xdg("XDG_CACHE_HOME", ".cache"))


def config_file() -> Path:
    return config_dir() / "config.toml"


def dictionary_file() -> Path:
    return config_dir() / "dictionary.toml"


def env_file() -> Path:
    return config_dir() / "env"


def history_db() -> Path:
    return data_dir() / "history.db"


def socket_path() -> Path:
    override = os.environ.get("GILLSPEAK_SOCKET")
    if override:
        return Path(override)
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime:
        candidate = Path(f"/run/user/{os.getuid()}")
        runtime = str(candidate) if candidate.is_dir() else tempfile.gettempdir()
    return Path(runtime) / "gillspeak.sock"
