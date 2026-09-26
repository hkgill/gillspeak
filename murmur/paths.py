"""Filesystem locations. Stdlib-only: imported by the thin CLI."""

from __future__ import annotations

import os
import tempfile
from pathlib import Path


def _xdg(var: str, fallback: str) -> Path:
    value = os.environ.get(var)
    return Path(value) if value else Path.home() / fallback


def config_dir() -> Path:
    return _xdg("XDG_CONFIG_HOME", ".config") / "murmur"


def data_dir() -> Path:
    return _xdg("XDG_DATA_HOME", ".local/share") / "murmur"


def cache_dir() -> Path:
    return _xdg("XDG_CACHE_HOME", ".cache") / "murmur"


def config_file() -> Path:
    return config_dir() / "config.toml"


def dictionary_file() -> Path:
    return config_dir() / "dictionary.toml"


def env_file() -> Path:
    return config_dir() / "env"


def history_db() -> Path:
    return data_dir() / "history.db"


def socket_path() -> Path:
    override = os.environ.get("MURMUR_SOCKET")
    if override:
        return Path(override)
    runtime = os.environ.get("XDG_RUNTIME_DIR")
    if not runtime:
        candidate = Path(f"/run/user/{os.getuid()}")
        runtime = str(candidate) if candidate.is_dir() else tempfile.gettempdir()
    return Path(runtime) / "murmur.sock"
