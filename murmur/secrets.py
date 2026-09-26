"""API key storage: libsecret via keyring, falling back to GEMINI_API_KEY (~/.config/murmur/env)."""

from __future__ import annotations

import logging
import os
import sys

from . import paths

log = logging.getLogger(__name__)

SERVICE = "murmur"
GEMINI_USER = "gemini_api_key"
PROXY_USER = "proxy_token"


def _keyring_get(user: str) -> str | None:
    try:
        import keyring

        return keyring.get_password(SERVICE, user)
    except Exception as e:  # no Secret Service, locked keyring, missing backend...
        log.debug("keyring unavailable: %s", e)
        return None


def _env_file_value(name: str) -> str | None:
    try:
        for line in paths.env_file().read_text(encoding="utf-8").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == name:
                return value.strip().strip("'\"") or None
    except OSError:
        pass
    return None


def get_api_key() -> str | None:
    return _keyring_get(GEMINI_USER) or os.environ.get("GEMINI_API_KEY") or _env_file_value("GEMINI_API_KEY")


def get_proxy_token() -> str | None:
    return _keyring_get(PROXY_USER) or os.environ.get("MURMUR_PROXY_TOKEN") or _env_file_value("MURMUR_PROXY_TOKEN")


def read_secret_from_stdin(prompt: str) -> str:
    if sys.stdin.isatty():
        import getpass

        return getpass.getpass(prompt).strip()
    return sys.stdin.readline().strip()


def write_env_file(name: str, value: str) -> None:
    path = paths.env_file()
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = []
    if path.exists():
        lines = [ln for ln in path.read_text(encoding="utf-8").splitlines() if ln.partition("=")[0].strip() != name]
    lines.append(f"{name}={value}")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    os.chmod(path, 0o600)


def set_key(user: str = GEMINI_USER, env_name: str = "GEMINI_API_KEY") -> str:
    """Store a secret read from stdin. Returns where it was stored."""
    value = read_secret_from_stdin("Paste key (input hidden): ")
    if not value:
        raise ValueError("empty key")
    try:
        import keyring

        keyring.set_password(SERVICE, user, value)
        if keyring.get_password(SERVICE, user) == value:
            return "keyring (libsecret)"
    except Exception as e:
        log.warning("keyring failed (%s); falling back to %s", e, paths.env_file())
    write_env_file(env_name, value)
    return str(paths.env_file())
